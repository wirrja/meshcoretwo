// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.services.backup.BackupDedupKeys
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

/**
 * Exercises [ContactStore] (and, through it, [ContactEntity]/[ContactDto]/[Converters]) against
 * a real in-memory Room database via Robolectric — unlike `EncryptedSharedPreferences`, Room has
 * no `AndroidKeyStore` dependency, so it runs under Robolectric without the workaround
 * [com.meshcoretwo.services.security.KeychainServiceTest] needs.
 */
@RunWith(RobolectricTestRunner::class)
class ContactStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: ContactStore
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = ContactStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun meshContact(
        publicKey: ByteArray = ByteArray(32) { it.toByte() },
        name: String = "Alice",
        flags: ContactFlags = ContactFlags.NONE,
        lastModified: Instant = Instant.ofEpochSecond(1000),
    ) = MeshContact(
        id = publicKey.joinToString("") { "%02x".format(it) },
        publicKey = publicKey,
        type = ContactType.CHAT,
        flags = flags,
        outPathLength = 0xFFu,
        outPath = ByteArray(0),
        advertisedName = name,
        lastAdvertisement = Instant.ofEpochSecond(900),
        latitude = 0.0,
        longitude = 0.0,
        lastModified = lastModified,
    )

    @Test
    fun `fetchContacts is empty for an unknown device`() = runTest {
        assertTrue(store.fetchContacts(radioID).isEmpty())
    }

    @Test
    fun `saveContact inserts a new contact`() = runTest {
        val (id, isNew) = store.saveContact(radioID, meshContact())

        assertTrue(isNew)
        val fetched = store.fetchContact(id)
        assertNotNull(fetched)
        assertEquals("Alice", fetched!!.name)
        assertEquals(radioID, fetched.radioID)
    }

    @Test
    fun `saveContact updates an existing contact matched by radioID and publicKey`() = runTest {
        val publicKey = ByteArray(32) { 0x42 }
        val (firstId, firstIsNew) = store.saveContact(radioID, meshContact(publicKey = publicKey, name = "Alice"))
        val (secondId, secondIsNew) = store.saveContact(radioID, meshContact(publicKey = publicKey, name = "Alice Renamed"))

        assertTrue(firstIsNew)
        assertEquals("Second save for the same (radioID, publicKey) should update, not insert", firstId, secondId)
        assertTrue(!secondIsNew)
        assertEquals(1, store.fetchContacts(radioID).size)
        assertEquals("Alice Renamed", store.fetchContact(firstId)?.name)
    }

    @Test
    fun `saveContact preserves app-only metadata across a wire update`() = runTest {
        val publicKey = ByteArray(32) { 0x11 }
        val (id, _) = store.saveContact(radioID, meshContact(publicKey = publicKey, name = "Bob"))

        // Simulate app-side edits a sync must not clobber.
        val existingEntity = database.contactDao().fetchContact(id)!!
        database.contactDao().update(existingEntity.copy(nickname = "Bobby", isBlocked = true))

        store.saveContact(radioID, meshContact(publicKey = publicKey, name = "Bob Updated"))

        val afterSync = store.fetchContact(id)!!
        assertEquals("Bob Updated", afterSync.name)
        assertEquals("Bobby", afterSync.nickname)
        assertTrue(afterSync.isBlocked)
    }

    @Test
    fun `saveContact preserves the favorite flag bit across a wire update`() = runTest {
        val publicKey = ByteArray(32) { 0x22 }
        store.saveContact(radioID, meshContact(publicKey = publicKey, flags = ContactFlags.FAVORITE))
        val beforeUpdate = store.fetchContact(radioID, publicKey)!!
        assertTrue(beforeUpdate.isFavorite)

        // The device re-sends the contact without the favorite bit set (it doesn't know this
        // phone's local favorite state) — the local favorite must survive.
        store.saveContact(radioID, meshContact(publicKey = publicKey, flags = ContactFlags.NONE))

        val afterUpdate = store.fetchContact(radioID, publicKey)!!
        assertTrue("Local favorite bit must survive a device sync", afterUpdate.isFavorite)
    }

    @Test
    fun `batchSaveContacts upserts many contacts in one transaction`() = runTest {
        val contacts = listOf(
            meshContact(publicKey = ByteArray(32) { 0x01 }, name = "A"),
            meshContact(publicKey = ByteArray(32) { 0x02 }, name = "B"),
            meshContact(publicKey = ByteArray(32) { 0x03 }, name = "C"),
        )

        val count = store.batchSaveContacts(radioID, contacts)

        assertEquals(3, count)
        assertEquals(3, store.fetchContacts(radioID).size)
    }

    @Test
    fun `batchSaveContacts updates instead of duplicate-inserting a repeated public key`() = runTest {
        val publicKey = ByteArray(32) { 0x33 }
        val contacts = listOf(
            meshContact(publicKey = publicKey, name = "First"),
            meshContact(publicKey = publicKey, name = "Second"),
        )

        store.batchSaveContacts(radioID, contacts)

        val all = store.fetchContacts(radioID)
        assertEquals(1, all.size)
        assertEquals("Second", all.first().name)
    }

    @Test
    fun `deleteContact removes the row`() = runTest {
        val (id, _) = store.saveContact(radioID, meshContact())

        store.deleteContact(id)

        assertNull(store.fetchContact(id))
    }

    @Test
    fun `touchContactHeard returns false and does nothing for an unknown contact`() = runTest {
        assertTrue(!store.touchContactHeard(radioID, ByteArray(32) { 0x99.toByte() }, Instant.now()))
    }

    @Test
    fun `touchContactHeard stamps lastHeardTimestamp on a max-wins basis`() = runTest {
        val publicKey = ByteArray(32) { 0x44 }
        val (id, _) = store.saveContact(radioID, meshContact(publicKey = publicKey))
        assertEquals(0u, store.fetchContact(id)!!.lastHeardTimestamp)

        val later = Instant.ofEpochSecond(5000)
        assertTrue(store.touchContactHeard(radioID, publicKey, later))
        assertEquals(5000u, store.fetchContact(id)!!.lastHeardTimestamp)

        // An earlier stamp must not regress a later one already recorded.
        val earlier = Instant.ofEpochSecond(1000)
        store.touchContactHeard(radioID, publicKey, earlier)
        assertEquals(5000u, store.fetchContact(id)!!.lastHeardTimestamp)
    }

    @Test
    fun `fetchContactPublicKeys returns every known public key for a device`() = runTest {
        val keyA = ByteArray(32) { 0x01 }
        val keyB = ByteArray(32) { 0x02 }
        store.saveContact(radioID, meshContact(publicKey = keyA))
        store.saveContact(radioID, meshContact(publicKey = keyB))

        val keys = store.fetchContactPublicKeys(radioID)

        assertEquals(setOf(keyA.toList(), keyB.toList()), keys)
    }

    @Test
    fun `findContactByPublicKey finds a contact regardless of which device it belongs to`() = runTest {
        val publicKey = ByteArray(32) { 0x55 }
        val otherRadioID = UUID.randomUUID()
        val (id, _) = store.saveContact(otherRadioID, meshContact(publicKey = publicKey, name = "Carol"))

        val found = store.findContactByPublicKey(publicKey)

        assertEquals(id, found?.id)
        assertEquals("Carol", found?.name)
    }

    @Test
    fun `findContactByPublicKey returns null for an unknown key`() = runTest {
        assertNull(store.findContactByPublicKey(ByteArray(32) { 0x66 }))
    }

    @Test
    fun `findContactNameByKeyPrefix matches on a short prefix across devices`() = runTest {
        val publicKey = ByteArray(32) { it.toByte() }
        val otherRadioID = UUID.randomUUID()
        store.saveContact(otherRadioID, meshContact(publicKey = publicKey, name = "Dave"))

        assertEquals("Dave", store.findContactNameByKeyPrefix(publicKey.copyOfRange(0, 4)))
        assertEquals("Dave", store.findContactNameByKeyPrefix(publicKey.copyOfRange(0, 6)))
    }

    @Test
    fun `findContactNameByKeyPrefix returns null when nothing matches`() = runTest {
        assertNull(store.findContactNameByKeyPrefix(ByteArray(4) { 0x77.toByte() }))
    }

    // MARK: - Unread / Mention Counters

    @Test
    fun `incrementUnreadCount and clearUnreadCount`() = runTest {
        val (id, _) = store.saveContact(radioID, meshContact())

        store.incrementUnreadCount(id)
        store.incrementUnreadCount(id)
        assertEquals(2, store.fetchContact(id)!!.unreadCount)

        store.clearUnreadCount(id)
        assertEquals(0, store.fetchContact(id)!!.unreadCount)
    }

    @Test
    fun `incrementUnreadCount is a no-op for an unknown contact`() = runTest {
        store.incrementUnreadCount(UUID.randomUUID())
        // No exception is the assertion.
    }

    @Test
    fun `unread mention count increments, decrements clamped at zero, and clears`() = runTest {
        val (id, _) = store.saveContact(radioID, meshContact())

        store.decrementUnreadMentionCount(id)
        assertEquals("decrement on a zero counter must clamp, not underflow", 0, store.fetchContact(id)!!.unreadMentionCount)

        store.incrementUnreadMentionCount(id)
        store.incrementUnreadMentionCount(id)
        assertEquals(2, store.fetchContact(id)!!.unreadMentionCount)

        store.decrementUnreadMentionCount(id)
        assertEquals(1, store.fetchContact(id)!!.unreadMentionCount)

        store.clearUnreadMentionCount(id)
        assertEquals(0, store.fetchContact(id)!!.unreadMentionCount)
    }

    // MARK: - Blocking

    @Test
    fun `updateContactPreferences resolves nickname and toggles blocked, favorite`() = runTest {
        val (id, _) = store.saveContact(radioID, meshContact(name = "Alice"))

        store.updateContactPreferences(id, nickname = "Ally", isBlocked = true, isFavorite = true, unreadCount = 0)

        val updated = store.fetchContact(id)!!
        assertEquals("Ally", updated.nickname)
        assertTrue(updated.isBlocked)
        assertTrue(updated.isFavorite)
        assertEquals(0, updated.unreadCount)
    }

    @Test
    fun `fetchBlockedContacts returns only blocked contacts for the device`() = runTest {
        val (blockedID, _) = store.saveContact(radioID, meshContact(publicKey = ByteArray(32) { 0x01 }, name = "Blocked"))
        store.saveContact(radioID, meshContact(publicKey = ByteArray(32) { 0x02 }, name = "NotBlocked"))
        store.updateContactPreferences(blockedID, nickname = null, isBlocked = true, isFavorite = false, unreadCount = 0)

        val blocked = store.fetchBlockedContacts(radioID)

        assertEquals(1, blocked.size)
        assertEquals("Blocked", blocked.first().name)
    }

    @Test
    fun `saveBlockedChannelSender upserts by (radioID, name) rather than duplicating`() = runTest {
        val first = BlockedChannelSenderDto(id = UUID.randomUUID(), name = "Troll", radioID = radioID, dateBlocked = Instant.ofEpochSecond(100))
        val second = BlockedChannelSenderDto(id = UUID.randomUUID(), name = "Troll", radioID = radioID, dateBlocked = Instant.ofEpochSecond(200))

        store.saveBlockedChannelSender(first)
        store.saveBlockedChannelSender(second)

        val fetched = store.fetchBlockedChannelSenders(radioID)
        assertEquals(1, fetched.size)
        assertEquals(Instant.ofEpochSecond(200), fetched.first().dateBlocked)
    }

    @Test
    fun `blocked channel sender name match is case-sensitive`() = runTest {
        store.saveBlockedChannelSender(BlockedChannelSenderDto(id = UUID.randomUUID(), name = "Alice", radioID = radioID, dateBlocked = Instant.now()))

        assertEquals(1, store.fetchBlockedChannelSenders(radioID).size)
        store.saveBlockedChannelSender(BlockedChannelSenderDto(id = UUID.randomUUID(), name = "ALICE", radioID = radioID, dateBlocked = Instant.now()))
        assertEquals("differently-cased names are separate entries", 2, store.fetchBlockedChannelSenders(radioID).size)
    }

    @Test
    fun `deleteBlockedChannelSender removes only the exact-case match`() = runTest {
        store.saveBlockedChannelSender(BlockedChannelSenderDto(id = UUID.randomUUID(), name = "Alice", radioID = radioID, dateBlocked = Instant.now()))

        store.deleteBlockedChannelSender(radioID, "ALICE")
        assertEquals(1, store.fetchBlockedChannelSenders(radioID).size)

        store.deleteBlockedChannelSender(radioID, "Alice")
        assertTrue(store.fetchBlockedChannelSenders(radioID).isEmpty())
    }

    @Test
    fun `fetchBlockedChannelSenders orders most-recently-blocked first`() = runTest {
        store.saveBlockedChannelSender(BlockedChannelSenderDto(id = UUID.randomUUID(), name = "first", radioID = radioID, dateBlocked = Instant.ofEpochSecond(1_000_000)))
        store.saveBlockedChannelSender(BlockedChannelSenderDto(id = UUID.randomUUID(), name = "second", radioID = radioID, dateBlocked = Instant.ofEpochSecond(3_000_000)))
        store.saveBlockedChannelSender(BlockedChannelSenderDto(id = UUID.randomUUID(), name = "third", radioID = radioID, dateBlocked = Instant.ofEpochSecond(2_000_000)))

        val names = store.fetchBlockedChannelSenders(radioID).map { it.name }

        assertEquals(listOf("second", "third", "first"), names)
    }

    @Test
    fun `isBlockedSender matches a blocked contact's advertised name, a blocked channel sender, or neither`() = runTest {
        val (blockedContactID, _) = store.saveContact(radioID, meshContact(publicKey = ByteArray(32) { 0x03 }, name = "BlockedContact"))
        store.updateContactPreferences(blockedContactID, nickname = null, isBlocked = true, isFavorite = false, unreadCount = 0)
        store.saveBlockedChannelSender(BlockedChannelSenderDto(id = UUID.randomUUID(), name = "BlockedSender", radioID = radioID, dateBlocked = Instant.now()))

        assertTrue(store.isBlockedSender(radioID, "BlockedContact"))
        assertTrue(store.isBlockedSender(radioID, "BlockedSender"))
        assertTrue(!store.isBlockedSender(radioID, "SomeoneElse"))
        assertTrue(!store.isBlockedSender(radioID, null))
    }

    private fun contactDto(
        radioID: UUID = this.radioID,
        publicKey: ByteArray = ByteArray(32) { it.toByte() },
        nickname: String? = null,
        isBlocked: Boolean = false,
        unreadCount: Int = 0,
    ) = ContactDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        publicKey = publicKey,
        name = "Node",
        typeRawValue = 1u,
        flags = 0u,
        outPathLength = 0u,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0u,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0u,
        lastHeardTimestamp = 0u,
        nickname = nickname,
        isBlocked = isBlocked,
        isMuted = false,
        isFavorite = false,
        lastMessageDate = null,
        unreadCount = unreadCount,
        unreadMentionCount = 0,
        ocvPreset = null,
        customOCVArrayString = null,
        avatarImageData = null,
    )

    @Test
    fun `batchInsertContacts inserts a backup contact with no local match`() = runTest {
        val dto = contactDto(publicKey = byteArrayOf(1, 1, 1), nickname = "Bob")

        val result = store.batchInsertContacts(listOf(dto), setOf(radioID))

        assertEquals(1, result.counts.inserted)
        assertEquals(dto.id, result.contactIdsByKey[BackupDedupKeys.contactKey(radioID, dto.publicKey)])
        assertEquals("Bob", store.fetchContact(dto.id)?.nickname)
    }

    @Test
    fun `batchInsertContacts merges into an existing local contact by (radioID, publicKey) and never un-blocks`() = runTest {
        val (localID, _) = store.saveContact(radioID, meshContact(publicKey = byteArrayOf(2, 2, 2), name = "Local"))
        val backup = contactDto(publicKey = byteArrayOf(2, 2, 2), nickname = "FromBackup", isBlocked = true, unreadCount = 7)

        val result = store.batchInsertContacts(listOf(backup), setOf(radioID))

        assertEquals(0, result.counts.inserted)
        assertEquals(1, result.counts.merged)
        assertEquals(localID, result.contactIdsByKey[BackupDedupKeys.contactKey(radioID, backup.publicKey)])
        val merged = store.fetchContact(localID)!!
        assertEquals("FromBackup", merged.nickname)
        assertTrue(merged.isBlocked)
        assertEquals(7, merged.unreadCount)
    }

    @Test
    fun `existingBlockedSenderKeys and batchInsertBlockedChannelSenders dedup by (radioID, name)`() = runTest {
        store.saveBlockedChannelSender(BlockedChannelSenderDto(id = UUID.randomUUID(), name = "Spammer", radioID = radioID, dateBlocked = Instant.now()))
        val existingKeys = store.existingBlockedSenderKeys(setOf(radioID))
        assertEquals(setOf(BackupDedupKeys.blockedChannelSenderKey(radioID, "Spammer")), existingKeys)

        val duplicate = BlockedChannelSenderDto(id = UUID.randomUUID(), name = "Spammer", radioID = radioID, dateBlocked = Instant.now())
        val fresh = BlockedChannelSenderDto(id = UUID.randomUUID(), name = "NewSpammer", radioID = radioID, dateBlocked = Instant.now())

        val counts = store.batchInsertBlockedChannelSenders(listOf(duplicate, fresh), existingKeys)

        assertEquals(1, counts.inserted)
        assertEquals(1, counts.skipped)
        assertEquals(2, store.fetchBlockedChannelSenders(radioID).size)
    }

    @Test
    fun `applyLastMessageDatesToContacts advances lastMessageDate only when the new date is newer`() = runTest {
        val (idNull, _) = store.saveContact(radioID, meshContact(publicKey = byteArrayOf(1, 1, 1)))
        val (idOlder, _) = store.saveContact(radioID, meshContact(publicKey = byteArrayOf(2, 2, 2)))
        val older = Instant.ofEpochSecond(1000)
        val newer = Instant.ofEpochSecond(2000)
        // Give idOlder an existing lastMessageDate newer than what we'll try to apply below.
        database.contactDao().fetchContact(idOlder)?.let { database.contactDao().update(it.copy(lastMessageDate = newer)) }

        store.applyLastMessageDatesToContacts(mapOf(idNull to newer, idOlder to older))

        assertEquals(newer, store.fetchContact(idNull)?.lastMessageDate)
        assertEquals("an already-newer local date must not regress", newer, store.fetchContact(idOlder)?.lastMessageDate)
    }
}
