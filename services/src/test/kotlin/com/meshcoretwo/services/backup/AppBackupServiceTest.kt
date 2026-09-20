// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.BlockedChannelSenderDto
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactEntity
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.DeviceStore
import com.meshcoretwo.services.persistence.DiscoveredNodeStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageRepeatDto
import com.meshcoretwo.services.persistence.MessageRepeatStore
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.NodeStatusSnapshotStore
import com.meshcoretwo.services.persistence.ReactionDto
import com.meshcoretwo.services.persistence.ReactionStore
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.persistence.RoomMessageDto
import com.meshcoretwo.services.persistence.RoomMessageStore
import com.meshcoretwo.services.persistence.TracePathRunDto
import com.meshcoretwo.services.persistence.TracePathStore
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * End-to-end coverage of [AppBackupService]: exercises export -> compress -> decompress -> import
 * across two independent in-memory databases, standing in for "export from this phone, restore on
 * a fresh install" — the scenario every sub-slice since 1 has been building toward.
 */
@RunWith(RobolectricTestRunner::class)
class AppBackupServiceTest {
    private lateinit var sourceDatabase: MeshCoreDatabase
    private lateinit var targetDatabase: MeshCoreDatabase
    private lateinit var sourcePrefs: SharedPreferences
    private lateinit var targetPrefs: SharedPreferences
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        sourceDatabase = newInMemoryDatabase()
        targetDatabase = newInMemoryDatabase()
        sourcePrefs = freshPrefs()
        targetPrefs = freshPrefs()
    }

    @After
    fun tearDown() {
        sourceDatabase.close()
        targetDatabase.close()
    }

    private fun newInMemoryDatabase() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java).allowMainThreadQueries().build()

    private fun freshPrefs() =
        RuntimeEnvironment.getApplication().getSharedPreferences("app-backup-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    /** [sourceDatabase]/[sourcePrefs] pair for [db] === [sourceDatabase], [targetDatabase]/[targetPrefs] otherwise — two independent "phones", never sharing state. */
    private fun service(db: MeshCoreDatabase) = AppBackupService(db, if (db === sourceDatabase) sourcePrefs else targetPrefs)

    @Test
    fun `exportEnvelope on an empty database returns an all-zero manifest`() = runTest {
        val envelope = service(sourceDatabase).exportEnvelope("1.0", "1")

        assertTrue(envelope.manifest.validate(envelope.actualCounts))
        assertEquals(0, envelope.manifest.deviceCount)
        assertEquals(0, envelope.manifest.messageCount)
    }

    @Test
    fun `exportEnvelope redacts device radio config before it leaves the database`() = runTest {
        DeviceStore(sourceDatabase).saveDevice(deviceDto(frequency = 915_123u, blePin = 4242u))

        val envelope = service(sourceDatabase).exportEnvelope("1.0", "1")

        val exported = envelope.devices.single()
        assertEquals(0u, exported.blePin)
        assertEquals(915_000u, exported.frequency)
    }

    @Test
    fun `round-trips a fully populated database into a fresh one`() = runTest {
        val ids = populate(sourceDatabase)
        sourcePrefs.edit().putString("selectedThemeID", "midnight").apply()

        val backupFile = service(sourceDatabase).exportBackupFile("2.0", "42")
        val result = service(targetDatabase).importBackupFile(backupFile)

        assertEquals("midnight", targetPrefs.getString("selectedThemeID", null))
        assertTrue(result.userDefaultsRestored)

        assertEquals(1, result.counts(BackupModelKind.DEVICES).inserted)
        assertEquals(1, result.counts(BackupModelKind.CONTACTS).inserted)
        assertEquals(1, result.counts(BackupModelKind.CHANNELS).inserted)
        assertEquals(2, result.counts(BackupModelKind.MESSAGES).inserted)
        assertEquals(1, result.counts(BackupModelKind.MESSAGE_REPEATS).inserted)
        assertEquals(1, result.counts(BackupModelKind.REACTIONS).inserted)
        assertEquals(1, result.counts(BackupModelKind.ROOM_MESSAGES).inserted)
        assertEquals(1, result.counts(BackupModelKind.REMOTE_NODE_SESSIONS).inserted)
        assertEquals(1, result.counts(BackupModelKind.SAVED_TRACE_PATHS).inserted)
        assertEquals(1, result.counts(BackupModelKind.BLOCKED_CHANNEL_SENDERS).inserted)
        assertEquals(1, result.counts(BackupModelKind.NODE_STATUS_SNAPSHOTS).inserted)
        assertEquals(1, result.counts(BackupModelKind.DISCOVERED_NODES).inserted)
        assertTrue(result.hasRestoredChanges)

        // The device didn't exist locally, so its radioID is preserved verbatim (no remap needed) —
        // every relationship below can be checked directly against the original ids.
        val contactStore = ContactStore(targetDatabase)
        val importedContact = contactStore.fetchContacts(radioID).single()
        assertEquals(ids.contactID, importedContact.id)

        val messageStore = MessageStore(targetDatabase)
        val reply = messageStore.fetchMessage(ids.replyMessageID)!!
        assertEquals(ids.parentMessageID, reply.replyToID)
        val parent = messageStore.fetchMessage(ids.parentMessageID)!!
        assertEquals(1, parent.heardRepeats)
        assertEquals("👍:1", parent.reactionSummary)

        val roomMessageStore = RoomMessageStore(targetDatabase)
        assertEquals(1, roomMessageStore.fetchMessages(ids.sessionID).size)

        val channelStore = ChannelStore(targetDatabase)
        assertEquals(1, channelStore.fetchChannels(radioID).size)
    }

    @Test
    fun `parseBackupFile decodes without writing anything to the database`() = runTest {
        populate(sourceDatabase)
        val backupFile = service(sourceDatabase).exportBackupFile("2.0", "42")

        val envelope = service(targetDatabase).parseBackupFile(backupFile)

        assertEquals(1, envelope.contacts.size)
        assertTrue(ContactStore(targetDatabase).fetchAllContacts().isEmpty())
    }

    @Test
    fun `importEnvelope commits a previously-parsed envelope`() = runTest {
        populate(sourceDatabase)
        val backupFile = service(sourceDatabase).exportBackupFile("2.0", "42")
        val targetService = service(targetDatabase)
        val envelope = targetService.parseBackupFile(backupFile)

        val result = targetService.importEnvelope(envelope)

        assertEquals(1, result.counts(BackupModelKind.CONTACTS).inserted)
        assertEquals(1, ContactStore(targetDatabase).fetchAllContacts().size)
    }

    @Test
    fun `re-importing the same backup is idempotent`() = runTest {
        populate(sourceDatabase)
        sourcePrefs.edit().putString("selectedThemeID", "midnight").apply()
        val backupFile = service(sourceDatabase).exportBackupFile("2.0", "42")
        val targetService = service(targetDatabase)
        targetService.importBackupFile(backupFile)

        val secondResult = targetService.importBackupFile(backupFile)

        assertEquals(0, secondResult.totalInserted)
        assertFalse(secondResult.userDefaultsRestored)
        assertEquals(1, ContactStore(targetDatabase).fetchContacts(radioID).size)
        assertEquals(2, MessageStore(targetDatabase).fetchMessages(contactID = ContactStore(targetDatabase).fetchContacts(radioID).single().id).size)
    }

    @Test
    fun `importBackupFile rejects a payload larger than the compressed-size cap`() = runTest {
        val oversized = ByteArray((BackupZlibCodec.MAX_BACKUP_COMPRESSED_BYTES + 1).toInt())

        val error = try {
            service(targetDatabase).importBackupFile(oversized)
            null
        } catch (e: AppBackupError.FileTooLarge) {
            e
        }
        assertEquals(BackupZlibCodec.MAX_BACKUP_COMPRESSED_BYTES, error?.maxBytes)
    }

    @Test
    fun `importBackupFile rejects data that isn't valid zlib`() = runTest {
        var threw = false
        try {
            service(targetDatabase).importBackupFile(byteArrayOf(1, 2, 3, 4))
        } catch (e: AppBackupError.InvalidFile) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `importBackupFile rejects a newer-than-supported format version`() = runTest {
        val envelope = service(sourceDatabase).exportEnvelope("1.0", "1")
        val futureJson = envelope.toBackupJson().apply { put("version", AppBackupEnvelope.CURRENT_VERSION + 1) }
        val futureFile = BackupZlibCodec.compress(futureJson.toString().toByteArray(Charsets.UTF_8))

        val error = try {
            service(targetDatabase).importBackupFile(futureFile)
            null
        } catch (e: AppBackupError.UnsupportedVersion) {
            e
        }
        assertEquals(AppBackupEnvelope.CURRENT_VERSION + 1, error?.found)
    }

    @Test
    fun `importBackupFile rejects a manifest whose declared counts don't match the actual arrays`() = runTest {
        val envelope = service(sourceDatabase).exportEnvelope("1.0", "1")
        val tamperedJson = envelope.toBackupJson().apply {
            put("manifest", getJSONObject("manifest").apply { put("contactCount", 99) })
        }
        val tamperedFile = BackupZlibCodec.compress(tamperedJson.toString().toByteArray(Charsets.UTF_8))

        var threw = false
        try {
            service(targetDatabase).importBackupFile(tamperedFile)
        } catch (e: AppBackupError.CorruptedManifest) {
            threw = true
        }
        assertTrue(threw)
    }

    // MARK: - fixtures

    private data class PopulatedIds(val contactID: UUID, val parentMessageID: UUID, val replyMessageID: UUID, val sessionID: UUID)

    /** Inserts one of each of the 12 backup model types into [db], with realistic cross-references. */
    private suspend fun populate(db: MeshCoreDatabase): PopulatedIds {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        DeviceStore(db).saveDevice(deviceDto())

        // ContactStore's only non-backup insert path takes a MeshContact, not a ContactDto, and
        // batchInsertContacts is what we're trying to test the far side of — so seed via the DAO
        // directly, same as several *StoreTest filler-row helpers already do in this module.
        val contact = contactDto()
        db.contactDao().insert(
            ContactEntity(
                id = contact.id, radioID = contact.radioID, publicKey = contact.publicKey, name = contact.name,
                typeRawValue = contact.typeRawValue.toInt(), flags = 0, outPathLength = 0, outPath = ByteArray(0),
                lastAdvertTimestamp = 0, latitude = 0.0, longitude = 0.0, lastModified = 0, lastHeardTimestamp = 0,
                nickname = null, isBlocked = false, isMuted = false, isFavorite = false, lastMessageDate = null,
                unreadCount = 0, unreadMentionCount = 0, ocvPreset = null, customOCVArrayString = null, avatarImageData = null,
            ),
        )

        ChannelStore(db).saveChannel(radioID, ChannelInfo(1u, "General", ByteArray(16) { 0x02 }))

        val messageStore = MessageStore(db)
        val parentMessage = messageDto(id = UUID.randomUUID(), contactID = contact.id, createdAt = now)
        messageStore.saveMessage(parentMessage)
        val replyMessage = messageDto(id = UUID.randomUUID(), contactID = contact.id, replyToID = parentMessage.id, createdAt = now.plusSeconds(1))
        messageStore.saveMessage(replyMessage)

        MessageRepeatStore(db).saveMessageRepeat(
            MessageRepeatDto(id = UUID.randomUUID(), messageID = parentMessage.id, receivedAt = now, pathNodes = ByteArray(1), pathLength = 1u, snr = null, rssi = null, rxLogEntryID = null),
        )
        ReactionStore(db).saveReaction(
            ReactionDto(id = UUID.randomUUID(), messageID = parentMessage.id, emoji = "👍", senderName = "Alice", messageHash = "abc", rawText = "x", receivedAt = now, channelIndex = null, contactID = contact.id, radioID = radioID),
        )

        val session = remoteNodeSessionDto()
        RemoteNodeSessionStore(db).saveSession(session)
        RoomMessageStore(db).saveMessage(RoomMessageDto(sessionID = session.id, authorKeyPrefix = ByteArray(4), text = "hi", timestamp = 1u, createdAt = now))

        TracePathStore(db).createSavedTracePath(
            radioID, "Tower", byteArrayOf(1, 2), hashSize = 1,
            initialRun = TracePathRunDto(id = UUID.randomUUID(), date = now, success = true, roundTripMs = 500, hopsSNR = listOf(4.5)),
        )
        ContactStore(db).saveBlockedChannelSender(BlockedChannelSenderDto(id = UUID.randomUUID(), name = "Spammer", radioID = radioID, dateBlocked = now))
        NodeStatusSnapshotStore(db).recordNodeStatusSnapshot(nodePublicKey = ByteArray(32) { it.toByte() }, status = null, telemetry = null, neighbors = null, location = null)
        DiscoveredNodeStore(db).upsertDiscoveredNode(radioID, meshContact())

        return PopulatedIds(contact.id, parentMessage.id, replyMessage.id, session.id)
    }

    private fun meshContact() = MeshContact(
        id = "aa", publicKey = ByteArray(32) { (it + 1).toByte() }, type = ContactType.REPEATER,
        flags = ContactFlags.NONE, outPathLength = 0xFFu, outPath = ByteArray(0), advertisedName = "Repeater",
        lastAdvertisement = Instant.ofEpochSecond(900), latitude = 12.0, longitude = 34.0, lastModified = Instant.ofEpochSecond(900),
    )

    private fun deviceDto(
        publicKey: ByteArray = ByteArray(32) { it.toByte() },
        frequency: UInt = 915_000u,
        blePin: UInt = 0u,
    ) = DeviceDto(
        id = UUID.randomUUID(), radioID = radioID, publicKey = publicKey, nodeName = "Radio", firmwareVersion = 10u,
        firmwareVersionString = "v1.16.0", manufacturerName = "Acme", buildDate = "2026-01-01", maxContacts = 100u,
        maxChannels = 8u, frequency = frequency, bandwidth = 250_000u, spreadingFactor = 10u, codingRate = 5u,
        txPower = 20, maxTxPower = 20, latitude = 0.0, longitude = 0.0, blePin = blePin,
        lastConnected = Instant.now().truncatedTo(ChronoUnit.MILLIS), lastContactSync = 0u, isActive = true,
        ocvPreset = null, customOCVArrayString = null,
    )

    private fun contactDto(publicKey: ByteArray = ByteArray(32) { (it + 5).toByte() }) = ContactDto(
        id = UUID.randomUUID(), radioID = radioID, publicKey = publicKey, name = "Bob", typeRawValue = 1u, flags = 0u,
        outPathLength = 0u, outPath = ByteArray(0), lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0,
        lastModified = 0u, lastHeardTimestamp = 0u, nickname = null, isBlocked = false, isMuted = false, isFavorite = false,
        lastMessageDate = null, unreadCount = 0, unreadMentionCount = 0, ocvPreset = null, customOCVArrayString = null, avatarImageData = null,
    )

    private fun messageDto(
        id: UUID,
        contactID: UUID?,
        replyToID: UUID? = null,
        createdAt: Instant,
    ) = MessageDto(
        id = id, radioID = radioID, contactID = contactID, channelIndex = null, text = "hi", timestamp = createdAt.epochSecond.toUInt(),
        createdAt = createdAt, sortDate = createdAt, direction = MessageDirection.OUTGOING, status = MessageStatus.SENT,
        textType = TextType.PLAIN_TEXT, ackCode = null, pathLength = 0u, snr = null, pathNodes = null, senderKeyPrefix = null,
        senderNodeName = null, isRead = true, replyToID = replyToID, roundTripTime = null, sendCount = 1, retryAttempt = 0,
        maxRetryAttempts = 0, deduplicationKey = null, reactionSummary = null, senderTimestamp = null, routeType = null, heardRepeats = 0,
    )

    private fun remoteNodeSessionDto(publicKey: ByteArray = ByteArray(32) { (it + 9).toByte() }) = RemoteNodeSessionDto(
        id = UUID.randomUUID(), radioID = radioID, publicKey = publicKey, name = "Room", role = RemoteNodeRole.ROOM_SERVER,
    )
}
