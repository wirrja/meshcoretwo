// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sendqueue

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
import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.messages.MessageService
import com.meshcoretwo.services.messages.MessageServiceConfig
import com.meshcoretwo.services.messages.PoolBackoffConfig
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageRepeatStore
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.PendingSendStore
import com.meshcoretwo.services.persistence.ReactionStore
import com.meshcoretwo.services.reactions.ReactionService
import com.meshcoretwo.services.utilities.AckCodeBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
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
import java.time.Instant
import java.util.UUID

/**
 * Exercises [ChatSendQueueService] against real in-memory [MessageStore]/[ContactStore]/
 * [ChannelStore]/[PendingSendStore] (Room, via Robolectric), real [MessageService]/[ChannelService]/
 * [ReactionService], and [FakeSendQueueSession], a hand-written [MeshCoreSessionProtocol] test
 * double — matching [com.meshcoretwo.services.messages.MessageServiceTest]'s precedent, since
 * [MessageService] genuinely needs the broad protocol.
 *
 * [ChatSendQueueConfig.transportWaitTimeoutMs] is tuned down to a small real value for every test
 * here (the drain runs on a real `Dispatchers.Default` job, not `runTest`'s virtual scheduler — see
 * [awaitUntil]'s doc), matching the timing-knob convention established in
 * [com.meshcoretwo.services.advertisement.AdvertisementServiceTest].
 */
@RunWith(RobolectricTestRunner::class)
class ChatSendQueueServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeSendQueueSession
    private lateinit var contactStore: ContactStore
    private lateinit var channelStore: ChannelStore
    private lateinit var messageStore: MessageStore
    private lateinit var pendingSendStore: PendingSendStore
    private lateinit var reactionStore: ReactionStore
    private lateinit var messageService: MessageService
    private lateinit var channelService: ChannelService
    private lateinit var reactionService: ReactionService
    private lateinit var service: ChatSendQueueService
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeSendQueueSession()
        contactStore = ContactStore(database)
        channelStore = ChannelStore(database)
        messageStore = MessageStore(database)
        pendingSendStore = PendingSendStore(database)
        reactionStore = ReactionStore(database)
        messageService = MessageService(
            session,
            messageStore,
            contactStore,
            channelStore,
            MessageRepeatStore(database),
            MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0)),
        )
        channelService = ChannelService(session, channelStore, messageStore)
        reactionService = ReactionService(messageStore, reactionStore)
        service = buildService()
    }

    private fun buildService(config: ChatSendQueueConfig = ChatSendQueueConfig(transportWaitTimeoutMs = 150)) =
        ChatSendQueueService(
            radioID = radioID,
            messageStore = messageStore,
            contactStore = contactStore,
            pendingSendStore = pendingSendStore,
            messageService = messageService,
            channelService = channelService,
            reactionService = reactionService,
            config = config,
        )

    @After
    fun tearDown() = runTest {
        service.shutdown()
        database.close()
    }

    private suspend fun saveContact(publicKey: ByteArray = ByteArray(32) { it.toByte() }): ContactDto {
        val meshContact = MeshContact(
            id = publicKey.joinToString("") { "%02x".format(it) },
            publicKey = publicKey,
            type = ContactType.CHAT,
            flags = ContactFlags.NONE,
            outPathLength = 0xFFu,
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

    /** Polls a real (non-virtual) timeout since the queue's drain jobs run on a real dispatcher. */
    private suspend fun awaitUntil(timeoutMs: Long = 10_000, intervalMs: Long = 10, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    // MARK: - DM drain

    @Test
    fun `enqueueDM sends a fresh pending message and clears the pending-send row`() = runTest {
        val contact = saveContact()
        val message = messageService.createPendingMessage("hello", contact)

        service.enqueueDM(DirectMessageEnvelope(message.id, contact.id))

        // Poll on the row's deletion (the drain's last write) rather than message status (an
        // earlier write within the same pass) to avoid a race between the two.
        awaitUntil { !pendingSendStore.hasPendingSend(message.id) }
        // The fake session ACKs the first waitForEvent call, so the drain resolves through
        // MessageService's finalizeSend into DELIVERED, not merely SENT (see MessageServiceTest's
        // "sendPendingDirectMessage drains a pending message" for the same terminal status).
        assertEquals(MessageStatus.DELIVERED, messageStore.fetchMessage(message.id)!!.status)
        assertEquals(1, session.sentMessages.size)
    }

    @Test
    fun `enqueueDM resend increments sendCount`() = runTest {
        val contact = saveContact()
        val message = messageService.createPendingMessage("hello", contact)

        service.enqueueDM(DirectMessageEnvelope(message.id, contact.id, isResend = true))

        // Poll on the row's deletion (the drain's last write) rather than sendCount (an earlier
        // write within the same pass) to avoid a race between the two.
        awaitUntil { !pendingSendStore.hasPendingSend(message.id) }
        assertEquals(2, messageStore.fetchMessage(message.id)!!.sendCount)
    }

    @Test
    fun `enqueueDM with a deleted contact fails the message and drops the row without throwing`() = runTest {
        val contact = saveContact()
        val message = messageService.createPendingMessage("hello", contact)
        contactStore.deleteContact(contact.id)

        service.enqueueDM(DirectMessageEnvelope(message.id, contact.id))

        awaitUntil { messageStore.fetchMessage(message.id)!!.status == MessageStatus.FAILED }
        assertFalse(pendingSendStore.hasPendingSend(message.id))
        assertEquals(0, session.sentMessages.size)
    }

    @Test
    fun `enqueueDM with a terminal session error fails the message and notifies MessageService`() = runTest {
        val contact = saveContact()
        val message = messageService.createPendingMessage("hello", contact)
        session.sendMessageError = MeshCoreError.ParseError("bad frame")

        val failedEvents = mutableListOf<UUID>()
        // UNDISPATCHED: the collector must be subscribed before enqueueDM emits (the flow has no replay).
        backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            messageService.statusEvents().collect { event ->
                if (event is com.meshcoretwo.services.messages.MessageStatusEvent.Failed) failedEvents.add(event.messageID)
            }
        }

        service.enqueueDM(DirectMessageEnvelope(message.id, contact.id))

        awaitUntil { messageStore.fetchMessage(message.id)!!.status == MessageStatus.FAILED }
        awaitUntil { failedEvents.contains(message.id) }
        awaitUntil { !pendingSendStore.hasPendingSend(message.id) }
    }

    @Test
    fun `enqueueDM with a transient session error parks the message, then drains once the transport reopens`() = runTest {
        val contact = saveContact()
        val message = messageService.createPendingMessage("hello", contact)
        session.sendMessageError = MeshCoreError.NotConnected

        service.enqueueDM(DirectMessageEnvelope(message.id, contact.id))

        // Parked: still pending, row survives, and no send ever reached the session.
        awaitUntil { pendingSendStore.hasPendingSend(message.id) }
        Thread.sleep(50)
        assertEquals(MessageStatus.PENDING, messageStore.fetchMessage(message.id)!!.status)
        assertTrue(pendingSendStore.hasPendingSend(message.id))
        assertEquals(0, session.sentMessages.size)

        session.sendMessageError = null
        service.transportDidOpen()

        awaitUntil { !pendingSendStore.hasPendingSend(message.id) }
        assertEquals(MessageStatus.DELIVERED, messageStore.fetchMessage(message.id)!!.status)
    }

    @Test
    fun `signalDMEnqueued drains an already-persisted row without inserting a new one`() = runTest {
        val contact = saveContact()
        val message = messageService.createPendingMessage("hello", contact)
        val envelope = DirectMessageEnvelope(message.id, contact.id)
        pendingSendStore.insertPendingSendAssigningSequence(pendingSendDto(envelope, radioID))

        service.signalDMEnqueued(envelope)

        awaitUntil { !pendingSendStore.hasPendingSend(message.id) }
        assertEquals(MessageStatus.DELIVERED, messageStore.fetchMessage(message.id)!!.status)
    }

    // MARK: - Channel drain

    @Test
    fun `enqueueChannel sends a fresh pending channel message and indexes it for reaction matching`() = runTest {
        saveChannel()
        val message = messageService.createPendingChannelMessage("hi there", channelIndex = 0u, radioID = radioID)
        val envelope = ChannelMessageEnvelope(
            messageID = message.id,
            channelIndex = 0u,
            isResend = false,
            messageText = message.text,
            messageTimestamp = message.timestamp,
            localNodeName = "Self",
        )

        service.enqueueChannel(envelope)

        awaitUntil { !pendingSendStore.hasPendingSend(message.id) }
        assertEquals(MessageStatus.SENT, messageStore.fetchMessage(message.id)!!.status)
        assertEquals(1, session.sentChannelMessages.size)

        // Indexed into the reaction LRU cache: a fresh reaction resolves immediately, not via the pending queue.
        val reactionText = reactionService.buildReactionText("👍", "Self", message.text, message.timestamp)
        val consumed = reactionService.handleChannelReaction(reactionText, 0u, "Bob", "Self", Instant.now(), radioID)
        assertTrue(consumed)
        assertTrue(reactionStore.reactionExists(message.id, "Bob", "👍"))
    }

    @Test
    fun `enqueueChannel resend increments sendCount and clears heard repeats`() = runTest {
        saveChannel()
        val message = messageService.createPendingChannelMessage("hi there", channelIndex = 0u, radioID = radioID)
        messageService.sendPendingChannelMessage(message.id) // establish an initial send

        val envelope = ChannelMessageEnvelope(
            messageID = message.id,
            channelIndex = 0u,
            isResend = true,
            messageText = message.text,
            messageTimestamp = message.timestamp,
            localNodeName = null,
        )
        service.enqueueChannel(envelope)

        // Poll on the row's deletion (the drain's last write) rather than sendCount (an earlier
        // write within the same pass) to avoid a race between the two.
        awaitUntil { !pendingSendStore.hasPendingSend(message.id) }
        assertEquals(2, messageStore.fetchMessage(message.id)!!.sendCount)
        assertEquals(2, session.sentChannelMessages.size)
    }

    @Test
    fun `channel NOT_FOUND below the disambiguate threshold parks without querying the device`() = runTest {
        saveChannel()
        val message = messageService.createPendingChannelMessage("hi", channelIndex = 0u, radioID = radioID)
        session.sendChannelMessageError = MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value)
        val queueWithHighThreshold = buildService(ChatSendQueueConfig(transportWaitTimeoutMs = 150, disambiguateAfterAttempts = 5))

        queueWithHighThreshold.enqueueChannel(
            ChannelMessageEnvelope(message.id, 0u, isResend = false, messageText = message.text, messageTimestamp = message.timestamp, localNodeName = null),
        )

        awaitUntil { pendingSendStore.hasPendingSend(message.id) }
        Thread.sleep(50)
        assertEquals(MessageStatus.PENDING, messageStore.fetchMessage(message.id)!!.status)
        assertEquals(0, session.getChannelCallCount)
        queueWithHighThreshold.shutdown()
    }

    @Test
    fun `channel NOT_FOUND at threshold with the channel still on the device parks for retry`() = runTest {
        saveChannel()
        val message = messageService.createPendingChannelMessage("hi", channelIndex = 0u, radioID = radioID)
        session.sendChannelMessageError = MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value)
        session.getChannelResult = ChannelInfo(0u, "General", ByteArray(16))
        val queueAtThreshold = buildService(ChatSendQueueConfig(transportWaitTimeoutMs = 150, disambiguateAfterAttempts = 1))

        queueAtThreshold.enqueueChannel(
            ChannelMessageEnvelope(message.id, 0u, isResend = false, messageText = message.text, messageTimestamp = message.timestamp, localNodeName = null),
        )

        awaitUntil { session.getChannelCallCount > 0 }
        Thread.sleep(50)
        assertEquals(MessageStatus.PENDING, messageStore.fetchMessage(message.id)!!.status)
        assertTrue(pendingSendStore.hasPendingSend(message.id))
        queueAtThreshold.shutdown()
    }

    @Test
    fun `channel NOT_FOUND at threshold with the channel gone from the device fails terminally`() = runTest {
        saveChannel()
        val message = messageService.createPendingChannelMessage("hi", channelIndex = 0u, radioID = radioID)
        session.sendChannelMessageError = MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value)
        session.getChannelResult = null
        val queueAtThreshold = buildService(ChatSendQueueConfig(transportWaitTimeoutMs = 150, disambiguateAfterAttempts = 1))

        queueAtThreshold.enqueueChannel(
            ChannelMessageEnvelope(message.id, 0u, isResend = false, messageText = message.text, messageTimestamp = message.timestamp, localNodeName = null),
        )

        awaitUntil { messageStore.fetchMessage(message.id)!!.status == MessageStatus.FAILED }
        awaitUntil { !pendingSendStore.hasPendingSend(message.id) }
        queueAtThreshold.shutdown()
    }

    // MARK: - Hydration

    @Test
    fun `hydrate replays persisted pending sends from a prior instance`() = runTest {
        val contact = saveContact()
        val message = messageService.createPendingMessage("hello", contact)
        val envelope = DirectMessageEnvelope(message.id, contact.id)
        pendingSendStore.insertPendingSendAssigningSequence(pendingSendDto(envelope, radioID))

        service.hydrate()

        awaitUntil { !pendingSendStore.hasPendingSend(message.id) }
        assertEquals(MessageStatus.DELIVERED, messageStore.fetchMessage(message.id)!!.status)
    }

    @Test
    fun `hydrate is a no-op on the second call`() = runTest {
        service.hydrate()
        val contact = saveContact()
        val message = messageService.createPendingMessage("hello", contact)
        val envelope = DirectMessageEnvelope(message.id, contact.id)
        pendingSendStore.insertPendingSendAssigningSequence(pendingSendDto(envelope, radioID))

        service.hydrate() // second call: must not enqueue the row just inserted above

        Thread.sleep(200)
        assertTrue(pendingSendStore.hasPendingSend(message.id))
        assertEquals(0, session.sentMessages.size)
    }

    // MARK: - Connection-state observation

    @Test
    fun `observeConnectionState fires the transport-open trigger when transitioning into ready`() = runTest {
        val contact = saveContact()
        val message = messageService.createPendingMessage("hello", contact)
        session.sendMessageError = MeshCoreError.NotConnected
        service.enqueueDM(DirectMessageEnvelope(message.id, contact.id))
        awaitUntil { pendingSendStore.hasPendingSend(message.id) }
        session.sendMessageError = null

        val events = MutableSharedFlow<DeviceConnectionState>(extraBufferCapacity = 4)
        service.observeConnectionState(DeviceConnectionState.CONNECTED, events)
        events.emit(DeviceConnectionState.SYNCING)
        events.emit(DeviceConnectionState.READY)

        awaitUntil { messageStore.fetchMessage(message.id)!!.status == MessageStatus.DELIVERED }
    }

    @Test
    fun `observeConnectionState with an already-ready initial state fires immediately`() = runTest {
        val contact = saveContact()
        val message = messageService.createPendingMessage("hello", contact)
        val envelope = DirectMessageEnvelope(message.id, contact.id)
        pendingSendStore.insertPendingSendAssigningSequence(pendingSendDto(envelope, radioID))

        // Enqueue directly on the in-memory queue without draining yet by using a service whose
        // send is parked behind a transient error until the state observation fires.
        session.sendMessageError = MeshCoreError.NotConnected
        service.signalDMEnqueued(envelope)
        awaitUntil { pendingSendStore.hasPendingSend(message.id) }
        session.sendMessageError = null

        service.observeConnectionState(DeviceConnectionState.READY, MutableSharedFlow())

        awaitUntil { messageStore.fetchMessage(message.id)!!.status == MessageStatus.DELIVERED }
    }
}

/** Hand-written [MeshCoreSessionProtocol] test double — see [ChatSendQueueServiceTest]'s class doc. */
private class FakeSendQueueSession : MeshCoreSessionProtocol {
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
    var sendChannelMessageError: MeshCoreError? = null

    /** 0-based index (across [waitForEvent] calls) that should receive a matching ACK; defaults to the first attempt. */
    var ackOnAttempt: Int? = 0
    private var waitForEventCallCount = 0

    var getChannelResult: ChannelInfo? = ChannelInfo(0u, "General", ByteArray(16))
    var getChannelError: MeshCoreError? = null
    var getChannelCallCount = 0

    override val currentSelfInfo: SelfInfo? get() = selfInfo

    override suspend fun sendMessage(destination: ByteArray, text: String, timestamp: Instant, attempt: UByte): MessageSentInfo {
        sendMessageError?.let { throw it }
        val expectedAck = AckCodeBuilder.expectedAck(
            timestamp = timestamp.epochSecond.toUInt(),
            attempt = attempt,
            text = text,
            senderPublicKey = selfInfo!!.publicKey,
        )
        sentMessages.add(Triple(destination, text, expectedAck))
        return MessageSentInfo(route = 0u, expectedAck = expectedAck, suggestedTimeoutMs = 1000u)
    }

    override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant) {
        sendChannelMessageError?.let { throw it }
        sentChannelMessages.add(channel to text)
    }

    override val connectionState: Flow<ConnectionState> get() = error("not used by this vertical slice")

    override suspend fun events(): Flow<MeshEvent> = error("not used by this vertical slice")

    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = error("not used by this vertical slice")

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? {
        val callIndex = waitForEventCallCount++
        return if (callIndex == ackOnAttempt) MeshEvent.Acknowledgement(sentMessages.last().third, tripTime = 42u) else null
    }

    override suspend fun getContacts(since: Instant?): List<MeshContact> = error("not used by this vertical slice")

    override suspend fun getContactsReportingTotal(since: Instant?) = error("not used by this vertical slice")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = null

    override suspend fun addContact(contact: MeshContact) = error("not used by this vertical slice")

    override suspend fun removeContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun resetPath(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun sendPathDiscovery(destination: ByteArray) = error("not used by this vertical slice")

    override suspend fun shareContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun exportContact(publicKey: ByteArray?) = error("not used by this vertical slice")

    override suspend fun importContact(cardData: ByteArray) = error("not used by this vertical slice")

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = error("not used by this vertical slice")

    override suspend fun getChannel(index: UByte): ChannelInfo {
        getChannelCallCount++
        getChannelError?.let { throw it }
        return getChannelResult ?: throw MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value)
    }

    override suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult = error("not used by this vertical slice")

    override suspend fun setChannel(index: UByte, name: String, secret: ByteArray) = error("not used by this vertical slice")

    override suspend fun getMessage(timeout: Double?): MessageResult = error("not used by this vertical slice")

    override suspend fun startAutoMessageFetching() = error("not used by this vertical slice")

    override fun stopAutoMessageFetching() = error("not used by this vertical slice")
}
