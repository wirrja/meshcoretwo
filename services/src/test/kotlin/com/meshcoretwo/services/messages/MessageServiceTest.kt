// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import androidx.room.Room
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ChannelsFetchResult
import com.meshcoretwo.protocol.ConnectionState
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.ErrorCode
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MeshCoreSessionProtocol
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageResult
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageRepeatDto
import com.meshcoretwo.services.persistence.MessageRepeatStore
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Exercises [MessageService] against real in-memory [MessageStore]/[ContactStore]/[ChannelStore]
 * (Room, via Robolectric) and [FakeMeshCoreSession], a hand-written test double for the full
 * [MeshCoreSessionProtocol] — [MessageService] genuinely needs the broad protocol (see its class
 * doc), unlike [com.meshcoretwo.services.contacts.ContactServiceTest]/
 * [com.meshcoretwo.services.channels.ChannelServiceTest]'s narrower fakes.
 */
@RunWith(RobolectricTestRunner::class)
class MessageServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeMeshCoreSession
    private lateinit var contactStore: ContactStore
    private lateinit var channelStore: ChannelStore
    private lateinit var service: MessageService
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeMeshCoreSession()
        contactStore = ContactStore(database)
        channelStore = ChannelStore(database)
        service = MessageService(session, MessageStore(database), contactStore, channelStore, MessageRepeatStore(database))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun saveContact(publicKey: ByteArray = ByteArray(32) { it.toByte() }, type: ContactType = ContactType.CHAT, outPathLength: UByte = 0xFFu): ContactDto {
        val meshContact = MeshContact(
            id = publicKey.joinToString("") { "%02x".format(it) },
            publicKey = publicKey,
            type = type,
            flags = ContactFlags.NONE,
            outPathLength = outPathLength,
            outPath = ByteArray(0),
            advertisedName = "Contact",
            lastAdvertisement = Instant.ofEpochSecond(900),
            latitude = 0.0,
            longitude = 0.0,
            lastModified = Instant.ofEpochSecond(1000),
        )
        val (id, _) = contactStore.saveContact(radioID, meshContact)
        return contactStore.fetchContact(id)!!
    }

    private suspend fun saveChannel(index: UByte = 0u): UUID =
        channelStore.saveChannel(radioID, ChannelInfo(index, "General", ByteArray(16)))

    @Test
    fun `sendDirectMessage rejects a repeater recipient`() = runTest {
        val repeater = saveContact(type = ContactType.REPEATER)
        try {
            service.sendDirectMessage("hi", repeater)
            fail("expected MessageServiceError.InvalidRecipient")
        } catch (error: MessageServiceError.InvalidRecipient) {
            // expected
        }
    }

    @Test
    fun `sendDirectMessage rejects text over the length limit`() = runTest {
        val contact = saveContact()
        try {
            service.sendDirectMessage("x".repeat(151), contact)
            fail("expected MessageServiceError.MessageTooLong")
        } catch (error: MessageServiceError.MessageTooLong) {
            // expected
        }
    }

    @Test
    fun `sendDirectMessage requires a connected session`() = runTest {
        val contact = saveContact()
        session.selfInfo = null
        try {
            service.sendDirectMessage("hi", contact)
            fail("expected MessageServiceError.NotConnected")
        } catch (error: MessageServiceError.NotConnected) {
            // expected
        }
    }

    @Test
    fun `sendDirectMessage sends, persists, and tracks a pending ACK`() = runTest {
        val contact = saveContact()

        val message = service.sendDirectMessage("hello", contact)

        assertEquals(MessageStatus.SENT, message.status)
        assertEquals(1, session.sentMessages.size)
        assertEquals(1, service.pendingAckCount())
    }

    @Test
    fun `sendDirectMessage wraps a session failure and marks the message failed`() = runTest {
        val contact = saveContact()
        session.sendMessageError = MeshCoreError.Timeout

        try {
            service.sendDirectMessage("hello", contact)
            fail("expected MessageServiceError.SessionError")
        } catch (error: MessageServiceError.SessionError) {
            assertEquals(MeshCoreError.Timeout, error.error)
        }
        // The message row was saved before the send attempt; it must end up failed, not stuck pending.
        assertEquals(0, service.pendingAckCount())
    }

    @Test
    fun `sendDirectMessage retries through a transient pool-full error`() = runTest {
        val contact = saveContact()
        session.sendMessageErrorsBeforeSuccess = 2
        session.sendMessageTransientCode = com.meshcoretwo.services.messages.FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL

        val message = service.sendDirectMessage("hello", contact)

        assertEquals(MessageStatus.SENT, message.status)
        assertEquals(3, session.sendMessageAttempts)
    }

    @Test
    fun `handleAcknowledgement marks the tracked message delivered`() = runTest {
        val contact = saveContact()
        val message = service.sendDirectMessage("hello", contact)
        val ackCode = session.sentMessages.last().third

        service.handleAcknowledgement(ackCode, tripTime = 250u)

        val stored = MessageStore(database).fetchMessage(message.id)
        assertEquals(MessageStatus.DELIVERED, stored?.status)
        assertEquals(250u, stored?.roundTripTime)
        assertEquals(0, service.pendingAckCount())
    }

    @Test
    fun `handleAcknowledgement with an unknown code is a no-op`() = runTest {
        service.handleAcknowledgement(ByteArray(4) { 0x11 }, tripTime = null)
        assertEquals(0, service.pendingAckCount())
    }

    @Test
    fun `sendChannelMessage rejects text over the length limit`() = runTest {
        saveChannel()
        try {
            service.sendChannelMessage("x".repeat(140), 0u, radioID)
            fail("expected MessageServiceError.MessageTooLong")
        } catch (error: MessageServiceError.MessageTooLong) {
            // expected
        }
    }

    @Test
    fun `sendChannelMessage sends and persists, updating the channel's last-message time`() = runTest {
        val channelId = saveChannel()

        val (messageID, _) = service.sendChannelMessage("hello channel", 0u, radioID)

        assertEquals(MessageStatus.SENT, MessageStore(database).fetchMessage(messageID)?.status)
        assertTrue(channelStore.fetchChannelById(channelId)!!.lastMessageDate != null)
        assertEquals(1, session.sentChannelMessages.size)
    }

    @Test
    fun `createPendingChannelMessage saves without sending`() = runTest {
        val message = service.createPendingChannelMessage("draft", 0u, radioID)

        assertEquals(MessageStatus.PENDING, message.status)
        assertTrue(session.sentChannelMessages.isEmpty())
    }

    @Test
    fun `sendPendingChannelMessage sends a previously-created pending message`() = runTest {
        saveChannel()
        val pending = service.createPendingChannelMessage("draft", 0u, radioID)

        service.sendPendingChannelMessage(pending.id)

        assertEquals(MessageStatus.SENT, MessageStore(database).fetchMessage(pending.id)?.status)
        assertEquals(1, session.sentChannelMessages.size)
    }

    @Test
    fun `resendChannelMessage retransmits, bumps sendCount, and clears heardRepeats and stale repeats`() = runTest {
        saveChannel()
        val messageStore = MessageStore(database)
        val messageRepeatStore = MessageRepeatStore(database)
        val (messageID, _) = service.sendChannelMessage("hello channel", 0u, radioID)
        messageStore.incrementMessageHeardRepeats(messageID)
        messageRepeatStore.saveMessageRepeat(
            com.meshcoretwo.services.persistence.MessageRepeatDto(
                id = UUID.randomUUID(), messageID = messageID, receivedAt = Instant.now(), pathNodes = ByteArray(0),
                pathLength = 0u, snr = null, rssi = null, rxLogEntryID = UUID.randomUUID(),
            ),
        )

        val newTimestamp = service.resendChannelMessage(messageID)

        val updated = messageStore.fetchMessage(messageID)!!
        assertEquals(2, session.sentChannelMessages.size)
        assertEquals(2, updated.sendCount)
        assertEquals(0, updated.heardRepeats)
        assertEquals(newTimestamp, updated.timestamp)
        assertTrue(messageRepeatStore.fetchMessageRepeats(messageID).isEmpty())
        assertEquals(MessageStatus.SENT, updated.status)
    }

    @Test
    fun `resendChannelMessage with preserveTimestamp keeps the original wire timestamp`() = runTest {
        saveChannel()
        val messageStore = MessageStore(database)
        val (messageID, originalTimestamp) = service.sendChannelMessage("hello channel", 0u, radioID)

        val newTimestamp = service.resendChannelMessage(messageID, preserveTimestamp = true)

        assertEquals(originalTimestamp, newTimestamp)
        assertEquals(originalTimestamp, messageStore.fetchMessage(messageID)!!.timestamp)
    }

    @Test
    fun `resendChannelMessage fails a message that doesn't exist`() = runTest {
        try {
            service.resendChannelMessage(UUID.randomUUID())
            fail("expected MessageServiceError.SendFailed")
        } catch (error: MessageServiceError.SendFailed) {
            // expected
        }
    }

    @Test
    fun `checkExpiredAcks fails an entry past its give-up deadline`() = runTest {
        // A near-zero give-up window and a near-zero device-suggested timeout together mean any
        // elapsed real time between send and check already exceeds max(window, entry.timeoutMs),
        // without needing to inject a fake clock into PendingAck.sentAt.
        session.suggestedTimeoutMs = 0u
        val fastFailService = MessageService(session, MessageStore(database), contactStore, channelStore, MessageRepeatStore(database), MessageServiceConfig(ackGiveUpWindowMs = 0))
        val contact = saveContact()
        val message = fastFailService.sendDirectMessage("hello", contact)

        // Guarantee at least 1ms of real elapsed time so Duration.between(...).toMillis() reads
        // as strictly positive against the ~0ms deadline — Instant.now() runs on the real wall
        // clock even inside runTest's virtual-time scheduler.
        Thread.sleep(5)

        fastFailService.checkExpiredAcks()

        assertEquals(MessageStatus.FAILED, MessageStore(database).fetchMessage(message.id)?.status)
        assertEquals(0, fastFailService.pendingAckCount())
    }

    @Test
    fun `failAllPendingMessages fails every undelivered entry`() = runTest {
        val contactA = saveContact(publicKey = ByteArray(32) { it.toByte() })
        val contactB = saveContact(publicKey = ByteArray(32) { (it + 10).toByte() })
        val messageA = service.sendDirectMessage("a", contactA)
        val messageB = service.sendDirectMessage("b", contactB)

        service.failAllPendingMessages()

        val store = MessageStore(database)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(messageA.id)?.status)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(messageB.id)?.status)
        assertEquals(0, service.pendingAckCount())
    }

    @Test
    fun `isAckExpiryCheckingActive reflects start and stop`() {
        assertFalse(service.isAckExpiryCheckingActive)
        service.startAckExpiryChecking()
        assertTrue(service.isAckExpiryCheckingActive)
        service.stopAckExpiryChecking()
        assertFalse(service.isAckExpiryCheckingActive)
    }

    // MARK: - sendMessageWithRetry

    @Test
    fun `sendMessageWithRetry delivers on the first attempt when the ACK arrives immediately`() = runTest {
        val contact = saveContact()
        session.ackOnAttempt = 0
        var createdCallbackMessage: com.meshcoretwo.services.persistence.MessageDto? = null

        val message = service.sendMessageWithRetry("hello", contact, onMessageCreated = { createdCallbackMessage = it })

        assertEquals(MessageStatus.DELIVERED, message.status)
        assertEquals(1, session.sentMessages.size)
        assertEquals(0, service.pendingAckCount())
        assertEquals(message.id, createdCallbackMessage?.id)
        assertEquals(MessageStatus.PENDING, createdCallbackMessage?.status) // called before the send loop runs
    }

    @Test
    fun `sendMessageWithRetry retries on ACK timeout and delivers on a later attempt`() = runTest {
        val contact = saveContact()
        session.ackOnAttempt = 2 // times out twice, delivers on the 3rd attempt
        val retryService = MessageService(session, MessageStore(database), contactStore, channelStore, MessageRepeatStore(database), MessageServiceConfig(maxAttempts = 5, floodAfter = 4))

        val message = retryService.sendMessageWithRetry("hello", contact)

        assertEquals(MessageStatus.DELIVERED, message.status)
        assertEquals(3, session.sentMessages.size)
        assertEquals(0, retryService.pendingAckCount())
    }

    @Test
    fun `sendMessageWithRetry switches to flood routing after floodAfter direct attempts`() = runTest {
        val contact = saveContact(outPathLength = 0x01u)
        session.ackOnAttempt = 1 // delivers on the attempt right after the flood switch
        session.getContactResult = MeshContact(
            id = contact.publicKey.joinToString("") { "%02x".format(it) }, publicKey = contact.publicKey, type = contact.type,
            flags = ContactFlags.NONE, outPathLength = 0xFFu, outPath = ByteArray(0), advertisedName = contact.name,
            lastAdvertisement = Instant.ofEpochSecond(900), latitude = 0.0, longitude = 0.0, lastModified = Instant.ofEpochSecond(1000),
        )
        val floodService = MessageService(session, MessageStore(database), contactStore, channelStore, MessageRepeatStore(database), MessageServiceConfig(maxAttempts = 5, floodAfter = 1, maxFloodAttempts = 1))
        val received = mutableListOf<MessageStatusEvent>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { floodService.statusEvents().collect { received.add(it) } }

        val message = floodService.sendMessageWithRetry("hello", contact)
        job.cancel()

        assertEquals(MessageStatus.DELIVERED, message.status)
        assertEquals(1, session.resetPathCalls.size)
        assertTrue(received.any { it is MessageStatusEvent.RoutingChanged && it.isFlood })
    }

    @Test
    fun `sendMessageWithRetry leaves the message SENT, not FAILED, once the retry budget is spent`() = runTest {
        val contact = saveContact()
        session.ackOnAttempt = null // never delivers
        val budgetService = MessageService(session, MessageStore(database), contactStore, channelStore, MessageRepeatStore(database), MessageServiceConfig(maxAttempts = 2, floodAfter = 5))

        val message = budgetService.sendMessageWithRetry("hello", contact)

        assertEquals(MessageStatus.SENT, message.status)
        assertEquals(2, session.sentMessages.size)
        // The tracking entry is deliberately left in place — checkExpiredAcks owns the eventual
        // give-up, so a late-but-legitimate ACK can still upgrade the row.
        assertEquals(1, budgetService.pendingAckCount())
    }

    @Test
    fun `sendMessageWithRetry rejects a repeater recipient before saving anything`() = runTest {
        val repeater = saveContact(type = ContactType.REPEATER)
        try {
            service.sendMessageWithRetry("hi", repeater)
            fail("expected MessageServiceError.InvalidRecipient")
        } catch (error: MessageServiceError.InvalidRecipient) {
            // expected
        }
        assertTrue(session.sentMessages.isEmpty())
    }

    // MARK: - createPendingMessage / sendPendingDirectMessage / resendDirectMessage

    @Test
    fun `createPendingMessage saves without sending and updates the contact's last-message time`() = runTest {
        val contact = saveContact()

        val pending = service.createPendingMessage("draft", contact)

        assertEquals(MessageStatus.PENDING, pending.status)
        assertTrue(session.sentMessages.isEmpty())
        // Compared at epoch-second resolution: Instant is stored as epoch-seconds (see
        // Converters), so the round-tripped lastMessageDate loses the sub-second precision
        // pending.createdAt (an in-memory Instant.now(), never round-tripped) still carries.
        assertEquals(pending.createdAt.epochSecond, contactStore.fetchContact(contact.id)?.lastMessageDate?.epochSecond)
    }

    @Test
    fun `sendPendingDirectMessage drains a pending message without bumping sendCount`() = runTest {
        val contact = saveContact()
        session.ackOnAttempt = 0
        val pending = service.createPendingMessage("draft", contact)

        val sent = service.sendPendingDirectMessage(pending.id, contact)

        assertEquals(MessageStatus.DELIVERED, sent.status)
        assertEquals(1, sent.sendCount)
    }

    @Test
    fun `resendDirectMessage bumps sendCount and broadcasts Resent only on a confirmed delivery`() = runTest {
        val contact = saveContact()
        session.ackOnAttempt = 0
        val original = service.sendDirectMessage("hello", contact)
        val received = mutableListOf<MessageStatusEvent>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { service.statusEvents().collect { received.add(it) } }

        val resent = service.resendDirectMessage(original.id, contact)
        job.cancel()

        assertEquals(2, resent.sendCount)
        assertTrue(received.any { it is MessageStatusEvent.Resent && it.messageID == original.id })
    }

    @Test
    fun `resendDirectMessage does not bump sendCount or broadcast Resent when the retry budget is spent`() = runTest {
        val contact = saveContact()
        session.ackOnAttempt = 0
        val original = service.sendDirectMessage("hello", contact)
        session.ackOnAttempt = null
        val budgetService = MessageService(session, MessageStore(database), contactStore, channelStore, MessageRepeatStore(database), MessageServiceConfig(maxAttempts = 1, floodAfter = 5))

        val resent = budgetService.resendDirectMessage(original.id, contact)

        assertEquals(1, resent.sendCount)
    }

    @Test
    fun `sendPendingDirectMessage preserves the original timestamp when preserveTimestamp is true`() = runTest {
        val contact = saveContact()
        session.ackOnAttempt = 0
        val pending = service.createPendingMessage("draft", contact)
        val originalTimestamp = pending.timestamp

        service.sendPendingDirectMessage(pending.id, contact, preserveTimestamp = true)

        assertEquals(originalTimestamp, MessageStore(database).fetchMessage(pending.id)?.timestamp)
    }

    @Test
    fun `sendPendingDirectMessage stamps a fresh timestamp when preserveTimestamp is false`() = runTest {
        val contact = saveContact()
        session.ackOnAttempt = 0
        val pending = service.createPendingMessage("draft", contact)
        Thread.sleep(1100) // ensure epoch-second resolution actually advances

        service.sendPendingDirectMessage(pending.id, contact, preserveTimestamp = false)

        assertTrue(MessageStore(database).fetchMessage(pending.id)!!.timestamp > pending.timestamp)
    }

    @Test
    fun `sendPendingDirectMessage rejects a concurrent drain of the same message`() = runTest {
        val contact = saveContact()
        val pending = service.createPendingMessage("draft", contact)
        val release = CompletableDeferred<MeshEvent?>()
        session.waitForEventOverride = { release.await() }
        val guardedService = MessageService(session, MessageStore(database), contactStore, channelStore, MessageRepeatStore(database), MessageServiceConfig(maxAttempts = 1, floodAfter = 5))

        val firstJob = CoroutineScope(Dispatchers.Unconfined).launch { guardedService.sendPendingDirectMessage(pending.id, contact) }

        // Unconfined dispatch already ran firstJob synchronously up to its first genuine
        // suspension point (release.await()), so the in-flight guard is already claimed.
        try {
            guardedService.sendPendingDirectMessage(pending.id, contact)
            fail("expected MessageServiceError.SendFailed")
        } catch (error: MessageServiceError.SendFailed) {
            // expected
        }

        release.complete(null)
        firstJob.join()
    }

    @Test
    fun `maxChannelMessageLength reserves room for the NodeName colon-space prefix`() {
        // 139 total - 8-byte node name - 2 bytes for ": " = 129.
        assertEquals(129, MessageService.maxChannelMessageLength(nodeNameByteCount = 8))
    }

    @Test
    fun `maxChannelMessageLength floors at zero for a node name longer than the total limit`() {
        assertEquals(0, MessageService.maxChannelMessageLength(nodeNameByteCount = 200))
    }

    // MARK: - deleteMessage

    @Test
    fun `deleteMessage removes the row and is a no-op for an unknown id`() = runTest {
        val contact = saveContact()
        val message = service.createPendingMessage("draft", contact)

        service.deleteMessage(message.id)

        assertNull(MessageStore(database).fetchMessage(message.id))
        service.deleteMessage(UUID.randomUUID()) // must not throw
    }

    @Test
    fun `deleteMessage clears the contact's last-message time when it was the only message`() = runTest {
        val contact = saveContact()
        val message = service.createPendingMessage("draft", contact)

        service.deleteMessage(message.id)

        assertNull(contactStore.fetchContact(contact.id)?.lastMessageDate)
    }

    @Test
    fun `deleteMessage recomputes the contact's last-message time from the remaining newest message`() = runTest {
        val contact = saveContact()
        val newer = service.createPendingMessage("newer", contact)
        val older = newer.copy(id = UUID.randomUUID(), text = "older", sortDate = newer.sortDate.minusSeconds(60), createdAt = newer.createdAt.minusSeconds(60))
        MessageStore(database).saveMessage(older)

        service.deleteMessage(newer.id)

        assertEquals(older.sortDate.epochSecond, contactStore.fetchContact(contact.id)?.lastMessageDate?.epochSecond)
    }

    @Test
    fun `deleteMessage recomputes the channel's last-message time from the remaining newest message`() = runTest {
        val channelId = saveChannel()
        val newer = service.createPendingChannelMessage("newer", 0u, radioID)
        val older = newer.copy(id = UUID.randomUUID(), text = "older", sortDate = newer.sortDate.minusSeconds(60), createdAt = newer.createdAt.minusSeconds(60))
        MessageStore(database).saveMessage(older)

        service.deleteMessage(newer.id)

        assertEquals(older.sortDate.epochSecond, channelStore.fetchChannelById(channelId)?.lastMessageDate?.epochSecond)
    }

    @Test
    fun `deleteMessage cascades the message's heard repeats`() = runTest {
        saveChannel()
        val message = service.createPendingChannelMessage("draft", 0u, radioID)
        val repeatStore = MessageRepeatStore(database)
        repeatStore.saveMessageRepeat(
            MessageRepeatDto(id = UUID.randomUUID(), messageID = message.id, receivedAt = Instant.now(), pathNodes = ByteArray(1), pathLength = 1u, snr = null, rssi = null, rxLogEntryID = null),
        )

        service.deleteMessage(message.id)

        assertTrue(repeatStore.fetchMessageRepeats(message.id).isEmpty())
    }
}

/** Hand-written [MeshCoreSessionProtocol] test double — see [MessageServiceTest]'s class doc. */
private class FakeMeshCoreSession : MeshCoreSessionProtocol {
    var selfInfo: SelfInfo? = SelfInfo(
        advertisementType = 1u,
        txPower = 20,
        maxTxPower = 20,
        publicKey = ByteArray(32) { 0xFE.toByte() },
        latitude = 0.0,
        longitude = 0.0,
        multiAcks = 0u,
        advertisementLocationPolicy = 0u,
        telemetryModeEnvironment = 0u,
        telemetryModeLocation = 0u,
        telemetryModeBase = 0u,
        manualAddContacts = false,
        radioFrequency = 915.0,
        radioBandwidth = 250.0,
        radioSpreadingFactor = 10u,
        radioCodingRate = 5u,
        name = "Test Radio",
    )

    /** (destination, text, expectedAck ByteArray). */
    val sentMessages = mutableListOf<Triple<ByteArray, String, ByteArray>>()
    val sentChannelMessages = mutableListOf<Pair<UByte, String>>()

    var sendMessageError: MeshCoreError? = null
    var sendMessageErrorsBeforeSuccess = 0
    var sendMessageTransientCode: UByte? = null
    var sendMessageAttempts = 0
    var suggestedTimeoutMs: UInt = 1000u

    /** 0-based index (across [waitForEvent] calls on this session instance) of the attempt that should receive a matching ACK; `null` means every attempt times out. */
    var ackOnAttempt: Int? = null

    /** Overrides [waitForEvent] entirely — for tests that need to suspend deterministically (e.g. concurrent-retry-guard tests) instead of the [ackOnAttempt] bookkeeping. */
    var waitForEventOverride: (suspend () -> MeshEvent?)? = null
    private var waitForEventCallCount = 0

    val resetPathCalls = mutableListOf<ByteArray>()
    var resetPathError: MeshCoreError? = null
    var getContactResult: MeshContact? = null

    private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 16)

    override val currentSelfInfo: SelfInfo? get() = selfInfo

    override suspend fun sendMessage(destination: ByteArray, text: String, timestamp: java.time.Instant, attempt: UByte): MessageSentInfo {
        sendMessageAttempts++
        if (sendMessageAttempts <= sendMessageErrorsBeforeSuccess) {
            throw MeshCoreError.DeviceError(sendMessageTransientCode ?: ErrorCode.TABLE_FULL.value)
        }
        sendMessageError?.let { throw it }
        val expectedAck = com.meshcoretwo.services.utilities.AckCodeBuilder.expectedAck(
            timestamp = timestamp.epochSecond.toUInt(),
            attempt = attempt,
            text = text,
            senderPublicKey = selfInfo!!.publicKey,
        )
        sentMessages.add(Triple(destination, text, expectedAck))
        return MessageSentInfo(route = 0u, expectedAck = expectedAck, suggestedTimeoutMs = suggestedTimeoutMs)
    }

    override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: java.time.Instant) {
        sentChannelMessages.add(channel to text)
    }

    override val connectionState: Flow<ConnectionState> get() = error("not used by this vertical slice")

    override suspend fun events(): Flow<MeshEvent> = eventsFlow

    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = eventsFlow.filter { filter.matches(it) }

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? {
        waitForEventOverride?.let { return it() }
        val callIndex = waitForEventCallCount++
        return if (callIndex == ackOnAttempt) MeshEvent.Acknowledgement(sentMessages.last().third, tripTime = 42u) else null
    }

    override suspend fun getContacts(since: java.time.Instant?): List<MeshContact> = error("not used by this vertical slice")

    override suspend fun getContactsReportingTotal(since: java.time.Instant?) = error("not used by this vertical slice")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = getContactResult

    override suspend fun addContact(contact: MeshContact) = error("not used by this vertical slice")

    override suspend fun removeContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun resetPath(publicKey: ByteArray) {
        resetPathCalls.add(publicKey)
        resetPathError?.let { throw it }
    }

    override suspend fun sendPathDiscovery(destination: ByteArray) = error("not used by this vertical slice")

    override suspend fun shareContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun exportContact(publicKey: ByteArray?) = error("not used by this vertical slice")

    override suspend fun importContact(cardData: ByteArray) = error("not used by this vertical slice")

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = error("not used by this vertical slice")

    override suspend fun getChannel(index: UByte): ChannelInfo = error("not used by this vertical slice")

    override suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult = error("not used by this vertical slice")

    override suspend fun setChannel(index: UByte, name: String, secret: ByteArray) = error("not used by this vertical slice")

    override suspend fun getMessage(timeout: Double?): MessageResult = error("not used by this vertical slice")

    override suspend fun startAutoMessageFetching() = error("not used by this vertical slice")

    override fun stopAutoMessageFetching() = error("not used by this vertical slice")
}
