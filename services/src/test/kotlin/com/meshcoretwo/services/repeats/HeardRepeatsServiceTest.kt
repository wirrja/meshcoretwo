// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.repeats

import androidx.room.Room
import com.meshcoretwo.protocol.PayloadType
import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.DecryptStatus
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageRepeatStore
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.RxLogDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Ported from the "processForRepeats Matching Tests" section of `HeardRepeatsServiceTests.swift`
 * (the `ChannelMessageFormat.parse` cases live in [com.meshcoretwo.services.utilities.ChannelMessageFormatTest]
 * instead, matching that utility's own file).
 */
@RunWith(RobolectricTestRunner::class)
class HeardRepeatsServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var messageStore: MessageStore
    private lateinit var messageRepeatStore: MessageRepeatStore
    private lateinit var service: HeardRepeatsService
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        messageStore = MessageStore(database)
        messageRepeatStore = MessageRepeatStore(database)
        service = HeardRepeatsService(messageStore, messageRepeatStore)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sentChannelMessage(id: UUID, channelIndex: UByte, text: String, timestamp: UInt) = MessageDto(
        id = id, radioID = radioID, contactID = null, channelIndex = channelIndex, text = text,
        timestamp = timestamp, createdAt = Instant.now(), sortDate = Instant.now(), direction = MessageDirection.OUTGOING,
        status = MessageStatus.DELIVERED, textType = TextType.PLAIN_TEXT, ackCode = null, pathLength = 0u, snr = null,
        pathNodes = null, senderKeyPrefix = null, senderNodeName = null, isRead = true, replyToID = null,
        roundTripTime = null, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0, deduplicationKey = null,
        reactionSummary = null, senderTimestamp = null, routeType = null, heardRepeats = 0,
    )

    /** Builds a decrypted channel-message echo the service can correlate: `"NodeName: body"` decoded text plus a matching sender timestamp. */
    private fun makeEcho(
        channelIndex: UByte,
        senderTimestamp: UInt,
        body: String,
        senderName: String = "TestNode",
        id: UUID = UUID.randomUUID(),
        payloadType: PayloadType = PayloadType.GROUP_TEXT,
        decryptStatus: DecryptStatus = DecryptStatus.SUCCESS,
        decodedText: String? = "$senderName: $body",
    ) = RxLogDto(
        id = id, radioID = radioID, receivedAt = Instant.now(), snr = 8.0, rssi = -70,
        routeType = RouteType.FLOOD, payloadType = payloadType, payloadVersion = 0u,
        pathLength = 1u, pathNodes = byteArrayOf(0x42), packetPayload = byteArrayOf(0x01, 0x02, 0x03),
        rawPayload = byteArrayOf(0x01, 0x02, 0x03), packetHash = "hash-$id", channelIndex = channelIndex, channelName = "Test",
        decryptStatus = decryptStatus, senderTimestamp = senderTimestamp, decodedText = decodedText,
    )

    @Test
    fun `counts a repeat whose send is far outside the old 10s window`() = runTest {
        val channelIndex: UByte = 2u
        val sendTimestamp = (Instant.now().epochSecond - 120).toUInt()
        val messageID = UUID.randomUUID()
        messageStore.saveMessage(sentChannelMessage(messageID, channelIndex, "north repeater check", sendTimestamp))
        service.configure(radioID)

        val received = mutableListOf<HeardRepeatEvent>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { service.events().collect { received.add(it) } }
        val echo = makeEcho(channelIndex, sendTimestamp, "north repeater check")
        val count = service.processForRepeats(echo)
        job.cancel()

        assertEquals(1, count)
        val repeats = messageRepeatStore.fetchMessageRepeats(messageID)
        assertEquals(1, repeats.size)
        assertEquals(echo.id, repeats.first().rxLogEntryID)
        assertEquals(1, received.size)
        assertEquals(messageID, received.first().messageID)
        assertEquals(1, received.first().count)
    }

    @Test
    fun `same RX log entry is counted once`() = runTest {
        val channelIndex: UByte = 0u
        val sendTimestamp = Instant.now().epochSecond.toUInt()
        val messageID = UUID.randomUUID()
        messageStore.saveMessage(sentChannelMessage(messageID, channelIndex, "hello", sendTimestamp))
        service.configure(radioID)

        val echo = makeEcho(channelIndex, sendTimestamp, "hello")
        val first = service.processForRepeats(echo)
        val second = service.processForRepeats(echo)

        assertEquals(1, first)
        assertNull(second)
        assertEquals(1, messageRepeatStore.fetchMessageRepeats(messageID).size)
    }

    @Test
    fun `no match for unknown timestamp`() = runTest {
        val channelIndex: UByte = 1u
        val sendTimestamp = Instant.now().epochSecond.toUInt()
        messageStore.saveMessage(sentChannelMessage(UUID.randomUUID(), channelIndex, "hello", sendTimestamp))
        service.configure(radioID)

        val wrongTimestamp = makeEcho(channelIndex, sendTimestamp + 5u, "hello")

        assertNull(service.processForRepeats(wrongTimestamp))
    }

    @Test
    fun `new-node rename then three TEST Hello echoes attach`() = runTest {
        val channelIndex: UByte = 0u
        val sendTimestamp = Instant.now().epochSecond.toUInt()
        val messageID = UUID.randomUUID()
        messageStore.saveMessage(sentChannelMessage(messageID, channelIndex, "Hello", sendTimestamp))
        service.configure(radioID)

        var lastCount: Int? = null
        repeat(3) {
            lastCount = service.processForRepeats(makeEcho(channelIndex, sendTimestamp, "Hello", senderName = "TEST"))
        }

        assertEquals(3, lastCount)
        assertEquals(3, messageRepeatStore.fetchMessageRepeats(messageID).size)
    }

    @Test
    fun `ignores non-groupText payload types`() = runTest {
        service.configure(radioID)
        val echo = makeEcho(0u, 100u, "hello", payloadType = PayloadType.GROUP_DATA)

        assertNull(service.processForRepeats(echo))
    }

    @Test
    fun `ignores entries that failed to decrypt`() = runTest {
        service.configure(radioID)
        val echo = makeEcho(0u, 100u, "hello", decryptStatus = DecryptStatus.NO_MATCHING_KEY, decodedText = null)

        assertNull(service.processForRepeats(echo))
    }

    @Test
    fun `ignores decoded text with no sender prefix`() = runTest {
        service.configure(radioID)
        val echo = makeEcho(0u, 100u, "hello", decodedText = "no colon here")

        assertNull(service.processForRepeats(echo))
    }

    @Test
    fun `returns null before configure is called`() = runTest {
        val echo = makeEcho(0u, 100u, "hello")

        assertNull(service.processForRepeats(echo))
    }

    @Test
    fun `refreshRepeats returns recorded repeats for a message`() = runTest {
        val channelIndex: UByte = 0u
        val sendTimestamp = Instant.now().epochSecond.toUInt()
        val messageID = UUID.randomUUID()
        messageStore.saveMessage(sentChannelMessage(messageID, channelIndex, "hello", sendTimestamp))
        service.configure(radioID)
        service.processForRepeats(makeEcho(channelIndex, sendTimestamp, "hello"))

        val repeats = service.refreshRepeats(messageID)

        assertEquals(1, repeats.size)
    }

    @Test
    fun `refreshRepeats returns empty for a message with no repeats`() = runTest {
        assertTrue(service.refreshRepeats(UUID.randomUUID()).isEmpty())
    }
    // MARK: - Extra incoming flood paths

    private fun incomingChannelMessage(channelIndex: UByte, sender: String, body: String, timestamp: UInt, pathNodes: ByteArray?, pathLength: UByte = 1u): MessageDto {
        val key = com.meshcoretwo.services.messages.DeduplicationKey.contentBased(null, channelIndex, sender, timestamp, body)
        return sentChannelMessage(UUID.randomUUID(), channelIndex, body, timestamp).copy(
            direction = MessageDirection.INCOMING, senderNodeName = sender, deduplicationKey = key,
            pathNodes = pathNodes, pathLength = pathLength, routeType = RouteType.FLOOD,
        )
    }

    private fun floodCopy(channelIndex: UByte, timestamp: UInt, body: String, sender: String, path: ByteArray) =
        makeEcho(channelIndex, timestamp, body, senderName = sender).copy(pathNodes = path, pathLength = path.size.toUByte())

    @Test
    fun `later flood copy with a distinct path becomes an extra on the incoming message`() = runTest {
        val message = incomingChannelMessage(1u, "Alice", "hi all", 500u, byteArrayOf(0x11))
        messageStore.saveMessage(message)
        service.configure(radioID)

        val count = service.processForRepeats(floodCopy(1u, 500u, "hi all", "Alice", byteArrayOf(0x22, 0x33)))

        assertEquals(1, count)
        assertEquals(1, messageStore.fetchMessage(message.id)!!.heardRepeats)
        assertEquals(1, messageRepeatStore.fetchMessageRepeats(message.id).size)
    }

    @Test
    fun `flood copy over the message's own path is not an extra`() = runTest {
        val message = incomingChannelMessage(1u, "Alice", "hi all", 500u, byteArrayOf(0x11))
        messageStore.saveMessage(message)
        service.configure(radioID)

        assertNull(service.processForRepeats(floodCopy(1u, 500u, "hi all", "Alice", byteArrayOf(0x11))))
        assertEquals(0, messageStore.fetchMessage(message.id)!!.heardRepeats)
    }

    @Test
    fun `the same extra path is recorded once`() = runTest {
        val message = incomingChannelMessage(1u, "Alice", "hi all", 500u, byteArrayOf(0x11))
        messageStore.saveMessage(message)
        service.configure(radioID)

        service.processForRepeats(floodCopy(1u, 500u, "hi all", "Alice", byteArrayOf(0x22)))
        assertNull(service.processForRepeats(floodCopy(1u, 500u, "hi all", "Alice", byteArrayOf(0x22))))

        assertEquals(1, messageRepeatStore.fetchMessageRepeats(message.id).size)
    }

    @Test
    fun `a different message sharing the sender timestamp does not receive extras`() = runTest {
        val message = incomingChannelMessage(1u, "Alice", "hi all", 500u, byteArrayOf(0x11))
        messageStore.saveMessage(message)
        service.configure(radioID)

        assertNull(service.processForRepeats(floodCopy(1u, 500u, "something else", "Alice", byteArrayOf(0x22))))
        assertEquals(0, messageStore.fetchMessage(message.id)!!.heardRepeats)
    }

    @Test
    fun `an unknown canonical path adopts the first observation instead of recording an extra`() = runTest {
        val message = incomingChannelMessage(1u, "Alice", "hi all", 500u, pathNodes = null, pathLength = 0u)
        messageStore.saveMessage(message)
        service.configure(radioID)

        assertNull(service.processForRepeats(floodCopy(1u, 500u, "hi all", "Alice", byteArrayOf(0x22))))

        val stored = messageStore.fetchMessage(message.id)!!
        assertTrue(byteArrayOf(0x22).contentEquals(stored.pathNodes))
        assertEquals(0, stored.heardRepeats)
        assertTrue(messageRepeatStore.fetchMessageRepeats(message.id).isEmpty())
    }

    @Test
    fun `harvest adopts the oldest match then records later distinct paths`() = runTest {
        val message = incomingChannelMessage(1u, "Alice", "hi all", 500u, pathNodes = null, pathLength = 0u)
        messageStore.saveMessage(message)
        service.configure(radioID)
        val t0 = Instant.now()
        val candidates = listOf(
            floodCopy(1u, 500u, "hi all", "Alice", byteArrayOf(0x22)).copy(receivedAt = t0),
            floodCopy(1u, 500u, "hi all", "Alice", byteArrayOf(0x33)).copy(receivedAt = t0.plusSeconds(1)),
            floodCopy(1u, 500u, "hi all", "Alice", byteArrayOf(0x33)).copy(receivedAt = t0.plusSeconds(2)),
            floodCopy(1u, 500u, "other text", "Alice", byteArrayOf(0x44)).copy(receivedAt = t0.plusSeconds(3)),
        )

        service.harvestIncomingPaths(message, candidates)

        val stored = messageStore.fetchMessage(message.id)!!
        assertTrue(byteArrayOf(0x22).contentEquals(stored.pathNodes))
        assertEquals(1, stored.heardRepeats)
        assertTrue(byteArrayOf(0x33).contentEquals(messageRepeatStore.fetchMessageRepeats(message.id).single().pathNodes))
    }

    @Test
    fun `harvest ignores outgoing messages`() = runTest {
        val message = sentChannelMessage(UUID.randomUUID(), 1u, "hi all", 500u)
        messageStore.saveMessage(message)
        service.configure(radioID)

        service.harvestIncomingPaths(message, listOf(floodCopy(1u, 500u, "hi all", "Me", byteArrayOf(0x22))))

        assertTrue(messageRepeatStore.fetchMessageRepeats(message.id).isEmpty())
    }
}
