// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.reactions

import androidx.room.Room
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ChannelsFetchResult
import com.meshcoretwo.protocol.ConnectionState
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreSessionProtocol
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageResult
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.services.messages.MessageService
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageRepeatStore
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.ReactionStore
import com.meshcoretwo.services.utilities.ReactionParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ReactionServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var messageStore: MessageStore
    private lateinit var reactionStore: ReactionStore
    private lateinit var contactStore: ContactStore
    private lateinit var channelStore: ChannelStore
    private lateinit var service: ReactionService
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        messageStore = MessageStore(database)
        reactionStore = ReactionStore(database)
        contactStore = ContactStore(database)
        channelStore = ChannelStore(database)
        service = ReactionService(messageStore, reactionStore)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun channelMessage(
        text: String,
        timestamp: UInt,
        senderNodeName: String? = "Alice",
        direction: MessageDirection = MessageDirection.INCOMING,
        channelIndex: UByte = 0u,
    ) = MessageDto(
        id = UUID.randomUUID(), radioID = radioID, contactID = null, channelIndex = channelIndex, text = text,
        timestamp = timestamp, createdAt = Instant.now(), sortDate = Instant.now(), direction = direction,
        status = MessageStatus.DELIVERED, textType = com.meshcoretwo.protocol.TextType.PLAIN_TEXT, ackCode = null,
        pathLength = 0u, snr = null, pathNodes = null, senderKeyPrefix = null, senderNodeName = senderNodeName,
        isRead = false, replyToID = null, roundTripTime = null, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0,
        deduplicationKey = null, reactionSummary = null, senderTimestamp = null, routeType = null, heardRepeats = 0,
    )

    private suspend fun saveContact(publicKey: ByteArray = ByteArray(32) { it.toByte() }): ContactDto {
        val meshContact = MeshContact(
            id = publicKey.joinToString("") { "%02x".format(it) }, publicKey = publicKey, type = ContactType.CHAT,
            flags = ContactFlags.NONE, outPathLength = 0xFFu, outPath = ByteArray(0), advertisedName = "Contact",
            lastAdvertisement = Instant.ofEpochSecond(900), latitude = 0.0, longitude = 0.0, lastModified = Instant.ofEpochSecond(1000),
        )
        val (id, _) = contactStore.saveContact(radioID, meshContact)
        return contactStore.fetchContact(id)!!
    }

    // MARK: - Channel Reactions

    @Test
    fun `handleChannelReaction returns false for non-reaction text`() = runTest {
        val consumed = service.handleChannelReaction("just a normal message", 0u, "Alice", "Self", Instant.now(), radioID)
        assertFalse(consumed)
    }

    @Test
    fun `handleChannelReaction matches via the LRU cache and persists a reaction`() = runTest {
        val target = channelMessage("hi there", timestamp = 1000u)
        messageStore.saveMessage(target)
        service.indexChannelMessage(target.id, 0u, "Alice", "hi there", 1000u)

        val reactionText = service.buildReactionText("👍", "Alice", "hi there", 1000u)
        val consumed = service.handleChannelReaction(reactionText, 0u, "Bob", "Self", Instant.now(), radioID)

        assertTrue(consumed)
        assertTrue(reactionStore.reactionExists(target.id, "Bob", "👍"))
        assertEquals("👍:1", messageStore.fetchMessage(target.id)!!.reactionSummary)
    }

    @Test
    fun `handleChannelReaction matches via DB fallback when not cached`() = runTest {
        val target = channelMessage("db lookup target", timestamp = Instant.now().epochSecond.toUInt())
        messageStore.saveMessage(target) // never indexed into the LRU cache

        val reactionText = service.buildReactionText("❤️", "Alice", "db lookup target", target.timestamp)
        val consumed = service.handleChannelReaction(reactionText, 0u, "Bob", "Self", Instant.now(), radioID)

        assertTrue(consumed)
        assertTrue(reactionStore.reactionExists(target.id, "Bob", "❤️"))
    }

    @Test
    fun `handleChannelReaction queues as pending when no target is found, then flushes on later indexing`() = runTest {
        val reactionText = service.buildReactionText("😂", "Alice", "not yet arrived", 5000u)
        val consumedBeforeIndex = service.handleChannelReaction(reactionText, 0u, "Bob", "Self", Instant.now(), radioID)
        assertTrue(consumedBeforeIndex)

        val target = channelMessage("not yet arrived", timestamp = 5000u)
        messageStore.saveMessage(target)
        service.indexChannelMessage(target.id, 0u, "Alice", "not yet arrived", 5000u)

        assertTrue(reactionStore.reactionExists(target.id, "Bob", "😂"))
    }

    @Test
    fun `handleChannelReaction to the user's own outgoing message matches via selfNodeName`() = runTest {
        // Uncached, so this exercises the DB-fallback tier, whose timestamp window is centered
        // on the real receive time — hence a realistic (not small literal) timestamp here.
        val now = Instant.now().epochSecond.toUInt()
        val target = channelMessage("my own message", timestamp = now, senderNodeName = null, direction = MessageDirection.OUTGOING)
        messageStore.saveMessage(target)

        val reactionText = service.buildReactionText("🔥", "Self", "my own message", now)
        val consumed = service.handleChannelReaction(reactionText, 0u, "Bob", "Self", Instant.now(), radioID)

        assertTrue(consumed)
        assertTrue(reactionStore.reactionExists(target.id, "Bob", "🔥"))
    }

    @Test
    fun `duplicate channel reactions from the same sender do not double-count`() = runTest {
        val target = channelMessage("hi", timestamp = 100u)
        messageStore.saveMessage(target)
        service.indexChannelMessage(target.id, 0u, "Alice", "hi", 100u)
        val reactionText = service.buildReactionText("👍", "Alice", "hi", 100u)

        service.handleChannelReaction(reactionText, 0u, "Bob", "Self", Instant.now(), radioID)
        service.handleChannelReaction(reactionText, 0u, "Bob", "Self", Instant.now(), radioID)

        assertEquals("👍:1", messageStore.fetchMessage(target.id)!!.reactionSummary)
    }

    // MARK: - DM Reactions

    @Test
    fun `handleDirectReaction matches via the LRU cache`() = runTest {
        val contact = saveContact()
        val target = channelMessage("dm text", timestamp = 300u, senderNodeName = null, channelIndex = 0u).copy(contactID = contact.id, channelIndex = null)
        messageStore.saveMessage(target)
        service.indexDirectMessage(target.id, contact.id, "dm text", 300u)

        val reactionText = service.buildDMReactionText("👍", "dm text", 300u)
        val consumed = service.handleDirectReaction(reactionText, contact, radioID)

        assertTrue(consumed)
        assertTrue(reactionStore.reactionExists(target.id, contact.displayName, "👍"))
    }

    @Test
    fun `handleDirectReaction queues as pending and flushes when the target is later indexed`() = runTest {
        val contact = saveContact()
        val reactionText = service.buildDMReactionText("❤️", "later dm", 400u)

        assertTrue(service.handleDirectReaction(reactionText, contact, radioID))

        val target = channelMessage("later dm", timestamp = 400u, senderNodeName = null, channelIndex = 0u).copy(contactID = contact.id, channelIndex = null)
        messageStore.saveMessage(target)
        service.indexDirectMessage(target.id, contact.id, "later dm", 400u)

        assertTrue(reactionStore.reactionExists(target.id, contact.displayName, "❤️"))
    }

    // MARK: - Lifecycle

    @Test
    fun `clearPendingReactions discards queued reactions so a later index does not flush them`() = runTest {
        val reactionText = service.buildReactionText("😂", "Alice", "gone", 700u)
        service.handleChannelReaction(reactionText, 0u, "Bob", "Self", Instant.now(), radioID)

        service.clearPendingReactions()

        val target = channelMessage("gone", timestamp = 700u)
        messageStore.saveMessage(target)
        service.indexChannelMessage(target.id, 0u, "Alice", "gone", 700u)

        assertFalse(reactionStore.reactionExists(target.id, "Bob", "😂"))
    }

    // MARK: - Events

    @Test
    fun `a persisted reaction emits ReactionReceived with the updated summary`() = runTest {
        val target = channelMessage("evented", timestamp = 800u)
        messageStore.saveMessage(target)
        service.indexChannelMessage(target.id, 0u, "Alice", "evented", 800u)
        val received = mutableListOf<ReactionEvent>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { service.events().collect { received.add(it) } }

        val reactionText = service.buildReactionText("👍", "Alice", "evented", 800u)
        service.handleChannelReaction(reactionText, 0u, "Bob", "Self", Instant.now(), radioID)
        job.cancel()

        assertEquals(1, received.size)
        val event = received.single() as ReactionEvent.ReactionReceived
        assertEquals(target.id, event.messageID)
        assertEquals("👍:1", event.summary)
    }

    // MARK: - Sending

    @Test
    fun `sendChannelReaction sends a wire-format broadcast and persists locally`() = runTest {
        val session = FakeReactionSession()
        val messageService = MessageService(session, messageStore, contactStore, channelStore, MessageRepeatStore(database))
        channelStore.saveChannel(radioID, ChannelInfo(0u, "General", ByteArray(16)))
        val target = channelMessage("react to me", timestamp = 900u)
        messageStore.saveMessage(target)

        val sent = service.sendChannelReaction(messageService, "👍", target, "Alice", 0u, radioID, "Self")

        assertTrue(sent)
        assertEquals(1, session.sentChannelMessages.size)
        assertEquals(service.buildReactionText("👍", "Alice", "react to me", target.reactionTimestamp), session.sentChannelMessages.single().second)
        assertTrue(reactionStore.reactionExists(target.id, "Self", "👍"))
    }

    @Test
    fun `sendChannelReaction is a no-op if localNodeName already reacted with that emoji`() = runTest {
        val session = FakeReactionSession()
        val messageService = MessageService(session, messageStore, contactStore, channelStore, MessageRepeatStore(database))
        channelStore.saveChannel(radioID, ChannelInfo(0u, "General", ByteArray(16)))
        val target = channelMessage("react to me", timestamp = 900u)
        messageStore.saveMessage(target)
        service.sendChannelReaction(messageService, "👍", target, "Alice", 0u, radioID, "Self")

        val sentAgain = service.sendChannelReaction(messageService, "👍", target, "Alice", 0u, radioID, "Self")

        assertFalse(sentAgain)
        assertEquals(1, session.sentChannelMessages.size)
    }

    @Test
    fun `sendDMReaction sends a wire-format DM and persists locally`() = runTest {
        val session = FakeReactionSession()
        val messageService = MessageService(session, messageStore, contactStore, channelStore, MessageRepeatStore(database))
        val contact = saveContact()
        val target = channelMessage("dm target", timestamp = 950u, senderNodeName = null, channelIndex = 0u).copy(contactID = contact.id, channelIndex = null)
        messageStore.saveMessage(target)

        val sent = service.sendDMReaction(messageService, "❤️", target, contact, "Self")

        assertTrue(sent)
        assertEquals(1, session.sentMessages.size)
        assertTrue(reactionStore.reactionExists(target.id, "Self", "❤️"))
    }
}

/** Minimal hand-written [MeshCoreSessionProtocol] fake — only send paths are functional. */
private class FakeReactionSession : MeshCoreSessionProtocol {
    val sentChannelMessages = mutableListOf<Pair<UByte, String>>()
    val sentMessages = mutableListOf<Pair<ByteArray, String>>()

    override val currentSelfInfo: SelfInfo? = SelfInfo(
        advertisementType = 1u, txPower = 20, maxTxPower = 20, publicKey = ByteArray(32) { 0xFE.toByte() },
        latitude = 0.0, longitude = 0.0, multiAcks = 0u, advertisementLocationPolicy = 0u,
        telemetryModeEnvironment = 0u, telemetryModeLocation = 0u, telemetryModeBase = 0u, manualAddContacts = false,
        radioFrequency = 915.0, radioBandwidth = 250.0, radioSpreadingFactor = 10u, radioCodingRate = 5u, name = "Self",
    )

    override suspend fun sendMessage(destination: ByteArray, text: String, timestamp: Instant, attempt: UByte): MessageSentInfo {
        sentMessages.add(destination to text)
        return MessageSentInfo(route = 0u, expectedAck = ByteArray(4), suggestedTimeoutMs = 1000u)
    }

    override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant) {
        sentChannelMessages.add(channel to text)
    }

    override val connectionState: Flow<ConnectionState> get() = error("not used by this test")
    override suspend fun events(): Flow<MeshEvent> = error("not used by this test")
    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = error("not used by this test")
    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = error("not used by this test")
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
    override suspend fun changeContactFlags(contact: MeshContact, flags: com.meshcoretwo.protocol.ContactFlags) = error("not used by this test")
    override suspend fun getChannel(index: UByte): ChannelInfo = error("not used by this test")
    override suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult = error("not used by this test")
    override suspend fun setChannel(index: UByte, name: String, secret: ByteArray) = error("not used by this test")
    override suspend fun getMessage(timeout: Double?): MessageResult = error("not used by this test")
    override suspend fun startAutoMessageFetching() = error("not used by this test")
    override fun stopAutoMessageFetching() = error("not used by this test")
}
