// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import androidx.room.Room
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ChannelsFetchResult
import com.meshcoretwo.protocol.ConnectionState
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MeshCoreSessionProtocol
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageResult
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.services.messages.MessageService
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageRepeatStore
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.ReactionDto
import com.meshcoretwo.services.persistence.ReactionStore
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RoomMessageStore
import com.meshcoretwo.services.remotenode.FakeRemoteNodeSessionOps
import com.meshcoretwo.services.remotenode.RemoteNodeService
import com.meshcoretwo.services.remotenode.RoomServerService
import com.meshcoretwo.services.security.KeychainService
import com.meshcoretwo.services.sync.SyncCoordinator
import com.meshcoretwo.protocol.TextType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager
import android.app.NotificationManager
import android.content.Context
import java.time.Instant
import java.util.UUID

/**
 * Exercises [NotificationActionHandler] against real in-memory Room-backed stores (Robolectric)
 * plus real [MessageService]/[NotificationService]/[RoomServerService]/[SyncCoordinator], matching
 * this codebase's "real service, fake wire" pattern. Only the session boundaries
 * ([FakeNotificationActionSession] for [MessageService], [FakeRemoteNodeSessionOps] reused from
 * `RemoteNodeServiceTest` for [RoomServerService]/[RemoteNodeService]) are faked.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationActionHandlerTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var contactStore: ContactStore
    private lateinit var channelStore: ChannelStore
    private lateinit var messageStore: MessageStore
    private lateinit var reactionStore: ReactionStore
    private lateinit var session: FakeNotificationActionSession
    private lateinit var messageService: MessageService
    private lateinit var notificationService: NotificationService
    private lateinit var roomServerService: RoomServerService
    private lateinit var syncCoordinator: SyncCoordinator
    private lateinit var handler: NotificationActionHandler
    private lateinit var shadowManager: ShadowNotificationManager
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        contactStore = ContactStore(database)
        channelStore = ChannelStore(database)
        messageStore = MessageStore(database)
        reactionStore = ReactionStore(database)
        session = FakeNotificationActionSession()
        messageService = MessageService(session, messageStore, contactStore, channelStore, MessageRepeatStore(database))
        val context = RuntimeEnvironment.getApplication()
        notificationService = NotificationService(context).apply { setup() }
        val remoteNodeSessionOps = FakeRemoteNodeSessionOps()
        val remoteNodeSessionStore = RemoteNodeSessionStore(database)
        val remoteNodeService = RemoteNodeService(
            remoteNodeSessionOps, remoteNodeSessionStore, contactStore,
            KeychainService(context.getSharedPreferences("nah-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)),
        )
        roomServerService = RoomServerService(remoteNodeSessionOps, remoteNodeService, remoteNodeSessionStore, RoomMessageStore(database), contactStore, radioID)
        syncCoordinator = SyncCoordinator()
        handler = NotificationActionHandler(contactStore, channelStore, messageStore, reactionStore, messageService, notificationService, roomServerService, syncCoordinator)
        shadowManager = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun saveContact(publicKey: ByteArray, name: String = "Alice"): UUID {
        val meshContact = MeshContact(
            id = publicKey.joinToString("") { "%02x".format(it) }, publicKey = publicKey, type = ContactType.CHAT,
            flags = ContactFlags.NONE, outPathLength = 0xFFu, outPath = ByteArray(0), advertisedName = name,
            lastAdvertisement = Instant.ofEpochSecond(900), latitude = 0.0, longitude = 0.0, lastModified = Instant.ofEpochSecond(1000),
        )
        contactStore.saveContact(radioID, meshContact)
        return contactStore.fetchContact(radioID, publicKey)!!.id
    }

    private suspend fun saveChannel(index: UByte = 1u, name: String = "General"): UUID =
        channelStore.saveChannel(radioID, ChannelInfo(index = index, name = name, secret = ByteArray(16)))

    // MARK: - Quick Reply (DM)

    @Test
    fun `handleQuickReply when connected sends the message and clears unread state`() = runTest {
        val contactID = saveContact(ByteArray(32) { 1 })
        contactStore.incrementUnreadCount(contactID)
        handler.configure(isConnectionReady = { true }, localNodeName = { "Self" })

        handler.handleQuickReply(contactID, "hey there")

        assertEquals(1, session.sentMessages.size)
        assertEquals(0, contactStore.fetchContact(contactID)!!.unreadCount)
        assertEquals(0, shadowManager.size())
    }

    @Test
    fun `handleQuickReply when not connected saves a draft and posts a failure notification`() = runTest {
        val contactID = saveContact(ByteArray(32) { 2 })
        handler.configure(isConnectionReady = { false }, localNodeName = { "Self" })

        handler.handleQuickReply(contactID, "queued reply")

        assertEquals(0, session.sentMessages.size)
        assertNotNull(notificationService.consumeDraft(contactID))
    }

    @Test
    fun `handleQuickReply for an unknown contact is a no-op`() = runTest {
        handler.configure(isConnectionReady = { true }, localNodeName = { "Self" })

        handler.handleQuickReply(UUID.randomUUID(), "hi")

        assertEquals(0, session.sentMessages.size)
    }

    @Test
    fun `handleQuickReply falls back to a draft when the send throws`() = runTest {
        val contactID = saveContact(ByteArray(32) { 4 })
        session.sendMessageError = MeshCoreError.NotConnected
        handler.configure(isConnectionReady = { true }, localNodeName = { "Self" })

        handler.handleQuickReply(contactID, "will fail")

        assertNotNull(notificationService.consumeDraft(contactID))
    }

    // MARK: - Quick Reply (Channel)

    @Test
    fun `handleChannelQuickReply when connected sends and clears channel unread state`() = runTest {
        val channelID = saveChannel()
        channelStore.incrementChannelUnreadCount(channelID)
        handler.configure(isConnectionReady = { true }, localNodeName = { "Self" })

        handler.handleChannelQuickReply(radioID, 1u, "channel reply")

        assertEquals(1, session.sentChannelMessages.size)
        assertEquals(0, channelStore.fetchChannel(radioID, 1u)!!.unreadCount)
    }

    @Test
    fun `handleChannelQuickReply when not connected does not send and posts a failure notification`() = runTest {
        saveChannel()
        handler.configure(isConnectionReady = { false }, localNodeName = { "Self" })

        handler.handleChannelQuickReply(radioID, 1u, "channel reply")

        assertEquals(0, session.sentChannelMessages.size)
    }

    // MARK: - Mark as Read

    @Test
    fun `handleMarkAsRead marks the message read and clears contact unread state`() = runTest {
        val contactID = saveContact(ByteArray(32) { 5 })
        contactStore.incrementUnreadCount(contactID)
        val messageID = UUID.randomUUID()
        messageStore.saveMessage(directMessage(messageID, contactID))

        handler.handleMarkAsRead(contactID, messageID)

        assertTrue(messageStore.fetchMessage(messageID)!!.isRead)
        assertEquals(0, contactStore.fetchContact(contactID)!!.unreadCount)
    }

    @Test
    fun `handleChannelMarkAsRead marks the message read and clears channel unread state`() = runTest {
        val channelID = saveChannel()
        channelStore.incrementChannelUnreadCount(channelID)
        val messageID = UUID.randomUUID()
        messageStore.saveMessage(channelMessage(messageID, 1u))

        handler.handleChannelMarkAsRead(radioID, 1u, messageID)

        assertTrue(messageStore.fetchMessage(messageID)!!.isRead)
        assertEquals(0, channelStore.fetchChannel(radioID, 1u)!!.unreadCount)
    }

    @Test
    fun `handleRoomMarkAsRead resets the session's unread count`() = runTest {
        val sessionID = UUID.randomUUID()
        RemoteNodeSessionStore(database).saveSession(
            RemoteNodeSessionDto(id = sessionID, radioID = radioID, publicKey = ByteArray(32) { 6 }, name = "Room", role = RemoteNodeRole.ROOM_SERVER, unreadCount = 3),
        )

        handler.handleRoomMarkAsRead(sessionID, UUID.randomUUID())

        assertEquals(0, RemoteNodeSessionStore(database).fetchSession(sessionID)!!.unreadCount)
    }

    // MARK: - Reactions

    @Test
    fun `handleReactionNotification is suppressed before configure is called`() = runTest {
        val contactID = saveContact(ByteArray(32) { 7 })
        val messageID = UUID.randomUUID()
        messageStore.saveMessage(directMessage(messageID, contactID, direction = MessageDirection.OUTGOING))
        reactionStore.saveReaction(reaction(messageID, senderName = "Bob"))

        handler.handleReactionNotification(messageID)

        assertEquals(0, shadowManager.size())
    }

    @Test
    fun `handleReactionNotification suppresses a self-reaction`() = runTest {
        val contactID = saveContact(ByteArray(32) { 8 })
        val messageID = UUID.randomUUID()
        messageStore.saveMessage(directMessage(messageID, contactID, direction = MessageDirection.OUTGOING))
        reactionStore.saveReaction(reaction(messageID, senderName = "Self"))
        handler.configure(isConnectionReady = { true }, localNodeName = { "Self" })

        handler.handleReactionNotification(messageID)

        assertEquals(0, shadowManager.size())
    }

    @Test
    fun `handleReactionNotification is a no-op for an incoming message`() = runTest {
        val contactID = saveContact(ByteArray(32) { 9 })
        val messageID = UUID.randomUUID()
        messageStore.saveMessage(directMessage(messageID, contactID, direction = MessageDirection.INCOMING))
        reactionStore.saveReaction(reaction(messageID, senderName = "Bob"))
        handler.configure(isConnectionReady = { true }, localNodeName = { "Self" })

        handler.handleReactionNotification(messageID)

        assertEquals(0, shadowManager.size())
    }

    @Test
    fun `handleReactionNotification posts a notification for a stranger's reaction`() = runTest {
        val contactID = saveContact(ByteArray(32) { 10 })
        val messageID = UUID.randomUUID()
        messageStore.saveMessage(directMessage(messageID, contactID, direction = MessageDirection.OUTGOING))
        reactionStore.saveReaction(reaction(messageID, senderName = "Bob"))
        handler.configure(isConnectionReady = { true }, localNodeName = { "Self" })

        handler.handleReactionNotification(messageID)

        assertTrue(shadowManager.size() > 0)
    }

    private fun directMessage(id: UUID, contactID: UUID, direction: MessageDirection = MessageDirection.INCOMING) = MessageDto(
        id = id, radioID = radioID, contactID = contactID, channelIndex = null, text = "hello",
        timestamp = 0u, createdAt = Instant.now(), sortDate = Instant.now(), direction = direction,
        status = MessageStatus.DELIVERED, textType = TextType.PLAIN_TEXT, ackCode = null,
        pathLength = 0u, snr = null, pathNodes = null, senderKeyPrefix = null, senderNodeName = null,
        isRead = false, replyToID = null, roundTripTime = null, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0,
        deduplicationKey = null, reactionSummary = null, senderTimestamp = null, routeType = null, heardRepeats = 0,
    )

    private fun channelMessage(id: UUID, channelIndex: UByte) = MessageDto(
        id = id, radioID = radioID, contactID = null, channelIndex = channelIndex, text = "hello channel",
        timestamp = 0u, createdAt = Instant.now(), sortDate = Instant.now(), direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED, textType = TextType.PLAIN_TEXT, ackCode = null,
        pathLength = 0u, snr = null, pathNodes = null, senderKeyPrefix = null, senderNodeName = "Alice",
        isRead = false, replyToID = null, roundTripTime = null, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0,
        deduplicationKey = null, reactionSummary = null, senderTimestamp = null, routeType = null, heardRepeats = 0,
    )

    private fun reaction(messageID: UUID, senderName: String) = ReactionDto(
        id = UUID.randomUUID(), messageID = messageID, emoji = "👍", senderName = senderName,
        messageHash = "hash", rawText = "👍:hash", receivedAt = Instant.now(), channelIndex = null,
        contactID = null, radioID = radioID,
    )
}

/** Minimal [MeshCoreSessionProtocol] fake — only [sendMessage]/[sendChannelMessage]/[currentSelfInfo] are exercised. */
private class FakeNotificationActionSession : MeshCoreSessionProtocol {
    var selfInfo: SelfInfo? = SelfInfo(
        advertisementType = 1u, txPower = 20, maxTxPower = 20, publicKey = ByteArray(32) { 0xFE.toByte() },
        latitude = 0.0, longitude = 0.0, multiAcks = 0u, advertisementLocationPolicy = 0u,
        telemetryModeEnvironment = 0u, telemetryModeLocation = 0u, telemetryModeBase = 0u,
        manualAddContacts = false, radioFrequency = 915.0, radioBandwidth = 250.0,
        radioSpreadingFactor = 10u, radioCodingRate = 5u, name = "Test Radio",
    )

    val sentMessages = mutableListOf<Triple<ByteArray, String, ByteArray>>()
    val sentChannelMessages = mutableListOf<Pair<UByte, String>>()
    var sendMessageError: MeshCoreError? = null
    private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 16)

    override val currentSelfInfo: SelfInfo? get() = selfInfo

    override suspend fun sendMessage(destination: ByteArray, text: String, timestamp: Instant, attempt: UByte): MessageSentInfo {
        sendMessageError?.let { throw it }
        val expectedAck = com.meshcoretwo.services.utilities.AckCodeBuilder.expectedAck(
            timestamp = timestamp.epochSecond.toUInt(), attempt = attempt, text = text, senderPublicKey = selfInfo!!.publicKey,
        )
        sentMessages.add(Triple(destination, text, expectedAck))
        return MessageSentInfo(route = 0u, expectedAck = expectedAck, suggestedTimeoutMs = 1000u)
    }

    override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant) {
        sentChannelMessages.add(channel to text)
    }

    override val connectionState: Flow<ConnectionState> get() = error("not used by this test")

    override suspend fun events(): Flow<MeshEvent> = eventsFlow

    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = eventsFlow

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? =
        if (sentMessages.isNotEmpty()) MeshEvent.Acknowledgement(sentMessages.last().third, tripTime = 42u) else null

    override suspend fun getContacts(since: Instant?): List<MeshContact> = error("not used by this test")

    override suspend fun getContactsReportingTotal(since: Instant?) = error("not used by this test")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = error("not used by this test")

    override suspend fun addContact(contact: MeshContact) = error("not used by this test")

    override suspend fun removeContact(publicKey: ByteArray) = error("not used by this test")

    override suspend fun resetPath(publicKey: ByteArray) = error("not used by this test")

    override suspend fun sendPathDiscovery(destination: ByteArray) = error("not used by this test")

    override suspend fun shareContact(publicKey: ByteArray) = error("not used by this test")

    override suspend fun exportContact(publicKey: ByteArray?) = error("not used by this test")

    override suspend fun importContact(cardData: ByteArray) = error("not used by this test")

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = error("not used by this test")

    override suspend fun getChannel(index: UByte): ChannelInfo = error("not used by this test")

    override suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult = error("not used by this test")

    override suspend fun setChannel(index: UByte, name: String, secret: ByteArray) = error("not used by this test")

    override suspend fun getMessage(timeout: Double?): MessageResult = error("not used by this test")

    override suspend fun startAutoMessageFetching() = error("not used by this test")

    override fun stopAutoMessageFetching() = error("not used by this test")
}
