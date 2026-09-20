// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import androidx.room.Room
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ChannelMessage
import com.meshcoretwo.protocol.ChannelsFetchResult
import com.meshcoretwo.protocol.ConnectionState
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactMessage
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MeshContact
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
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Exercises [IncomingMessageService] against real in-memory [MessageStore]/[ContactStore]/
 * [ChannelStore] (Room, via Robolectric) and [FakeIncomingSession], a hand-written test double —
 * see [MessageServiceTest]'s class doc for why a full [MeshCoreSessionProtocol] fake (rather
 * than a narrow protocol interface) is the established pattern for message-layer tests.
 */
@RunWith(RobolectricTestRunner::class)
class IncomingMessageServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeIncomingSession
    private lateinit var contactStore: ContactStore
    private lateinit var channelStore: ChannelStore
    private lateinit var messageStore: MessageStore
    private lateinit var service: IncomingMessageService
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeIncomingSession()
        contactStore = ContactStore(database)
        channelStore = ChannelStore(database)
        messageStore = MessageStore(database)
        service = IncomingMessageService(session, messageStore, contactStore, channelStore)
    }

    @After
    fun tearDown() {
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

    private fun contactMessage(
        senderPrefix: ByteArray,
        text: String = "hello",
        textType: UByte = TextType.PLAIN_TEXT.value,
        timestamp: Instant = Instant.now(),
        signature: ByteArray? = null,
    ) = ContactMessage(
        senderPublicKeyPrefix = senderPrefix,
        pathLength = 3u,
        textType = textType,
        senderTimestamp = timestamp,
        signature = signature,
        text = text,
        snr = 5.5,
    )

    private fun channelMessage(
        channelIndex: UByte = 0u,
        text: String = "Alice: hi there",
        timestamp: Instant = Instant.now(),
    ) = ChannelMessage(
        channelIndex = channelIndex,
        pathLength = 2u,
        textType = TextType.PLAIN_TEXT.value,
        senderTimestamp = timestamp,
        text = text,
        snr = 4.0,
    )

    @Test
    fun `pollAllMessages persists an incoming direct message for a known contact`() = runTest {
        val contact = saveContact()
        service.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ContactMessageResult(contactMessage(senderPrefix = contact.publicKey.copyOf(6))))

        val count = service.pollAllMessages()

        assertEquals(1, count)
        val messages = messageStore.fetchMessages(contact.id)
        assertEquals(1, messages.size)
        assertEquals(MessageDirection.INCOMING, messages[0].direction)
        assertEquals(contact.id, messages[0].contactID)
        assertTrue(contactStore.fetchContact(contact.id)!!.lastMessageDate != null)
    }

    @Test
    fun `pollAllMessages persists an incoming direct message with no matching contact`() = runTest {
        service.startMessageEventMonitoring(radioID)
        val timestamp = Instant.ofEpochSecond(555_555)
        session.queuedMessages.add(
            MessageResult.ContactMessageResult(contactMessage(senderPrefix = ByteArray(6) { 0x42 }, text = "orphan", timestamp = timestamp)),
        )

        val count = service.pollAllMessages()

        assertEquals(1, count)
        // No local contact matches this sender prefix; the message still persists (contactID = null,
        // no DirectMessageReceived event) rather than being dropped, matching Swift's orphan-DM
        // handling minus auto-materialization from a pending advertisement.
        val expectedKey = DeduplicationKey.contentBased(null, null, null, timestamp.epochSecond.toUInt(), "orphan")
        assertTrue(messageStore.isDuplicateMessage(expectedKey, radioID))
    }

    @Test
    fun `pollAllMessages resolves an orphan DM via the pending-advert resolver when supplied`() = runTest {
        val senderPrefix = ByteArray(6) { 0x77.toByte() }
        var resolverCalls = 0
        var materializedId: UUID? = null
        // The resolver stands in for AdvertisementService.materializeContactForPendingAdvert:
        // no local contact exists yet when it's called, and it creates one as a side effect.
        val resolvingService = IncomingMessageService(
            session,
            messageStore,
            contactStore,
            channelStore,
            pendingAdvertResolver = { prefix, _ ->
                resolverCalls++
                assertTrue(prefix.contentEquals(senderPrefix))
                val materialized = saveContact(publicKey = senderPrefix + ByteArray(26) { 0x99.toByte() })
                materializedId = materialized.id
                materialized
            },
        )
        resolvingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ContactMessageResult(contactMessage(senderPrefix = senderPrefix)))

        resolvingService.pollAllMessages()

        assertEquals(1, resolverCalls)
        assertEquals(materializedId, messageStore.fetchMessages(materializedId!!).single().contactID)
    }

    @Test
    fun `pollAllMessages leaves an orphan DM unresolved when the resolver itself fails`() = runTest {
        val senderPrefix = ByteArray(6) { 0x78.toByte() }
        val resolvingService = IncomingMessageService(
            session,
            messageStore,
            contactStore,
            channelStore,
            pendingAdvertResolver = { _, _ -> error("resolver failure") },
        )
        resolvingService.startMessageEventMonitoring(radioID)
        val timestamp = Instant.ofEpochSecond(777_777)
        session.queuedMessages.add(
            MessageResult.ContactMessageResult(contactMessage(senderPrefix = senderPrefix, text = "still orphan", timestamp = timestamp)),
        )

        resolvingService.pollAllMessages()

        val expectedKey = DeduplicationKey.contentBased(null, null, null, timestamp.epochSecond.toUInt(), "still orphan")
        assertTrue(messageStore.isDuplicateMessage(expectedKey, radioID))
    }

    @Test
    fun `pollAllMessages persists an incoming channel message and parses the sender name`() = runTest {
        saveChannel()
        service.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: hi there")))

        val count = service.pollAllMessages()

        assertEquals(1, count)
        val messages = messageStore.fetchMessages(radioID, 0u)
        assertEquals(1, messages.size)
        assertEquals("Alice", messages[0].senderNodeName)
        assertEquals("hi there", messages[0].text)
    }

    @Test
    fun `pollAllMessages skips a duplicate message on the same content-based key`() = runTest {
        saveChannel()
        val timestamp = Instant.ofEpochSecond(123456)
        service.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: dup", timestamp = timestamp)))
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: dup", timestamp = timestamp)))

        val count = service.pollAllMessages()

        assertEquals(2, count) // both drained from the queue...
        assertEquals(1, messageStore.fetchMessages(radioID, 0u).size) // ...but only one persisted
    }

    @Test
    fun `pollAllMessages drains channel datagrams without persisting them`() = runTest {
        saveChannel()
        service.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(
            MessageResult.ChannelDatagramResult(
                com.meshcoretwo.protocol.ChannelDatagram(channelIndex = 0u, pathLength = 0xFFu, dataType = 1u, data = ByteArray(0), snr = 3.0),
            ),
        )

        val count = service.pollAllMessages()

        assertEquals(0, count)
        assertTrue(messageStore.fetchMessages(radioID, 0u).isEmpty())
    }

    @Test
    fun `pollAllMessages drops CLI and signed text types`() = runTest {
        val contact = saveContact()
        service.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(
            MessageResult.ContactMessageResult(
                contactMessage(senderPrefix = contact.publicKey.copyOf(6), textType = TextType.CLI_DATA.value),
            ),
        )
        session.queuedMessages.add(
            MessageResult.ContactMessageResult(
                contactMessage(senderPrefix = contact.publicKey.copyOf(6), textType = TextType.SIGNED.value),
            ),
        )

        val count = service.pollAllMessages()

        assertEquals(2, count) // drained from the device queue...
        assertTrue(messageStore.fetchMessages(contact.id).isEmpty()) // ...but not persisted
    }

    @Test
    fun `pollAllMessages routes a signed message to the room message handler`() = runTest {
        val senderPrefix = ByteArray(6) { 0x11 }
        val authorPrefix = ByteArray(4) { 0x22 }
        val timestamp = Instant.ofEpochSecond(888_888)
        var handlerCalls = 0
        var capturedSenderPrefix: ByteArray? = null
        var capturedTimestamp: UInt? = null
        var capturedAuthorPrefix: ByteArray? = null
        var capturedText: String? = null
        val routingService = IncomingMessageService(
            session,
            messageStore,
            contactStore,
            channelStore,
            roomMessageHandler = { prefix, ts, author, text ->
                handlerCalls++
                capturedSenderPrefix = prefix
                capturedTimestamp = ts
                capturedAuthorPrefix = author
                capturedText = text
            },
        )
        routingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(
            MessageResult.ContactMessageResult(
                contactMessage(
                    senderPrefix = senderPrefix,
                    text = "room text",
                    textType = TextType.SIGNED.value,
                    timestamp = timestamp,
                    signature = authorPrefix,
                ),
            ),
        )

        routingService.pollAllMessages()

        assertEquals(1, handlerCalls)
        assertTrue(capturedSenderPrefix!!.contentEquals(senderPrefix))
        assertEquals(timestamp.epochSecond.toUInt(), capturedTimestamp)
        assertTrue(capturedAuthorPrefix!!.contentEquals(authorPrefix))
        assertEquals("room text", capturedText)
        assertTrue(messageStore.fetchMessages(radioID, 0u).isEmpty())
    }

    @Test
    fun `pollAllMessages does not route a signed message with a missing or short signature`() = runTest {
        var handlerCalls = 0
        val routingService = IncomingMessageService(
            session,
            messageStore,
            contactStore,
            channelStore,
            roomMessageHandler = { _, _, _, _ -> handlerCalls++ },
        )
        routingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(
            MessageResult.ContactMessageResult(
                contactMessage(senderPrefix = ByteArray(6) { 0x33 }, textType = TextType.SIGNED.value, signature = null),
            ),
        )
        session.queuedMessages.add(
            MessageResult.ContactMessageResult(
                contactMessage(senderPrefix = ByteArray(6) { 0x34 }, textType = TextType.SIGNED.value, signature = ByteArray(3) { 0x44 }),
            ),
        )

        routingService.pollAllMessages()

        assertEquals(0, handlerCalls)
    }

    @Test
    fun `pollAllMessages routes a CLI message to the cli message handler for a known contact`() = runTest {
        val contact = saveContact()
        var handlerCalls = 0
        var capturedContact: ContactDto? = null
        var capturedText: String? = null
        val routingService = IncomingMessageService(
            session,
            messageStore,
            contactStore,
            channelStore,
            cliMessageHandler = { message, cliContact ->
                handlerCalls++
                capturedContact = cliContact
                capturedText = message.text
            },
        )
        routingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(
            MessageResult.ContactMessageResult(
                contactMessage(senderPrefix = contact.publicKey.copyOf(6), text = "OK", textType = TextType.CLI_DATA.value),
            ),
        )

        routingService.pollAllMessages()

        assertEquals(1, handlerCalls)
        assertEquals(contact.id, capturedContact?.id)
        assertEquals("OK", capturedText)
        assertTrue(messageStore.fetchMessages(contact.id).isEmpty()) // routed, not persisted as chat text
    }

    @Test
    fun `pollAllMessages does not route a CLI message when no contact matches the sender`() = runTest {
        var handlerCalls = 0
        val routingService = IncomingMessageService(
            session,
            messageStore,
            contactStore,
            channelStore,
            cliMessageHandler = { _, _ -> handlerCalls++ },
        )
        routingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(
            MessageResult.ContactMessageResult(
                contactMessage(senderPrefix = ByteArray(6) { 0x55 }, textType = TextType.CLI_DATA.value),
            ),
        )

        routingService.pollAllMessages()

        assertEquals(0, handlerCalls)
    }

    @Test
    fun `pollAllMessages clamps a future sender timestamp to receive time`() = runTest {
        val contact = saveContact()
        service.startMessageEventMonitoring(radioID)
        val farFuture = Instant.now().plusSeconds(60 * 60)
        session.queuedMessages.add(
            MessageResult.ContactMessageResult(contactMessage(senderPrefix = contact.publicKey.copyOf(6), timestamp = farFuture)),
        )

        service.pollAllMessages()

        val stored = messageStore.fetchMessages(contact.id).single()
        val storedTimestamp = Instant.ofEpochSecond(stored.timestamp.toLong())
        assertTrue(storedTimestamp.isBefore(farFuture))
    }

    @Test
    fun `pollAllMessages clamps a far-past sender timestamp to receive time`() = runTest {
        val contact = saveContact()
        service.startMessageEventMonitoring(radioID)
        val farPast = Instant.now().minusSeconds(400L * 24 * 60 * 60)
        session.queuedMessages.add(
            MessageResult.ContactMessageResult(contactMessage(senderPrefix = contact.publicKey.copyOf(6), timestamp = farPast)),
        )

        service.pollAllMessages()

        val stored = messageStore.fetchMessages(contact.id).single()
        val storedTimestamp = Instant.ofEpochSecond(stored.timestamp.toLong())
        assertTrue(storedTimestamp.isAfter(farPast))
    }

    @Test
    fun `event-driven direct message is persisted and emitted while not polling`() = runTest {
        val contact = saveContact()
        service.startMessageEventMonitoring(radioID)

        val received = ArrayBlockingQueue<IncomingMessageEvent>(1)
        val collectorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val collectorJob = collectorScope.launch {
            received.put(service.receivedEvents().first())
        }
        Thread.sleep(20) // let the collector subscribe before the event fires

        session.emit(MeshEvent.ContactMessageReceived(contactMessage(senderPrefix = contact.publicKey.copyOf(6))))

        val event = received.poll(2, TimeUnit.SECONDS)
        collectorJob.cancel()
        assertTrue(event is IncomingMessageEvent.DirectMessageReceived)
        assertEquals(1, messageStore.fetchMessages(contact.id).size)
    }

    @Test
    fun `stopMessageEventMonitoring stops delivering further events`() = runTest {
        val contact = saveContact()
        service.startMessageEventMonitoring(radioID)
        service.stopMessageEventMonitoring()

        session.emit(MeshEvent.ContactMessageReceived(contactMessage(senderPrefix = contact.publicKey.copyOf(6))))
        Thread.sleep(50)

        assertTrue(messageStore.fetchMessages(contact.id).isEmpty())
    }

    @Test
    fun `startAutoFetch enables session auto-fetch and isAutoFetching`() = runTest {
        assertFalse(service.isAutoFetching)
        service.startAutoFetch(radioID)
        assertTrue(service.isAutoFetching)
        assertTrue(session.autoFetchStarted)

        service.stopAutoFetch()
        assertFalse(service.isAutoFetching)
        assertTrue(session.autoFetchStopped)
    }

    // MARK: - Reaction Handling

    @Test
    fun `a reaction consumed by reactionHandling is not persisted as an ordinary message`() = runTest {
        saveChannel()
        val reacting = FakeReactionHandling(handleChannelResult = true)
        val reactingService = IncomingMessageService(session, messageStore, contactStore, channelStore, reactionHandling = reacting)
        reactingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: 👍@[Bob]\nabcdefgh")))

        reactingService.pollAllMessages()

        assertTrue(messageStore.fetchMessages(radioID, 0u).isEmpty())
        assertEquals(1, reacting.channelReactionCalls)
    }

    @Test
    fun `a message reactionHandling does not consume is persisted normally and then indexed`() = runTest {
        saveChannel()
        val reacting = FakeReactionHandling(handleChannelResult = false)
        val reactingService = IncomingMessageService(session, messageStore, contactStore, channelStore, reactionHandling = reacting)
        reactingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: hi there")))

        reactingService.pollAllMessages()

        assertEquals(1, messageStore.fetchMessages(radioID, 0u).size)
        assertEquals(1, reacting.indexChannelCalls)
    }

    @Test
    fun `a throwing reactionHandling fails open and the message still persists`() = runTest {
        saveChannel()
        val reacting = FakeReactionHandling(throwOnHandle = true)
        val reactingService = IncomingMessageService(session, messageStore, contactStore, channelStore, reactionHandling = reacting)
        reactingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: hi there")))

        reactingService.pollAllMessages()

        assertEquals(1, messageStore.fetchMessages(radioID, 0u).size)
    }

    @Test
    fun `a direct-message reaction is routed through handleDirectReaction only when a contact is resolved`() = runTest {
        val contact = saveContact()
        val reacting = FakeReactionHandling(handleDirectResult = true)
        val reactingService = IncomingMessageService(session, messageStore, contactStore, channelStore, reactionHandling = reacting)
        reactingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ContactMessageResult(contactMessage(senderPrefix = contact.publicKey.copyOf(6), text = "👍\nabcdefgh")))

        reactingService.pollAllMessages()

        assertEquals(1, reacting.directReactionCalls)
        assertTrue(messageStore.fetchMessages(contact.id).isEmpty())
    }

    // MARK: - RX-Log Correlation

    @Test
    fun `with no rxLogCorrelation supplied, pathNodes stays null and pathLength falls back to the wire value`() = runTest {
        saveChannel()
        service.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: hi there")))

        service.pollAllMessages()

        val message = messageStore.fetchMessages(radioID, 0u).single()
        assertNull(message.pathNodes)
        assertNull(message.routeType)
    }

    @Test
    fun `a channel message is correlated via rxLogCorrelation when supplied`() = runTest {
        saveChannel()
        val correlating = FakeRxLogCorrelating(
            RxLogPathData(pathNodes = byteArrayOf(0x11, 0x22), pathLength = 5u, routeType = com.meshcoretwo.protocol.RouteType.FLOOD),
        )
        val correlatingService = IncomingMessageService(session, messageStore, contactStore, channelStore, rxLogCorrelation = correlating)
        correlatingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: hi there")))

        correlatingService.pollAllMessages()

        val message = messageStore.fetchMessages(radioID, 0u).single()
        assertEquals(1, correlating.calls)
        assertTrue(byteArrayOf(0x11, 0x22).contentEquals(message.pathNodes))
        assertEquals(5, message.pathLength.toInt())
        assertEquals(com.meshcoretwo.protocol.RouteType.FLOOD, message.routeType)
    }

    @Test
    fun `a direct message is correlated via rxLogCorrelation, passing the sender key prefix`() = runTest {
        val contact = saveContact()
        val correlating = FakeRxLogCorrelating(
            RxLogPathData(pathNodes = byteArrayOf(0x33), pathLength = 1u, routeType = com.meshcoretwo.protocol.RouteType.DIRECT),
        )
        val correlatingService = IncomingMessageService(session, messageStore, contactStore, channelStore, rxLogCorrelation = correlating)
        correlatingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ContactMessageResult(contactMessage(senderPrefix = contact.publicKey.copyOf(6))))

        correlatingService.pollAllMessages()

        val message = messageStore.fetchMessages(contact.id).single()
        assertTrue(contact.publicKey.copyOf(6).contentEquals(correlating.lastSenderPublicKeyPrefix))
        assertEquals(com.meshcoretwo.protocol.RouteType.DIRECT, message.routeType)
    }

    @Test
    fun `a throwing rxLogCorrelation fails open and the message still persists with the wire path data`() = runTest {
        saveChannel()
        val correlating = FakeRxLogCorrelating(result = null, throwOnLookup = true)
        val correlatingService = IncomingMessageService(session, messageStore, contactStore, channelStore, rxLogCorrelation = correlating)
        correlatingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: hi there")))

        correlatingService.pollAllMessages()

        val message = messageStore.fetchMessages(radioID, 0u).single()
        assertNull(message.pathNodes)
        assertNull(message.routeType)
    }

    // MARK: - Extra flood paths

    @Test
    fun `a channel message correlates by deduplication key`() = runTest {
        saveChannel()
        val correlating = FakeRxLogCorrelating(RxLogPathData(byteArrayOf(0x11), 1u, com.meshcoretwo.protocol.RouteType.FLOOD))
        val correlatingService = IncomingMessageService(session, messageStore, contactStore, channelStore, rxLogCorrelation = correlating)
        correlatingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: hi there")))

        correlatingService.pollAllMessages()

        assertEquals(messageStore.fetchMessages(radioID, 0u).single().deduplicationKey, correlating.lastChannelDeduplicationKey)
    }

    @Test
    fun `a direct message passes no deduplication key to the correlator`() = runTest {
        val contact = saveContact()
        val correlating = FakeRxLogCorrelating(result = null)
        val correlatingService = IncomingMessageService(session, messageStore, contactStore, channelStore, rxLogCorrelation = correlating)
        correlatingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ContactMessageResult(contactMessage(senderPrefix = contact.publicKey.copyOf(6))))

        correlatingService.pollAllMessages()

        assertNull(correlating.lastChannelDeduplicationKey)
    }

    @Test
    fun `a duplicate channel message with a known path is recorded as an extra arrival`() = runTest {
        saveChannel()
        val correlating = FakeRxLogCorrelating(RxLogPathData(byteArrayOf(0x11), 1u, com.meshcoretwo.protocol.RouteType.FLOOD))
        val harvesting = FakePathHarvesting()
        val harvestingService = IncomingMessageService(session, messageStore, contactStore, channelStore, rxLogCorrelation = correlating, pathHarvesting = harvesting)
        harvestingService.startMessageEventMonitoring(radioID)
        val timestamp = Instant.ofEpochSecond(123456)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: dup", timestamp = timestamp)))
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: dup", timestamp = timestamp)))

        harvestingService.pollAllMessages()

        assertEquals(1, messageStore.fetchMessages(radioID, 0u).size)
        assertEquals(1, harvesting.recorded.size)
        assertTrue(byteArrayOf(0x11).contentEquals(harvesting.recorded.single()))
    }

    @Test
    fun `a duplicate with no known path is not recorded as an extra`() = runTest {
        saveChannel()
        val harvesting = FakePathHarvesting()
        val harvestingService = IncomingMessageService(session, messageStore, contactStore, channelStore, pathHarvesting = harvesting)
        harvestingService.startMessageEventMonitoring(radioID)
        val timestamp = Instant.ofEpochSecond(123456)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: dup", timestamp = timestamp)))
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: dup", timestamp = timestamp)))

        harvestingService.pollAllMessages()

        assertTrue(harvesting.recorded.isEmpty())
    }

    @Test
    fun `a saved channel message harvests copies already in the RX log`() = runTest {
        saveChannel()
        val correlating = FakeRxLogCorrelating(result = null)
        val harvesting = FakePathHarvesting()
        val harvestingService = IncomingMessageService(session, messageStore, contactStore, channelStore, rxLogCorrelation = correlating, pathHarvesting = harvesting)
        harvestingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: hi there")))

        harvestingService.pollAllMessages()

        assertEquals(1, harvesting.harvestCalls)
    }

    @Test
    fun `a throwing harvester does not undo the saved message`() = runTest {
        saveChannel()
        val correlating = FakeRxLogCorrelating(result = null)
        val harvesting = FakePathHarvesting(throwOnHarvest = true)
        val harvestingService = IncomingMessageService(session, messageStore, contactStore, channelStore, rxLogCorrelation = correlating, pathHarvesting = harvesting)
        harvestingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: hi there")))

        harvestingService.pollAllMessages()

        assertEquals(1, messageStore.fetchMessages(radioID, 0u).size)
    }

    // MARK: - Self-Mention Detection

    @Test
    fun `a direct message mentioning the self node name is flagged`() = runTest {
        val contact = saveContact()
        val mentioningService = IncomingMessageService(session, messageStore, contactStore, channelStore, selfNodeNameProvider = { "MyNode" })
        mentioningService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ContactMessageResult(contactMessage(senderPrefix = contact.publicKey.copyOf(6), text = "hey @[MyNode] check this")))

        mentioningService.pollAllMessages()

        assertTrue(messageStore.fetchMessages(contact.id).single().containsSelfMention)
    }

    @Test
    fun `a channel message mentioning the self node name is flagged`() = runTest {
        saveChannel()
        val mentioningService = IncomingMessageService(session, messageStore, contactStore, channelStore, selfNodeNameProvider = { "MyNode" })
        mentioningService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: @[MyNode] are you there?")))

        mentioningService.pollAllMessages()

        assertTrue(messageStore.fetchMessages(radioID, 0u).single().containsSelfMention)
    }

    @Test
    fun `a channel message is never flagged as self-mentioning its own sender`() = runTest {
        saveChannel()
        // The self node's own outgoing message, echoed back as a channel message, must not be
        // flagged as a mention of itself even though the sender name matches selfNodeName.
        val mentioningService = IncomingMessageService(session, messageStore, contactStore, channelStore, selfNodeNameProvider = { "MyNode" })
        mentioningService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "MyNode: @[MyNode] talking to myself")))

        mentioningService.pollAllMessages()

        assertFalse(messageStore.fetchMessages(radioID, 0u).single().containsSelfMention)
    }

    @Test
    fun `no selfNodeNameProvider means no message is ever flagged as a self-mention`() = runTest {
        val contact = saveContact()
        service.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ContactMessageResult(contactMessage(senderPrefix = contact.publicKey.copyOf(6), text = "hey @[MyNode]")))

        service.pollAllMessages()

        assertFalse(messageStore.fetchMessages(contact.id).single().containsSelfMention)
    }

    // MARK: - Blocked-Sender Filtering

    @Test
    fun `a channel message from a blocked sender is dropped before it's ever saved`() = runTest {
        saveChannel()
        contactStore.saveBlockedChannelSender(
            com.meshcoretwo.services.persistence.BlockedChannelSenderDto(id = UUID.randomUUID(), name = "Spammer", radioID = radioID, dateBlocked = Instant.now()),
        )
        service.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Spammer: buy now")))

        val count = service.pollAllMessages()

        assertEquals(1, count) // drained from the device queue...
        assertTrue(messageStore.fetchMessages(radioID, 0u).isEmpty()) // ...but not persisted
    }

    @Test
    fun `a direct message from a blocked contact still persists`() = runTest {
        val contact = saveContact()
        contactStore.updateContactPreferences(contact.id, nickname = null, isBlocked = true, isFavorite = false, unreadCount = 0)
        service.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ContactMessageResult(contactMessage(senderPrefix = contact.publicKey.copyOf(6))))

        service.pollAllMessages()

        assertEquals(1, messageStore.fetchMessages(contact.id).size)
    }

    // MARK: - Unread/Notification Wiring

    @Test
    fun `a saved direct message is forwarded to notifying with the resolved contact and self-mention flag`() = runTest {
        val contact = saveContact()
        val notifying = FakeIncomingMessageNotifying()
        val notifyingService = IncomingMessageService(session, messageStore, contactStore, channelStore, selfNodeNameProvider = { "MyNode" }, notifying = notifying)
        notifyingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ContactMessageResult(contactMessage(senderPrefix = contact.publicKey.copyOf(6), text = "hi @[MyNode]")))

        notifyingService.pollAllMessages()

        assertEquals(1, notifying.directCalls.size)
        assertEquals(contact.id, notifying.directCalls.single().second?.id)
        assertTrue(notifying.directCalls.single().third)
    }

    @Test
    fun `a saved channel message is forwarded to notifying with the resolved channel`() = runTest {
        saveChannel()
        val notifying = FakeIncomingMessageNotifying()
        val notifyingService = IncomingMessageService(session, messageStore, contactStore, channelStore, notifying = notifying)
        notifyingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ChannelMessageResult(channelMessage(text = "Alice: hi there")))

        notifyingService.pollAllMessages()

        assertEquals(1, notifying.channelCalls.size)
        assertEquals(0u.toUByte(), notifying.channelCalls.single().second)
    }

    @Test
    fun `a throwing notifying fails open and does not affect the already-saved message`() = runTest {
        val contact = saveContact()
        val notifying = FakeIncomingMessageNotifying(throwOnNotify = true)
        val notifyingService = IncomingMessageService(session, messageStore, contactStore, channelStore, notifying = notifying)
        notifyingService.startMessageEventMonitoring(radioID)
        session.queuedMessages.add(MessageResult.ContactMessageResult(contactMessage(senderPrefix = contact.publicKey.copyOf(6))))

        notifyingService.pollAllMessages()

        assertEquals(1, messageStore.fetchMessages(contact.id).size)
    }
}

/** Hand-written [IncomingMessageNotifying] test double. */
private class FakeIncomingMessageNotifying(private val throwOnNotify: Boolean = false) : IncomingMessageNotifying {
    val directCalls = mutableListOf<Triple<com.meshcoretwo.services.persistence.MessageDto, com.meshcoretwo.services.persistence.ContactDto?, Boolean>>()
    val channelCalls = mutableListOf<Triple<com.meshcoretwo.services.persistence.MessageDto, UByte, Boolean>>()

    override suspend fun notifyDirectMessage(message: com.meshcoretwo.services.persistence.MessageDto, contact: com.meshcoretwo.services.persistence.ContactDto?, hasSelfMention: Boolean) {
        directCalls.add(Triple(message, contact, hasSelfMention))
        if (throwOnNotify) error("boom")
    }

    override suspend fun notifyChannelMessage(
        message: com.meshcoretwo.services.persistence.MessageDto,
        channel: com.meshcoretwo.services.persistence.ChannelDto?,
        channelIndex: UByte,
        senderNodeName: String?,
        hasSelfMention: Boolean,
        radioID: UUID,
    ) {
        channelCalls.add(Triple(message, channelIndex, hasSelfMention))
        if (throwOnNotify) error("boom")
    }
}

/** Hand-written [RxLogCorrelating] test double. */
private class FakeRxLogCorrelating(
    private val result: RxLogPathData?,
    private val throwOnLookup: Boolean = false,
) : RxLogCorrelating {
    var calls = 0
    var lastSenderPublicKeyPrefix: ByteArray? = null
    var lastChannelDeduplicationKey: String? = null

    /** Decrypted channel rows this double hands back to the extra-flood-path harvest. */
    var channelEntries: List<com.meshcoretwo.services.persistence.RxLogDto> = emptyList()

    override suspend fun lookupPathData(
        radioID: UUID,
        channelIndex: UByte?,
        senderTimestamp: UInt,
        senderPublicKeyPrefix: ByteArray?,
        defaultPathLength: UByte,
        channelDeduplicationKey: String?,
    ): RxLogPathData {
        calls++
        lastSenderPublicKeyPrefix = senderPublicKeyPrefix
        lastChannelDeduplicationKey = channelDeduplicationKey
        if (throwOnLookup) error("boom")
        return result ?: RxLogPathData(pathNodes = null, pathLength = defaultPathLength, routeType = null)
    }

    override suspend fun decodedChannelEntries(radioID: UUID, channelIndex: UByte, senderTimestamp: UInt) = channelEntries
}

/** Hand-written [IncomingPathHarvesting] test double. */
private class FakePathHarvesting(private val throwOnHarvest: Boolean = false) : IncomingPathHarvesting {
    val recorded = mutableListOf<ByteArray>()
    var harvestCalls = 0

    override suspend fun recordDistinctPathIfNeeded(
        message: com.meshcoretwo.services.persistence.MessageDto,
        pathNodes: ByteArray,
        pathLength: UByte,
        snr: Double?,
        rssi: Int?,
        receivedAt: Instant,
        rxLogEntryID: UUID?,
    ): Int? {
        recorded.add(pathNodes)
        return recorded.size
    }

    override suspend fun harvestIncomingPaths(message: com.meshcoretwo.services.persistence.MessageDto, decodedCandidates: List<com.meshcoretwo.services.persistence.RxLogDto>) {
        harvestCalls++
        if (throwOnHarvest) error("boom")
    }
}

/** Hand-written [ReactionHandling] test double. */
private class FakeReactionHandling(
    private val handleChannelResult: Boolean = false,
    private val handleDirectResult: Boolean = false,
    private val throwOnHandle: Boolean = false,
) : ReactionHandling {
    var channelReactionCalls = 0
    var directReactionCalls = 0
    var indexChannelCalls = 0
    var indexDirectCalls = 0

    override suspend fun handleDirectReaction(text: String, contact: com.meshcoretwo.services.persistence.ContactDto, radioID: UUID): Boolean {
        directReactionCalls++
        if (throwOnHandle) error("boom")
        return handleDirectResult
    }

    override suspend fun handleChannelReaction(
        text: String,
        channelIndex: UByte,
        senderNodeName: String?,
        selfNodeName: String,
        receiveTime: Instant,
        radioID: UUID,
    ): Boolean {
        channelReactionCalls++
        if (throwOnHandle) error("boom")
        return handleChannelResult
    }

    override suspend fun indexDirectMessage(messageID: UUID, contactID: UUID, text: String, timestamp: UInt) {
        indexDirectCalls++
    }

    override suspend fun indexChannelMessage(messageID: UUID, channelIndex: UByte, senderName: String, text: String, timestamp: UInt) {
        indexChannelCalls++
    }
}

/** Hand-written [MeshCoreSessionProtocol] test double — see [IncomingMessageServiceTest]'s class doc. */
private class FakeIncomingSession : MeshCoreSessionProtocol {
    val selfInfo: SelfInfo? = SelfInfo(
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

    val queuedMessages = ArrayDeque<MessageResult>()
    var autoFetchStarted = false
    var autoFetchStopped = false

    private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 16)

    suspend fun emit(event: MeshEvent) {
        eventsFlow.emit(event)
        // Give the collector's coroutine a turn on Robolectric's shared dispatcher.
        kotlinx.coroutines.delay(10)
    }

    override val currentSelfInfo: SelfInfo? get() = selfInfo

    override suspend fun sendMessage(destination: ByteArray, text: String, timestamp: Instant, attempt: UByte): MessageSentInfo =
        error("not used by this vertical slice")

    override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant) =
        error("not used by this vertical slice")

    override val connectionState: Flow<ConnectionState> get() = error("not used by this vertical slice")

    override suspend fun events(): Flow<MeshEvent> = eventsFlow

    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = eventsFlow.filter { filter.matches(it) }

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? =
        error("not used by this vertical slice")

    override suspend fun getContacts(since: Instant?): List<MeshContact> = error("not used by this vertical slice")

    override suspend fun getContactsReportingTotal(since: Instant?) = error("not used by this vertical slice")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = error("not used by this vertical slice")

    override suspend fun addContact(contact: MeshContact) = error("not used by this vertical slice")

    override suspend fun removeContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun resetPath(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun sendPathDiscovery(destination: ByteArray) = error("not used by this vertical slice")

    override suspend fun shareContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun exportContact(publicKey: ByteArray?) = error("not used by this vertical slice")

    override suspend fun importContact(cardData: ByteArray) = error("not used by this vertical slice")

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = error("not used by this vertical slice")

    override suspend fun getChannel(index: UByte): ChannelInfo = error("not used by this vertical slice")

    override suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult = error("not used by this vertical slice")

    override suspend fun setChannel(index: UByte, name: String, secret: ByteArray) = error("not used by this vertical slice")

    override suspend fun getMessage(timeout: Double?): MessageResult =
        if (queuedMessages.isNotEmpty()) queuedMessages.removeFirst() else MessageResult.NoMoreMessages

    override suspend fun startAutoMessageFetching() {
        autoFetchStarted = true
    }

    override fun stopAutoMessageFetching() {
        autoFetchStopped = true
    }
}
