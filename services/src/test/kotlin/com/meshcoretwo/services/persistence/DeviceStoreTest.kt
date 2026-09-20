// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.hexString
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class DeviceStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: DeviceStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = DeviceStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun deviceDto(
        id: UUID = UUID.randomUUID(),
        radioID: UUID = UUID.randomUUID(),
        publicKey: ByteArray = ByteArray(32) { it.toByte() },
        nodeName: String = "Radio",
        isActive: Boolean = false,
        pathHashMode: UByte = 0u,
        isGhost: Boolean = false,
        bleAddress: String? = null,
        wifiHost: String? = null,
        wifiPort: Int? = null,
    ) = DeviceDto(
        id = id,
        radioID = radioID,
        publicKey = publicKey,
        nodeName = nodeName,
        firmwareVersion = 10u,
        firmwareVersionString = "v1.16.0",
        manufacturerName = "Acme",
        buildDate = "2026-01-01",
        maxContacts = 100u,
        maxChannels = 8u,
        frequency = 915_000u,
        bandwidth = 250_000u,
        spreadingFactor = 10u,
        codingRate = 5u,
        txPower = 20,
        maxTxPower = 20,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0u,
        lastConnected = Instant.ofEpochSecond(1000),
        lastContactSync = 0u,
        isActive = isActive,
        ocvPreset = null,
        customOCVArrayString = null,
        pathHashMode = pathHashMode,
        isGhost = isGhost,
        bleAddress = bleAddress,
        wifiHost = wifiHost,
        wifiPort = wifiPort,
    )

    private fun meshContact(publicKey: ByteArray, name: String = "Bob") = MeshContact(
        id = publicKey.joinToString("") { "%02x".format(it) },
        publicKey = publicKey,
        type = ContactType.CHAT,
        flags = ContactFlags.NONE,
        outPathLength = 0xFFu,
        outPath = ByteArray(0),
        advertisedName = name,
        lastAdvertisement = Instant.ofEpochSecond(900),
        latitude = 0.0,
        longitude = 0.0,
        lastModified = Instant.ofEpochSecond(1000),
    )

    @Test
    fun `saveDevice inserts a new device`() = runTest {
        val dto = deviceDto(nodeName = "New Radio")

        store.saveDevice(dto)

        val fetched = store.fetchDeviceById(dto.id)
        assertEquals("New Radio", fetched?.nodeName)
    }

    @Test
    fun `saveDevice updates an existing device matched by id`() = runTest {
        val id = UUID.randomUUID()
        store.saveDevice(deviceDto(id = id, nodeName = "Original"))

        store.saveDevice(deviceDto(id = id, nodeName = "Renamed"))

        assertEquals("Renamed", store.fetchDeviceById(id)?.nodeName)
    }

    @Test
    fun `fetchDeviceByRadioId finds the matching row`() = runTest {
        val radioID = UUID.randomUUID()
        store.saveDevice(deviceDto(radioID = radioID))

        assertEquals(radioID, store.fetchDeviceByRadioId(radioID)?.radioID)
        assertNull(store.fetchDeviceByRadioId(UUID.randomUUID()))
    }

    @Test
    fun `fetchDevice by publicKey finds the matching row`() = runTest {
        val publicKey = ByteArray(32) { 0x42 }
        store.saveDevice(deviceDto(publicKey = publicKey))

        assertEquals(publicKey.toList(), store.fetchDevice(publicKey)?.publicKey?.toList())
    }

    @Test
    fun `pathHashMode round-trips through save and fetch`() = runTest {
        val dto = deviceDto(pathHashMode = 2u)

        store.saveDevice(dto)

        assertEquals(2u.toUByte(), store.fetchDeviceById(dto.id)?.pathHashMode)
    }

    @Test
    fun `setActiveDevice activates one device and deactivates the rest`() = runTest {
        val first = deviceDto(publicKey = ByteArray(32) { 0x01 }, isActive = true)
        val second = deviceDto(publicKey = ByteArray(32) { 0x02 })
        store.saveDevice(first)
        store.saveDevice(second)

        store.setActiveDevice(second.id, now = Instant.ofEpochSecond(5000))

        assertFalse(store.fetchDeviceById(first.id)!!.isActive)
        val activated = store.fetchDeviceById(second.id)!!
        assertTrue(activated.isActive)
        assertEquals(Instant.ofEpochSecond(5000), activated.lastConnected)
    }

    @Test
    fun `existingDeviceRadioIdsByPublicKey maps every local device by hex publicKey`() = runTest {
        val dto = deviceDto(publicKey = byteArrayOf(1, 2, 3))
        store.saveDevice(dto)

        val map = store.existingDeviceRadioIdsByPublicKey()

        assertEquals(dto.radioID, map[dto.publicKey.hexString])
    }

    @Test
    fun `batchInsertDevices inserts an unmatched device with a fresh id and forces isActive false`() = runTest {
        val dto = deviceDto(publicKey = byteArrayOf(9, 9, 9), isActive = true)

        val counts = store.batchInsertDevices(listOf(dto), emptySet())

        assertEquals(1, counts.inserted)
        val stored = store.fetchDevice(dto.publicKey)
        assertNotEquals(dto.id, stored?.id)
        assertFalse(stored!!.isActive)
    }

    @Test
    fun `batchInsertDevices skips a device whose publicKey already exists`() = runTest {
        val dto = deviceDto(publicKey = byteArrayOf(4, 4, 4))

        val counts = store.batchInsertDevices(listOf(dto), setOf(dto.publicKey.hexString))

        assertEquals(0, counts.inserted)
        assertEquals(1, counts.skipped)
        assertNull(store.fetchDevice(dto.publicKey))
    }

    // MARK: - Ghost-identity reconciliation

    @Test
    fun `demoteDeviceToGhost preserves publicKey and radioID but clears active state, transport, and mints a fresh id`() = runTest {
        val publicKey = ByteArray(32) { 0x01 }
        val radioID = UUID.randomUUID()
        val original = deviceDto(radioID = radioID, publicKey = publicKey, isActive = true, bleAddress = "AA:BB:CC:DD:EE:FF")
        store.saveDevice(original)

        store.demoteDeviceToGhost(original.id)

        assertNull(store.fetchDeviceById(original.id))
        val ghost = store.fetchDevice(publicKey)!!
        assertNotEquals(original.id, ghost.id)
        assertEquals(radioID, ghost.radioID)
        assertEquals(publicKey.toList(), ghost.publicKey.toList())
        assertFalse(ghost.isActive)
        assertTrue(ghost.isGhost)
        assertNull(ghost.bleAddress)
    }

    @Test
    fun `demoteDeviceToGhost is a no-op for a non-existent device`() = runTest {
        store.demoteDeviceToGhost(UUID.randomUUID())
    }

    @Test
    fun `reconcileGhostIdentity re-points the live device to the ghosts radioID, deletes the ghost, and reattaches its contacts`() = runTest {
        val originalPublicKey = ByteArray(32) { 0x01 }
        val originalRadioID = UUID.randomUUID()
        val paired = deviceDto(radioID = originalRadioID, publicKey = originalPublicKey, isActive = true)
        store.saveDevice(paired)
        val contactStore = ContactStore(database)
        contactStore.saveContact(originalRadioID, meshContact(publicKey = ByteArray(32) { 0x99.toByte() }))

        store.demoteDeviceToGhost(paired.id)

        // Radio erased/reflashed with a new keypair, re-paired as a brand-new row.
        val reflashed = deviceDto(publicKey = ByteArray(32) { 0x02 }, isActive = true)
        store.saveDevice(reflashed)
        assertTrue(contactStore.fetchContacts(reflashed.radioID).isEmpty())

        // Config import restores the original private key onto the (still the same, now-renamed) live row.
        val reconciledRadioID = store.reconcileGhostIdentity(reflashed.id, originalPublicKey)

        assertEquals(originalRadioID, reconciledRadioID)
        val reconciled = store.fetchDeviceById(reflashed.id)!!
        assertEquals(originalRadioID, reconciled.radioID)
        assertEquals(originalPublicKey.toList(), reconciled.publicKey.toList())
        assertEquals(1, store.fetchDevices().size) // the ghost row is gone, not just superseded
        assertEquals(1, contactStore.fetchContacts(originalRadioID).size)
    }

    @Test
    fun `reconcileGhostIdentity returns null and leaves the device unchanged when no ghost matches`() = runTest {
        val dto = deviceDto()
        store.saveDevice(dto)

        val result = store.reconcileGhostIdentity(dto.id, dto.publicKey)

        assertNull(result)
        assertEquals(dto.radioID, store.fetchDeviceById(dto.id)?.radioID)
    }

    @Test
    fun `reconcileGhostIdentity merges the ghosts wifi connection when the live device has none`() = runTest {
        val originalPublicKey = ByteArray(32) { 0x03 }
        val paired = deviceDto(publicKey = originalPublicKey, wifiHost = "192.168.1.50", wifiPort = 5000)
        store.saveDevice(paired)
        store.demoteDeviceToGhost(paired.id)
        val reflashed = deviceDto(publicKey = ByteArray(32) { 0x04 })
        store.saveDevice(reflashed)

        store.reconcileGhostIdentity(reflashed.id, originalPublicKey)

        val reconciled = store.fetchDeviceById(reflashed.id)!!
        assertEquals("192.168.1.50", reconciled.wifiHost)
        assertEquals(5000, reconciled.wifiPort)
    }

    @Test
    fun `reconcileGhostIdentity does not migrate a contact written under the interim radioID before reconciliation`() = runTest {
        val originalPublicKey = ByteArray(32) { 0x05 }
        val originalRadioID = UUID.randomUUID()
        val paired = deviceDto(radioID = originalRadioID, publicKey = originalPublicKey)
        store.saveDevice(paired)
        store.demoteDeviceToGhost(paired.id)
        val reflashed = deviceDto(publicKey = ByteArray(32) { 0x06 })
        store.saveDevice(reflashed)
        val contactStore = ContactStore(database)
        // A sync already ran under the short-lived interim radioID before reconciliation caught up.
        contactStore.saveContact(reflashed.radioID, meshContact(publicKey = ByteArray(32) { 0x98.toByte() }))

        store.reconcileGhostIdentity(reflashed.id, originalPublicKey)

        assertEquals(1, contactStore.fetchContacts(reflashed.radioID).size)
        assertTrue(contactStore.fetchContacts(originalRadioID).isEmpty())
    }

    // MARK: - Cascading delete

    private fun contactEntity(
        radioID: UUID,
        id: UUID = UUID.randomUUID(),
        publicKey: ByteArray = ByteArray(32) { it.toByte() },
    ) = ContactEntity(
        id = id,
        radioID = radioID,
        publicKey = publicKey,
        name = "Contact",
        typeRawValue = 0,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0L,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0L,
        lastHeardTimestamp = 0L,
        nickname = null,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        lastMessageDate = null,
        unreadCount = 0,
        unreadMentionCount = 0,
        ocvPreset = null,
        customOCVArrayString = null,
        avatarImageData = null,
    )

    private fun messageEntity(radioID: UUID, id: UUID = UUID.randomUUID()) = MessageEntity(
        id = id,
        radioID = radioID,
        contactID = null,
        channelIndex = null,
        text = "Hello",
        timestamp = 1000L,
        createdAt = Instant.ofEpochSecond(1000),
        sortDate = Instant.ofEpochSecond(1000),
        directionRawValue = 0,
        statusRawValue = 0,
        textTypeRawValue = 0,
        ackCode = null,
        pathLength = 0,
        snr = null,
        pathNodes = null,
        senderKeyPrefix = null,
        senderNodeName = null,
        isRead = false,
        replyToID = null,
        roundTripTime = null,
        sendCount = 1,
        retryAttempt = 0,
        maxRetryAttempts = 3,
        deduplicationKey = null,
        reactionSummary = null,
        senderTimestamp = null,
        routeTypeRawValue = -1,
        heardRepeats = 0,
        containsSelfMention = false,
        mentionSeen = false,
    )

    private fun reactionEntity(messageID: UUID, radioID: UUID, id: UUID = UUID.randomUUID()) = ReactionEntity(
        id = id,
        messageID = messageID,
        emoji = "👍",
        senderName = "Alice",
        messageHash = "ABCDEFGH",
        rawText = "thumbsup ABCDEFGH",
        receivedAt = Instant.ofEpochSecond(1000),
        channelIndex = null,
        contactID = null,
        radioID = radioID,
    )

    private fun messageRepeatEntity(messageID: UUID, id: UUID = UUID.randomUUID()) = MessageRepeatEntity(
        id = id,
        messageID = messageID,
        receivedAt = Instant.ofEpochSecond(1000),
        pathNodes = byteArrayOf(0x01),
        pathLength = 1,
        snr = null,
        rssi = null,
        rxLogEntryID = null,
    )

    private fun pendingSendEntity(radioID: UUID, messageID: UUID, id: UUID = UUID.randomUUID()) = PendingSendEntity(
        id = id,
        radioID = radioID,
        messageID = messageID,
        kindRawValue = 0,
        contactID = null,
        channelIndex = null,
        isResend = false,
        messageText = "Hello",
        messageTimestamp = 1000L,
        localNodeName = null,
        sequence = 1,
        enqueuedAt = Instant.ofEpochSecond(1000),
        attemptCount = 0,
    )

    private fun channelEntity(radioID: UUID, id: UUID = UUID.randomUUID(), index: Int = 0) = ChannelEntity(
        id = id,
        radioID = radioID,
        index = index,
        name = "General",
        secret = ByteArray(16),
        isEnabled = true,
        lastMessageDate = null,
        unreadCount = 0,
        unreadMentionCount = 0,
        notificationLevelRawValue = 0,
        isFavorite = false,
        floodScopeModeRawValue = "inherit",
        regionScope = null,
    )

    private fun blockedChannelSenderEntity(radioID: UUID, id: UUID = UUID.randomUUID(), name: String = "Blocked") =
        BlockedChannelSenderEntity(
            id = id,
            name = name,
            radioID = radioID,
            dateBlocked = Instant.ofEpochSecond(1000),
        )

    private fun rxLogEntity(radioID: UUID, id: UUID = UUID.randomUUID()) = RxLogEntity(
        id = id,
        radioID = radioID,
        receivedAt = Instant.ofEpochSecond(1000),
        snr = null,
        rssi = null,
        routeTypeRawValue = 0,
        payloadTypeRawValue = 0,
        payloadVersion = 1,
        pathLength = 0,
        pathNodes = ByteArray(0),
        packetPayload = ByteArray(0),
        rawPayload = ByteArray(0),
        packetHash = "hash",
        channelIndex = null,
        channelName = null,
        decryptStatusRawValue = 0,
        senderTimestamp = null,
    )

    private fun discoveredNodeEntity(
        radioID: UUID,
        id: UUID = UUID.randomUUID(),
        publicKey: ByteArray = ByteArray(32) { it.toByte() },
    ) = DiscoveredNodeEntity(
        id = id,
        radioID = radioID,
        publicKey = publicKey,
        name = "Discovered",
        typeRawValue = 0,
        lastHeard = Instant.ofEpochSecond(1000),
        lastAdvertTimestamp = 1000L,
        latitude = 0.0,
        longitude = 0.0,
        outPathLength = 0,
        outPath = ByteArray(0),
        inboundHopCount = null,
        inboundHopAdvertTimestamp = null,
    )

    private fun remoteNodeSessionEntity(radioID: UUID, publicKey: ByteArray, id: UUID = UUID.randomUUID()) =
        RemoteNodeSessionEntity(
            id = id,
            radioID = radioID,
            publicKey = publicKey,
            name = "Node",
            roleRawValue = 0,
            latitude = 0.0,
            longitude = 0.0,
            isConnected = false,
            permissionLevelRawValue = 0,
            lastConnectedDate = null,
            lastBatteryMillivolts = null,
            lastUptimeSeconds = null,
            lastNoiseFloor = null,
            unreadCount = 0,
            notificationLevelRawValue = 0,
            lastRxAirtimeSeconds = null,
            neighborCount = 0,
            lastSyncTimestamp = 0L,
            lastMessageDate = null,
        )

    private fun roomMessageEntity(sessionID: UUID, id: UUID = UUID.randomUUID()) = RoomMessageEntity(
        id = id,
        sessionID = sessionID,
        authorKeyPrefix = ByteArray(4),
        authorName = null,
        text = "Hi",
        timestamp = 1000L,
        createdAt = Instant.ofEpochSecond(1000),
        isFromSelf = false,
        deduplicationKey = "dedup",
        statusRawValue = 0,
        ackCode = null,
        roundTripTime = null,
        retryAttempt = 0,
        maxRetryAttempts = 3,
        failureSeen = false,
    )

    private fun tracePathEntity(radioID: UUID, id: UUID = UUID.randomUUID()) = TracePathEntity(
        id = id,
        radioID = radioID,
        name = "Path",
        pathBytes = ByteArray(4),
        hashSize = 1,
        createdDate = Instant.ofEpochSecond(1000),
    )

    private fun tracePathRunEntity(pathID: UUID, id: UUID = UUID.randomUUID()) = TracePathRunEntity(
        id = id,
        pathID = pathID,
        date = Instant.ofEpochSecond(1000),
        success = true,
        roundTripMs = 100,
        hopsSNR = "1.0,2.0",
    )

    private fun nodeStatusSnapshotEntity(nodePublicKey: ByteArray, id: UUID = UUID.randomUUID()) = NodeStatusSnapshotEntity(
        id = id,
        timestamp = Instant.ofEpochSecond(1000),
        nodePublicKey = nodePublicKey,
        batteryMillivolts = null,
        lastSNR = null,
        lastRSSI = null,
        noiseFloor = null,
        uptimeSeconds = null,
        rxAirtimeSeconds = null,
        packetsSent = null,
        packetsReceived = null,
        receiveErrors = null,
        sentDirect = null,
        sentFlood = null,
        receivedDirect = null,
        receivedFlood = null,
        directDuplicates = null,
        floodDuplicates = null,
        postedCount = null,
        postPushCount = null,
        neighborSnapshots = null,
        telemetryEntries = null,
        latitude = null,
        longitude = null,
        altitude = null,
    )

    @Test
    fun `deleteDeviceAndData deletes the device and every child row for its radioID`() = runTest {
        val device = deviceDto()
        store.saveDevice(device)
        val radioID = device.radioID

        database.contactDao().insert(contactEntity(radioID))
        val message = messageEntity(radioID)
        database.messageDao().insert(message)
        database.reactionDao().insert(reactionEntity(messageID = message.id, radioID = radioID))
        database.messageRepeatDao().insert(messageRepeatEntity(messageID = message.id))
        database.pendingSendDao().insert(pendingSendEntity(radioID = radioID, messageID = message.id))
        database.pendingSendDao().insert(pendingSendEntity(radioID = radioID, messageID = UUID.randomUUID()))
        database.channelDao().insert(channelEntity(radioID))
        database.blockedChannelSenderDao().insert(blockedChannelSenderEntity(radioID))
        database.rxLogDao().insert(rxLogEntity(radioID))
        database.discoveredNodeDao().insert(discoveredNodeEntity(radioID))
        val session = remoteNodeSessionEntity(radioID = radioID, publicKey = ByteArray(32) { 0x11 })
        database.remoteNodeSessionDao().insert(session)
        database.roomMessageDao().insert(roomMessageEntity(sessionID = session.id))
        val tracePath = tracePathEntity(radioID)
        database.tracePathDao().insert(tracePath)
        database.tracePathRunDao().insert(tracePathRunEntity(pathID = tracePath.id))

        store.deleteDeviceAndData(device.id)

        assertNull(store.fetchDeviceById(device.id))
        assertTrue(database.contactDao().fetchContacts(radioID).isEmpty())
        assertTrue(database.messageDao().fetchMessageIdsForRadio(radioID).isEmpty())
        assertTrue(database.reactionDao().fetchForMessages(listOf(message.id)).isEmpty())
        assertTrue(database.messageRepeatDao().fetchForMessage(message.id).isEmpty())
        assertTrue(database.pendingSendDao().fetchAll(radioID).isEmpty())
        assertTrue(database.channelDao().fetchChannels(radioID).isEmpty())
        assertTrue(database.blockedChannelSenderDao().fetchAll(radioID).isEmpty())
        assertTrue(database.rxLogDao().fetchRecent(radioID, 10).isEmpty())
        assertTrue(database.discoveredNodeDao().fetchAll(radioID).isEmpty())
        assertTrue(database.remoteNodeSessionDao().fetchByRadioID(radioID).isEmpty())
        assertTrue(database.roomMessageDao().fetchForSession(session.id, 10, 0).isEmpty())
        assertTrue(database.tracePathDao().fetchForRadio(radioID).isEmpty())
        assertTrue(database.tracePathRunDao().fetchForPath(tracePath.id).isEmpty())
    }

    @Test
    fun `deleteDeviceAndData keeps a NodeStatusSnapshot whose public key still has a session on a different device`() = runTest {
        val deviceA = deviceDto(publicKey = ByteArray(32) { 0x51 })
        val deviceB = deviceDto(publicKey = ByteArray(32) { 0x52 })
        store.saveDevice(deviceA)
        store.saveDevice(deviceB)
        val sharedPublicKey = ByteArray(32) { 0x22 }
        database.remoteNodeSessionDao().insert(remoteNodeSessionEntity(radioID = deviceA.radioID, publicKey = sharedPublicKey))
        database.remoteNodeSessionDao().insert(remoteNodeSessionEntity(radioID = deviceB.radioID, publicKey = sharedPublicKey))
        database.nodeStatusSnapshotDao().insert(nodeStatusSnapshotEntity(nodePublicKey = sharedPublicKey))

        store.deleteDeviceAndData(deviceA.id)

        assertTrue(database.nodeStatusSnapshotDao().fetchLatest(sharedPublicKey) != null)
        assertTrue(database.remoteNodeSessionDao().fetchByRadioID(deviceA.radioID).isEmpty())
        assertEquals(1, database.remoteNodeSessionDao().fetchByRadioID(deviceB.radioID).size)
    }

    @Test
    fun `deleteDeviceAndData removes the NodeStatusSnapshot once no session anywhere still references its public key`() = runTest {
        val deviceA = deviceDto(publicKey = ByteArray(32) { 0x53 })
        val deviceB = deviceDto(publicKey = ByteArray(32) { 0x54 })
        store.saveDevice(deviceA)
        store.saveDevice(deviceB)
        val sharedPublicKey = ByteArray(32) { 0x33 }
        database.remoteNodeSessionDao().insert(remoteNodeSessionEntity(radioID = deviceA.radioID, publicKey = sharedPublicKey))
        database.remoteNodeSessionDao().insert(remoteNodeSessionEntity(radioID = deviceB.radioID, publicKey = sharedPublicKey))
        database.nodeStatusSnapshotDao().insert(nodeStatusSnapshotEntity(nodePublicKey = sharedPublicKey))

        store.deleteDeviceAndData(deviceA.id)
        store.deleteDeviceAndData(deviceB.id)

        assertNull(database.nodeStatusSnapshotDao().fetchLatest(sharedPublicKey))
    }

    @Test
    fun `deleteDeviceAndData does not touch a different devices data`() = runTest {
        val deviceA = deviceDto(publicKey = ByteArray(32) { 0x55 })
        val deviceB = deviceDto(publicKey = ByteArray(32) { 0x56 })
        store.saveDevice(deviceA)
        store.saveDevice(deviceB)
        database.contactDao().insert(contactEntity(deviceA.radioID))
        database.contactDao().insert(contactEntity(deviceB.radioID))

        store.deleteDeviceAndData(deviceA.id)

        assertNull(store.fetchDeviceById(deviceA.id))
        assertTrue(store.fetchDeviceById(deviceB.id) != null)
        assertTrue(database.contactDao().fetchContacts(deviceA.radioID).isEmpty())
        assertEquals(1, database.contactDao().fetchContacts(deviceB.radioID).size)
    }

    @Test
    fun `deleteDeviceAndData is a no-op for a non-existent device`() = runTest {
        store.deleteDeviceAndData(UUID.randomUUID())
    }
}
