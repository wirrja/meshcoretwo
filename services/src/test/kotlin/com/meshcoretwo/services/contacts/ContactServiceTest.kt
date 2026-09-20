// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.contacts

import androidx.room.Room
import com.meshcoretwo.protocol.ContactFetchResult
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactSessionOps
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.ErrorCode
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.DeviceStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.utilities.VContactIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

/**
 * Exercises [ContactService] against a real in-memory [ContactStore] (Room, via Robolectric —
 * see [com.meshcoretwo.services.persistence.ContactStoreTest]) and [FakeContactSessionOps], a
 * hand-written test double for the narrow [ContactSessionOps] role interface. This is exactly
 * the pattern [ContactSessionOps]'s own doc comment (and `ContactPersisting`'s on the Swift side)
 * calls out narrow role interfaces for: a full `MeshCoreSession` + `MockTransport` handshake
 * isn't needed just to test add/remove/sync logic.
 */
@RunWith(RobolectricTestRunner::class)
class ContactServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeContactSessionOps
    private lateinit var deviceStore: DeviceStore
    private lateinit var contactStore: ContactStore
    private lateinit var messageStore: MessageStore
    private lateinit var service: ContactService
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeContactSessionOps()
        deviceStore = DeviceStore(database)
        contactStore = ContactStore(database)
        messageStore = MessageStore(database)
        service = ContactService(session, contactStore, deviceStore, messageStore)
    }

    /** Registers a device row for [radioID] with the given self [publicKey], enabling pruning. */
    private suspend fun registerDevice(publicKey: ByteArray) {
        deviceStore.saveDevice(
            DeviceDto(
                id = UUID.randomUUID(),
                radioID = radioID,
                publicKey = publicKey,
                nodeName = "Test Radio",
                firmwareVersion = 10u,
                firmwareVersionString = "v1.16.0",
                manufacturerName = "",
                buildDate = "",
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
                lastConnected = Instant.now(),
                lastContactSync = 0u,
                isActive = true,
                ocvPreset = null,
                customOCVArrayString = null,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun meshContact(
        publicKey: ByteArray = ByteArray(32) { it.toByte() },
        name: String = "Alice",
    ) = MeshContact(
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
    fun `syncContacts persists everything the device reports`() = runTest {
        session.contactsToReturn = ContactFetchResult(
            contacts = listOf(meshContact(publicKey = ByteArray(32) { 0x01 }), meshContact(publicKey = ByteArray(32) { 0x02 })),
            reportedTotal = 2,
        )

        val result = service.syncContacts(radioID)

        assertEquals(2, result.contactsReceived)
        assertEquals(1000u.toUInt(), result.lastSyncTimestamp)
        assertTrue(!result.isIncremental)
        assertEquals(2, service.getContacts(radioID).size)
    }

    @Test
    fun `syncContacts with a since date reports an incremental sync`() = runTest {
        session.contactsToReturn = ContactFetchResult(contacts = listOf(meshContact()), reportedTotal = null)

        val result = service.syncContacts(radioID, since = Instant.ofEpochSecond(500))

        assertTrue(result.isIncremental)
    }

    @Test
    fun `full sync prunes a local contact the device no longer reports`() = runTest {
        val selfKey = ByteArray(32) { 0x50 }
        registerDevice(selfKey)
        val survivorKey = ByteArray(32) { 0x01 }
        val removedKey = ByteArray(32) { 0x02 }
        service.addOrUpdateContact(radioID, meshContact(publicKey = survivorKey))
        service.addOrUpdateContact(radioID, meshContact(publicKey = removedKey))

        // A complete snapshot (received count meets reportedTotal) that no longer lists removedKey.
        session.contactsToReturn = ContactFetchResult(contacts = listOf(meshContact(publicKey = survivorKey)), reportedTotal = 1)

        service.syncContacts(radioID)

        val remaining = service.getContacts(radioID)
        assertEquals(1, remaining.size)
        assertTrue(remaining.any { it.publicKey.contentEquals(survivorKey) })
    }

    @Test
    fun `full sync does not prune the ZephCore V-contact`() = runTest {
        val selfKey = ByteArray(32) { 0x60 }
        registerDevice(selfKey)
        val vContactKey = VContactIdentity.publicKey(selfKey)!!
        service.addOrUpdateContact(radioID, meshContact(publicKey = vContactKey, name = "v.contact"))

        // Device reports zero contacts (the V-contact is synthetic and never appears in the reply).
        session.contactsToReturn = ContactFetchResult(contacts = emptyList(), reportedTotal = 0)

        service.syncContacts(radioID)

        assertEquals(1, service.getContacts(radioID).size)
    }

    @Test
    fun `full sync skips pruning on an incomplete snapshot`() = runTest {
        val selfKey = ByteArray(32) { 0x70 }
        registerDevice(selfKey)
        service.addOrUpdateContact(radioID, meshContact(publicKey = ByteArray(32) { 0x03 }))

        // reportedTotal (5) exceeds what was actually received (0) — a truncated stream, not a
        // real deletion.
        session.contactsToReturn = ContactFetchResult(contacts = emptyList(), reportedTotal = 5)

        service.syncContacts(radioID)

        assertEquals(1, service.getContacts(radioID).size)
    }

    @Test
    fun `full sync skips pruning without a registered device`() = runTest {
        // No registerDevice() call: pruneOrphans has no self key to exclude the V-contact with.
        service.addOrUpdateContact(radioID, meshContact(publicKey = ByteArray(32) { 0x04 }))

        session.contactsToReturn = ContactFetchResult(contacts = emptyList(), reportedTotal = 0)

        service.syncContacts(radioID)

        assertEquals(1, service.getContacts(radioID).size)
    }

    @Test
    fun `syncContacts wraps a session failure`() = runTest {
        session.getContactsError = MeshCoreError.Timeout

        try {
            service.syncContacts(radioID)
            fail("expected ContactServiceError.SessionError")
        } catch (error: ContactServiceError.SessionError) {
            assertEquals(MeshCoreError.Timeout, error.error)
        }
    }

    @Test
    fun `addOrUpdateContact sends to the device and persists locally`() = runTest {
        val contact = meshContact(name = "New Friend")

        service.addOrUpdateContact(radioID, contact)

        assertEquals(1, session.addedContacts.size)
        val stored = service.getContact(radioID, contact.publicKey)
        assertEquals("New Friend", stored?.name)
    }

    @Test
    fun `addOrUpdateContact maps a table-full device error`() = runTest {
        session.addContactError = MeshCoreError.DeviceError(ErrorCode.TABLE_FULL.value)

        try {
            service.addOrUpdateContact(radioID, meshContact())
            fail("expected ContactServiceError.ContactTableFull")
        } catch (error: ContactServiceError.ContactTableFull) {
            // expected
        }
    }

    @Test
    fun `removeContact deletes the device and local copies`() = runTest {
        val contact = meshContact()
        service.addOrUpdateContact(radioID, contact)

        service.removeContact(radioID, contact.publicKey)

        assertEquals(listOf(contact.publicKey), session.removedPublicKeys)
        assertNull(service.getContact(radioID, contact.publicKey))
    }

    @Test
    fun `removeContact maps a not-found device error`() = runTest {
        session.removeContactError = MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value)

        try {
            service.removeContact(radioID, ByteArray(32))
            fail("expected ContactServiceError.ContactNotFound")
        } catch (error: ContactServiceError.ContactNotFound) {
            // expected
        }
    }

    @Test
    fun `removeContact is a local no-op when no local row exists`() = runTest {
        // The device accepts the removal; there was never a local row to clean up.
        service.removeContact(radioID, ByteArray(32) { 0x99.toByte() })

        assertEquals(1, session.removedPublicKeys.size)
    }

    @Test
    fun `resetPath flood-routes the contact while preserving an unmodeled type byte`() = runTest {
        // The mesh contact carries a raw type byte ContactType doesn't model; resetPath must persist it verbatim.
        val unmodeledType: UByte = 0x7Fu
        val publicKey = ByteArray(32) { it.toByte() }
        val contact = MeshContact(
            id = publicKey.joinToString("") { "%02x".format(it) },
            publicKey = publicKey,
            type = ContactType.CHAT,
            typeRawValue = unmodeledType,
            flags = ContactFlags.NONE,
            outPathLength = 2u,
            outPath = byteArrayOf(0xA3.toByte(), 0x7F),
            advertisedName = "Alice",
            lastAdvertisement = Instant.ofEpochSecond(900),
            latitude = 0.0,
            longitude = 0.0,
            lastModified = Instant.ofEpochSecond(1000),
        )
        contactStore.saveContact(radioID, contact)

        service.resetPath(radioID, contact.publicKey)

        assertEquals(listOf(contact.publicKey), session.resetPathPublicKeys)
        val reset = service.getContact(radioID, contact.publicKey)
        assertTrue(reset!!.isFloodRouted)
        assertEquals(0, reset.outPath.size)
        assertEquals(unmodeledType, reset.typeRawValue)
    }

    @Test
    fun `resetPath maps a not-found device error`() = runTest {
        session.resetPathError = MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value)

        try {
            service.resetPath(radioID, ByteArray(32))
            fail("expected ContactServiceError.ContactNotFound")
        } catch (error: ContactServiceError.ContactNotFound) {
            // expected
        }
    }

    @Test
    fun `resetPath is a local no-op when no local row exists`() = runTest {
        service.resetPath(radioID, ByteArray(32) { 0x99.toByte() })

        assertEquals(1, session.resetPathPublicKeys.size)
    }

    @Test
    fun `clearContactMessages deletes messages and resets unread badges`() = runTest {
        val contact = meshContact()
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id
        contactStore.incrementUnreadCount(contactID)
        contactStore.incrementUnreadMentionCount(contactID)
        messageStore.saveMessage(directMessage(contactID))

        service.clearContactMessages(contactID)

        assertTrue(messageStore.fetchMessages(contactID).isEmpty())
        val after = service.getContactById(contactID)!!
        assertEquals(0, after.unreadCount)
        assertEquals(0, after.unreadMentionCount)
    }

    @Test
    fun `markConversationRead resets unread badges but leaves messages in place`() = runTest {
        val contact = meshContact()
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id
        contactStore.incrementUnreadCount(contactID)
        contactStore.incrementUnreadMentionCount(contactID)
        messageStore.saveMessage(directMessage(contactID))

        service.markConversationRead(contactID)

        assertEquals(1, messageStore.fetchMessages(contactID).size)
        val after = service.getContactById(contactID)!!
        assertEquals(0, after.unreadCount)
        assertEquals(0, after.unreadMentionCount)
    }

    @Test
    fun `observeContacts reflects an unread reset without a separate fetch`() = runTest {
        val contact = meshContact()
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id
        contactStore.incrementUnreadCount(contactID)

        assertEquals(1, service.observeContacts(radioID).first().first { it.id == contactID }.unreadCount)

        service.markConversationRead(contactID)

        assertEquals(0, service.observeContacts(radioID).first().first { it.id == contactID }.unreadCount)
    }

    @Test
    fun `updateContactPreferences resolves nickname, blocked, and favorite`() = runTest {
        val contact = meshContact()
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id

        service.updateContactPreferences(contactID, nickname = "  Ally  ", isBlocked = true, isFavorite = true)

        val updated = service.getContactById(contactID)!!
        assertEquals("Ally", updated.nickname)
        assertTrue(updated.isBlocked)
        assertTrue(updated.isFavorite)
    }

    @Test
    fun `updateContactPreferences clears the nickname on a blank string`() = runTest {
        val contact = meshContact()
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id
        service.updateContactPreferences(contactID, nickname = "Ally")

        service.updateContactPreferences(contactID, nickname = "   ")

        assertNull(service.getContactById(contactID)!!.nickname)
    }

    @Test
    fun `updateContactPreferences leaves the nickname untouched when null`() = runTest {
        val contact = meshContact()
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id
        service.updateContactPreferences(contactID, nickname = "Ally")

        service.updateContactPreferences(contactID, isFavorite = true)

        assertEquals("Ally", service.getContactById(contactID)!!.nickname)
    }

    @Test
    fun `updateContactPreferences zeroes unread on block but leaves the mention counter alone`() = runTest {
        val contact = meshContact()
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id
        contactStore.incrementUnreadCount(contactID)
        contactStore.incrementUnreadMentionCount(contactID)

        service.updateContactPreferences(contactID, isBlocked = true)

        val after = service.getContactById(contactID)!!
        assertEquals("blocking zeroes unread, matching Swift", 0, after.unreadCount)
        assertEquals("blocking leaves unreadMentionCount untouched, matching Swift as-is", 1, after.unreadMentionCount)
    }

    @Test
    fun `updateContactPreferences deletes that contact's channel messages when it becomes blocked`() = runTest {
        val contact = meshContact(name = "Spammer")
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id
        messageStore.saveMessage(directMessage(contactID = null).copy(channelIndex = 1u, senderNodeName = "Spammer"))

        service.updateContactPreferences(contactID, isBlocked = true)

        assertTrue(messageStore.fetchMessages(radioID, 1u).isEmpty())
    }

    @Test
    fun `updateContactPreferences throws ContactNotFound for an unknown id`() = runTest {
        try {
            service.updateContactPreferences(UUID.randomUUID(), isBlocked = true)
            fail("expected ContactServiceError.ContactNotFound")
        } catch (error: ContactServiceError.ContactNotFound) {
            // expected
        }
    }

    @Test
    fun `updateContactOCVSettings persists a preset`() = runTest {
        val contact = meshContact()
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id

        service.updateContactOCVSettings(contactID, preset = "liFePO4", customArray = null)

        val updated = service.getContactById(contactID)!!
        assertEquals("liFePO4", updated.ocvPreset)
        assertNull(updated.customOCVArrayString)
    }

    @Test
    fun `updateContactOCVSettings persists a custom array`() = runTest {
        val contact = meshContact()
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id

        service.updateContactOCVSettings(contactID, preset = "custom", customArray = "4240,4112,4029")

        val updated = service.getContactById(contactID)!!
        assertEquals("custom", updated.ocvPreset)
        assertEquals("4240,4112,4029", updated.customOCVArrayString)
    }

    @Test
    fun `updateContactOCVSettings can clear a stale custom array when switching back to a preset`() = runTest {
        val contact = meshContact()
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id
        service.updateContactOCVSettings(contactID, preset = "custom", customArray = "1,2,3")

        service.updateContactOCVSettings(contactID, preset = "liIon", customArray = null)

        val updated = service.getContactById(contactID)!!
        assertEquals("liIon", updated.ocvPreset)
        assertNull(updated.customOCVArrayString)
    }

    @Test
    fun `updateContactOCVSettings throws ContactNotFound for an unknown id`() = runTest {
        try {
            service.updateContactOCVSettings(UUID.randomUUID(), preset = "liIon", customArray = null)
            fail("expected ContactServiceError.ContactNotFound")
        } catch (error: ContactServiceError.ContactNotFound) {
            // expected
        }
    }

    @Test
    fun `blockChannelSender records the block, deletes that sender's channel messages, and blocks matching contacts`() = runTest {
        val contact = meshContact(name = "Troll")
        service.addOrUpdateContact(radioID, contact)
        val contactID = service.getContact(radioID, contact.publicKey)!!.id
        messageStore.saveMessage(directMessage(contactID = null).copy(channelIndex = 2u, senderNodeName = "Troll"))

        service.blockChannelSender(radioID, "Troll", contactIDs = setOf(contactID))

        assertEquals(1, service.getBlockedChannelSenders(radioID).size)
        assertTrue(messageStore.fetchMessages(radioID, 2u).isEmpty())
        assertTrue(service.getContactById(contactID)!!.isBlocked)
    }

    @Test
    fun `unblockChannelSender removes the block`() = runTest {
        service.blockChannelSender(radioID, "Troll")

        service.unblockChannelSender(radioID, "Troll")

        assertTrue(service.getBlockedChannelSenders(radioID).isEmpty())
    }

    @Test
    fun `parseContactURI round-trips a URI built by exportContactURI`() {
        val publicKey = ByteArray(32) { it.toByte() }

        val uri = ContactService.exportContactURI("Alice", publicKey, ContactType.REPEATER)
        val result = ContactService.parseContactURI(uri)

        assertEquals("Alice", result?.name)
        assertTrue(publicKey.contentEquals(result?.publicKey ?: ByteArray(0)))
        assertEquals(ContactType.REPEATER, result?.contactType)
    }

    @Test
    fun `parseContactURI defaults to CHAT for a missing or unrecognized type`() {
        val publicKey = ByteArray(32) { it.toByte() }
        val hex = publicKey.hexString.uppercase()

        assertEquals(ContactType.CHAT, ContactService.parseContactURI("meshcore://contact/add?name=Bob&public_key=$hex")?.contactType)
        assertEquals(ContactType.CHAT, ContactService.parseContactURI("meshcore://contact/add?name=Bob&public_key=$hex&type=99")?.contactType)
    }

    @Test
    fun `parseContactURI rejects the wrong scheme, host, or path`() {
        val hex = ByteArray(32).hexString
        assertNull(ContactService.parseContactURI("https://contact/add?name=Bob&public_key=$hex"))
        assertNull(ContactService.parseContactURI("meshcore://channel/add?name=Bob&public_key=$hex"))
        assertNull(ContactService.parseContactURI("meshcore://contact/join?name=Bob&public_key=$hex"))
    }

    @Test
    fun `parseContactURI rejects a missing name or malformed public key`() {
        val hex = ByteArray(32).hexString
        assertNull(ContactService.parseContactURI("meshcore://contact/add?public_key=$hex"))
        assertNull(ContactService.parseContactURI("meshcore://contact/add?name=Bob"))
        assertNull(ContactService.parseContactURI("meshcore://contact/add?name=Bob&public_key=not-hex"))
        assertNull(ContactService.parseContactURI("meshcore://contact/add?name=Bob&public_key=AB")) // too short
    }

    @Test
    fun `parseContactShareToken round-trips a token built by formatContactShareToken`() {
        val publicKey = ByteArray(32) { it.toByte() }

        val token = ContactService.formatContactShareToken("Alice", publicKey, ContactType.ROOM)
        val result = ContactService.parseContactShareToken(token)

        assertEquals("Alice", result?.name)
        assertTrue(publicKey.contentEquals(result?.publicKey ?: ByteArray(0)))
        assertEquals(ContactType.ROOM, result?.contactType)
    }

    @Test
    fun `parseContactShareToken finds a token embedded in surrounding message text`() {
        val publicKey = ByteArray(32) { it.toByte() }
        val token = ContactService.formatContactShareToken("Bob", publicKey, ContactType.CHAT)

        val result = ContactService.parseContactShareToken("check out $token please")

        assertEquals("Bob", result?.name)
    }

    @Test
    fun `formatContactShareToken strips a reserved terminator character from the name`() {
        val publicKey = ByteArray(32) { it.toByte() }
        val token = ContactService.formatContactShareToken("Weird>Name", publicKey, ContactType.CHAT)

        assertEquals("WeirdName", ContactService.parseContactShareToken(token)?.name)
    }

    @Test
    fun `parseContactShareToken rejects a malformed public key, unrecognized type, or empty name`() {
        val hex = ByteArray(32).hexString.uppercase()
        assertNull(ContactService.parseContactShareToken("<not-hex:1:Bob>"))
        assertNull(ContactService.parseContactShareToken("<$hex:99:Bob>"))
        assertNull(ContactService.parseContactShareToken("<$hex:1:>"))
        assertNull(ContactService.parseContactShareToken("plain text with no token"))
    }

    private fun directMessage(contactID: UUID?) = com.meshcoretwo.services.persistence.MessageDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        contactID = contactID,
        channelIndex = null,
        text = "hi",
        timestamp = 0u,
        createdAt = Instant.now(),
        sortDate = Instant.now(),
        direction = com.meshcoretwo.services.persistence.MessageDirection.INCOMING,
        status = com.meshcoretwo.services.persistence.MessageStatus.DELIVERED,
        textType = com.meshcoretwo.protocol.TextType.PLAIN_TEXT,
        ackCode = null,
        pathLength = 0u,
        snr = null,
        pathNodes = null,
        senderKeyPrefix = null,
        senderNodeName = null,
        isRead = false,
        replyToID = null,
        roundTripTime = null,
        sendCount = 1,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        deduplicationKey = null,
        reactionSummary = null,
        senderTimestamp = null,
        routeType = null,
        heardRepeats = 0,
    )
}

/** Hand-written [ContactSessionOps] test double — see [ContactServiceTest]'s class doc. */
private class FakeContactSessionOps : ContactSessionOps {
    var contactsToReturn = ContactFetchResult(contacts = emptyList(), reportedTotal = null)
    var getContactsError: MeshCoreError? = null
    var addContactError: MeshCoreError? = null
    var removeContactError: MeshCoreError? = null
    var resetPathError: MeshCoreError? = null

    val addedContacts = mutableListOf<MeshContact>()
    val removedPublicKeys = mutableListOf<ByteArray>()
    val resetPathPublicKeys = mutableListOf<ByteArray>()

    override suspend fun getContacts(since: Instant?): List<MeshContact> = contactsToReturn.contacts

    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult {
        getContactsError?.let { throw it }
        return contactsToReturn
    }

    override suspend fun getContact(publicKey: ByteArray): MeshContact? =
        contactsToReturn.contacts.find { it.publicKey.contentEquals(publicKey) }

    override suspend fun addContact(contact: MeshContact) {
        addContactError?.let { throw it }
        addedContacts.add(contact)
    }

    override suspend fun removeContact(publicKey: ByteArray) {
        removeContactError?.let { throw it }
        removedPublicKeys.add(publicKey)
    }

    override suspend fun resetPath(publicKey: ByteArray) {
        resetPathError?.let { throw it }
        resetPathPublicKeys.add(publicKey)
    }

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo =
        error("not used by this vertical slice")

    override suspend fun shareContact(publicKey: ByteArray) {
        error("not used by this vertical slice")
    }

    override suspend fun exportContact(publicKey: ByteArray?): String =
        error("not used by this vertical slice")

    override suspend fun importContact(cardData: ByteArray) {
        error("not used by this vertical slice")
    }

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) {
        error("not used by this vertical slice")
    }
}
