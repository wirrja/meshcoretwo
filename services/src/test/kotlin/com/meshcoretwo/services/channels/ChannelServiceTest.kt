// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.channels

import androidx.room.Room
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ChannelSessionOps
import com.meshcoretwo.protocol.ChannelsFetchResult
import com.meshcoretwo.protocol.ErrorCode
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.ChannelFloodScope
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.NotificationLevel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * Exercises [ChannelService] against a real in-memory [ChannelStore] (Room, via Robolectric —
 * see [com.meshcoretwo.services.persistence.ChannelStoreTest]) and [FakeChannelSessionOps], a
 * hand-written test double for the narrow [ChannelSessionOps] role interface — the same pattern
 * [com.meshcoretwo.services.contacts.ContactServiceTest] established.
 *
 * Backoff/retry delays run under [runTest]'s virtual time, so tests that exercise the full
 * 3-attempt exponential backoff or the sync circuit breaker complete instantly despite the
 * (virtual) multi-second delays involved.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeChannelSessionOps
    private lateinit var channelStore: ChannelStore
    private lateinit var messageStore: MessageStore
    private lateinit var service: ChannelService
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeChannelSessionOps()
        channelStore = ChannelStore(database)
        messageStore = MessageStore(database)
        service = ChannelService(session, channelStore, messageStore)
    }

    private fun channelMessage(channelIndex: UByte, text: String = "hi") = MessageDto(
        id = UUID.randomUUID(), radioID = radioID, contactID = null, channelIndex = channelIndex, text = text,
        timestamp = 1000u, createdAt = java.time.Instant.now(), sortDate = java.time.Instant.now(),
        direction = MessageDirection.OUTGOING, status = MessageStatus.DELIVERED,
        textType = com.meshcoretwo.protocol.TextType.PLAIN_TEXT, ackCode = null, pathLength = 0u, snr = null,
        pathNodes = null, senderKeyPrefix = null, senderNodeName = null, isRead = true, replyToID = null,
        roundTripTime = null, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0, deduplicationKey = null,
        reactionSummary = null, senderTimestamp = null, routeType = null, heardRepeats = 0,
    )

    @After
    fun tearDown() {
        database.close()
    }

    private fun channelInfo(index: UByte, name: String = "Channel$index", secret: ByteArray = ByteArray(16) { 0x02 }) =
        ChannelInfo(index, name, secret)

    // MARK: - Secret hashing / validation / URI export

    @Test
    fun `hashSecret returns 16 zero bytes for an empty passphrase`() {
        val secret = ChannelService.hashSecret("")
        assertEquals(16, secret.size)
        assertTrue(secret.all { it == 0.toByte() })
    }

    @Test
    fun `hashSecret is deterministic and 16 bytes for a non-empty passphrase`() {
        val a = ChannelService.hashSecret("correct horse battery staple")
        val b = ChannelService.hashSecret("correct horse battery staple")
        assertEquals(16, a.size)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `validateSecret accepts only exactly 16 bytes`() {
        assertTrue(ChannelService.validateSecret(ByteArray(16)))
        assertFalse(ChannelService.validateSecret(ByteArray(15)))
        assertFalse(ChannelService.validateSecret(ByteArray(17)))
    }

    @Test
    fun `exportChannelURI omits region_scope outside Region flood scope`() {
        val secret = ByteArray(16) { 0xAB.toByte() }
        val uri = ChannelService.exportChannelURI("Public", secret, ChannelFloodScope.Inherit)
        assertTrue(uri.startsWith("meshcore://channel/add?"))
        assertTrue(uri.contains("name=Public"))
        assertTrue(uri.contains("secret=${secret.hexString.uppercase()}"))
        assertFalse(uri.contains("region_scope"))
    }

    @Test
    fun `exportChannelURI includes region_scope for a Region flood scope`() {
        val uri = ChannelService.exportChannelURI("Ops", ByteArray(16), ChannelFloodScope.Region("us-west"))
        assertTrue(uri.contains("region_scope=us-west"))
    }

    @Test
    fun `parseChannelURI round-trips a URI built by exportChannelURI`() {
        val secret = ByteArray(16) { 0xAB.toByte() }
        val uri = ChannelService.exportChannelURI("Ops", secret, ChannelFloodScope.Region("us-west"))

        val invite = ChannelService.parseChannelURI(uri)

        assertEquals("Ops", invite?.name)
        assertTrue(secret.contentEquals(invite?.secret ?: ByteArray(0)))
    }

    @Test
    fun `parseChannelURI rejects the wrong scheme, host, or path`() {
        val secretHex = ByteArray(16).hexString.uppercase()
        assertNull(ChannelService.parseChannelURI("https://channel/add?name=Ops&secret=$secretHex"))
        assertNull(ChannelService.parseChannelURI("meshcore://contact/add?name=Ops&secret=$secretHex"))
        assertNull(ChannelService.parseChannelURI("meshcore://channel/join?name=Ops&secret=$secretHex"))
    }

    @Test
    fun `parseChannelURI rejects a missing name or malformed secret`() {
        val secretHex = ByteArray(16).hexString.uppercase()
        assertNull(ChannelService.parseChannelURI("meshcore://channel/add?secret=$secretHex"))
        assertNull(ChannelService.parseChannelURI("meshcore://channel/add?name=Ops"))
        assertNull(ChannelService.parseChannelURI("meshcore://channel/add?name=Ops&secret=not-hex"))
        assertNull(ChannelService.parseChannelURI("meshcore://channel/add?name=Ops&secret=AB")) // too short
    }

    @Test
    fun `parseChannelURI extracts a normalized region_scope`() {
        val uri = ChannelService.exportChannelURI("Ops", ByteArray(16), ChannelFloodScope.Region("us-west"))
        assertEquals("us-west", ChannelService.parseChannelURI(uri)?.regionScope)
    }

    @Test
    fun `parseChannelURI treats a missing region_scope as null`() {
        val uri = ChannelService.exportChannelURI("Ops", ByteArray(16), ChannelFloodScope.Inherit)
        assertNull(ChannelService.parseChannelURI(uri)?.regionScope)
    }

    @Test
    fun `parseChannelURI trims whitespace and treats a blank region_scope as null`() {
        val secretHex = ByteArray(16).hexString.uppercase()
        val trimmed = ChannelService.parseChannelURI("meshcore://channel/add?name=Ops&secret=$secretHex&region_scope=%20us-west%20")
        val blank = ChannelService.parseChannelURI("meshcore://channel/add?name=Ops&secret=$secretHex&region_scope=%20%20")
        assertEquals("us-west", trimmed?.regionScope)
        assertNull(blank?.regionScope)
    }

    @Test
    fun `parseChannelURI caps region_scope to the max flood-scope name length`() {
        val secretHex = ByteArray(16).hexString.uppercase()
        val overLong = "a".repeat(40)
        val invite = ChannelService.parseChannelURI("meshcore://channel/add?name=Ops&secret=$secretHex&region_scope=$overLong")
        assertEquals(30, invite?.regionScope?.toByteArray(Charsets.UTF_8)?.size)
    }

    // MARK: - fetchChannel

    @Test
    fun `fetchChannel returns the configured channel`() = runTest {
        session.channels[1u] = channelInfo(1u, name = "Ops")

        val result = service.fetchChannel(1u)

        assertEquals("Ops", result?.name)
    }

    @Test
    fun `fetchChannel returns null for an unconfigured slot`() = runTest {
        session.channels[2u] = channelInfo(2u, name = "", secret = ByteArray(16))

        assertNull(service.fetchChannel(2u))
    }

    @Test
    fun `fetchChannel returns null when the device reports not found`() = runTest {
        session.errorsByIndex[3u] = MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value)

        assertNull(service.fetchChannel(3u))
    }

    @Test
    fun `fetchChannel retries on timeout and succeeds`() = runTest {
        session.channels[4u] = channelInfo(4u, name = "Recovered")
        session.timeoutCountByIndex[4u] = 2 // first 2 attempts time out, 3rd succeeds

        val result = service.fetchChannel(4u)

        assertEquals("Recovered", result?.name)
    }

    @Test
    fun `fetchChannel throws SessionError after exhausting all retries`() = runTest {
        session.timeoutCountByIndex[5u] = 10 // always times out

        try {
            service.fetchChannel(5u)
            fail("expected ChannelServiceError.SessionError")
        } catch (error: ChannelServiceError.SessionError) {
            assertEquals(MeshCoreError.Timeout, error.error)
        }
    }

    @Test
    fun `fetchChannel wraps a non-timeout session error without retrying`() = runTest {
        session.errorsByIndex[6u] = MeshCoreError.ParseError("bad frame")

        try {
            service.fetchChannel(6u)
            fail("expected ChannelServiceError.SessionError")
        } catch (error: ChannelServiceError.SessionError) {
            assertTrue(error.error is MeshCoreError.ParseError)
        }
        assertEquals("Non-timeout errors should not retry", 1, session.getChannelCallCount[6u])
    }

    // MARK: - setChannel / setChannelWithSecret / clearChannel

    @Test
    fun `setChannel sends to the device and persists locally`() = runTest {
        service.setChannel(radioID, 0u, "General", "hunter2")

        assertEquals(1, session.setChannelCalls.size)
        val stored = service.getChannel(radioID, 0u)
        assertEquals("General", stored?.name)
    }

    @Test
    fun `setChannelWithSecret rejects a secret that is not 16 bytes`() = runTest {
        try {
            service.setChannelWithSecret(radioID, 0u, "General", ByteArray(8))
            fail("expected ChannelServiceError.SecretHashingFailed")
        } catch (error: ChannelServiceError.SecretHashingFailed) {
            // expected
        }
        assertTrue(session.setChannelCalls.isEmpty())
    }

    @Test
    fun `clearChannel clears the device slot and deletes the local row`() = runTest {
        service.setChannel(radioID, 2u, "Temp", "pw")

        service.clearChannel(radioID, 2u)

        assertNull(service.getChannel(radioID, 2u))
        val clearCall = session.setChannelCalls.last()
        assertEquals("", clearCall.second)
        assertTrue(clearCall.third.all { it == 0.toByte() })
    }

    @Test
    fun `clearChannel deletes the channel's messages along with the row`() = runTest {
        service.setChannel(radioID, 2u, "Temp", "pw")
        messageStore.saveMessage(channelMessage(2u))
        messageStore.saveMessage(channelMessage(3u)) // different channel, must survive

        service.clearChannel(radioID, 2u)

        assertTrue(messageStore.fetchMessages(radioID, 2u).isEmpty())
        assertEquals(1, messageStore.fetchMessages(radioID, 3u).size)
    }

    @Test
    fun `clearChannelMessages deletes messages and resets unread badges without deleting the channel`() = runTest {
        service.setChannel(radioID, 2u, "Temp", "pw")
        val channelId = service.getChannel(radioID, 2u)!!.id
        messageStore.saveMessage(channelMessage(2u))
        channelStore.updateChannelLastMessage(channelId, java.time.Instant.now())

        service.clearChannelMessages(radioID, 2u)

        assertTrue(messageStore.fetchMessages(radioID, 2u).isEmpty())
        val channel = service.getChannel(radioID, 2u)
        assertEquals(channelId, channel?.id) // row survives, unlike clearChannel
        assertNull(channel?.lastMessageDate)
        assertEquals(0, channel?.unreadCount)
        assertEquals(0, channel?.unreadMentionCount)
    }

    @Test
    fun `markConversationRead resets unread badges but leaves messages and last-message date in place`() = runTest {
        service.setChannel(radioID, 2u, "Temp", "pw")
        val channelId = service.getChannel(radioID, 2u)!!.id
        messageStore.saveMessage(channelMessage(2u))
        val lastMessageDate = java.time.Instant.ofEpochSecond(1_700_000_000)
        channelStore.updateChannelLastMessage(channelId, lastMessageDate)
        channelStore.incrementChannelUnreadCount(channelId)
        channelStore.incrementChannelUnreadMentionCount(channelId)

        service.markConversationRead(channelId)

        assertEquals(1, messageStore.fetchMessages(radioID, 2u).size)
        val channel = service.getChannel(radioID, 2u)
        assertEquals(lastMessageDate, channel?.lastMessageDate)
        assertEquals(0, channel?.unreadCount)
        assertEquals(0, channel?.unreadMentionCount)
    }

    @Test
    fun `observeChannels reflects an unread reset without a separate fetch`() = runTest {
        service.setChannel(radioID, 2u, "Temp", "pw")
        val channelId = service.getChannel(radioID, 2u)!!.id
        channelStore.incrementChannelUnreadCount(channelId)

        assertEquals(1, service.observeChannels(radioID).first().first { it.id == channelId }.unreadCount)

        service.markConversationRead(channelId)

        assertEquals(0, service.observeChannels(radioID).first().first { it.id == channelId }.unreadCount)
    }

    @Test
    fun `clearChannelMessages is a no-op when the channel doesn't exist locally`() = runTest {
        service.clearChannelMessages(radioID, 9u)

        assertNull(service.getChannel(radioID, 9u))
    }

    // MARK: - Public channel

    @Test
    fun `setupPublicChannel creates slot 0 and hasPublicChannel reports it`() = runTest {
        assertFalse(service.hasPublicChannel(radioID))

        service.setupPublicChannel(radioID)

        assertTrue(service.hasPublicChannel(radioID))
        assertEquals("Public", service.getChannel(radioID, 0u)?.name)
    }

    // MARK: - App-only metadata

    @Test
    fun `setFavorite persists without a session round-trip`() = runTest {
        service.setChannel(radioID, 1u, "Ops", "secret")
        val channelID = service.getChannel(radioID, 1u)!!.id

        service.setFavorite(channelID, true)

        assertTrue(service.getChannel(radioID, 1u)!!.isFavorite)
        assertEquals(1, session.setChannelCalls.size)
    }

    @Test
    fun `setNotificationLevel persists without a session round-trip`() = runTest {
        service.setChannel(radioID, 1u, "Ops", "secret")
        val channelID = service.getChannel(radioID, 1u)!!.id

        service.setNotificationLevel(channelID, NotificationLevel.MUTED)

        assertEquals(NotificationLevel.MUTED, service.getChannel(radioID, 1u)!!.notificationLevel)
        assertEquals(1, session.setChannelCalls.size)
    }

    @Test
    fun `setChannelFloodScope persists mode and region atomically without a session round-trip`() = runTest {
        service.setChannel(radioID, 1u, "Ops", "secret")
        val channelID = service.getChannel(radioID, 1u)!!.id

        service.setChannelFloodScope(channelID, ChannelFloodScope.Region("us-west"))

        assertEquals(ChannelFloodScope.Region("us-west"), service.getChannel(radioID, 1u)!!.floodScope)
        assertEquals(1, session.setChannelCalls.size)

        service.setChannelFloodScope(channelID, ChannelFloodScope.Inherit)

        assertEquals(ChannelFloodScope.Inherit, service.getChannel(radioID, 1u)!!.floodScope)
    }

    @Test
    fun `resetChannelsScopedToRegion reverts only channels pinned to that region`() = runTest {
        service.setChannel(radioID, 1u, "Ops", "secret")
        service.setChannel(radioID, 2u, "Recon", "secret")
        service.setChannel(radioID, 3u, "General", "secret")
        val opsID = service.getChannel(radioID, 1u)!!.id
        val reconID = service.getChannel(radioID, 2u)!!.id
        val generalID = service.getChannel(radioID, 3u)!!.id
        service.setChannelFloodScope(opsID, ChannelFloodScope.Region("us-west"))
        service.setChannelFloodScope(reconID, ChannelFloodScope.Region("us-east"))
        service.setChannelFloodScope(generalID, ChannelFloodScope.AllRegions)

        service.resetChannelsScopedToRegion(radioID, "us-west")

        assertEquals(ChannelFloodScope.Inherit, service.getChannel(radioID, 1u)!!.floodScope)
        assertEquals(ChannelFloodScope.Region("us-east"), service.getChannel(radioID, 2u)!!.floodScope)
        assertEquals(ChannelFloodScope.AllRegions, service.getChannel(radioID, 3u)!!.floodScope)
    }

    @Test
    fun `resetChannelsScopedToRegion is a no-op when no channel is pinned to that region`() = runTest {
        service.setChannel(radioID, 1u, "Ops", "secret")
        val channelID = service.getChannel(radioID, 1u)!!.id
        service.setChannelFloodScope(channelID, ChannelFloodScope.Inherit)

        service.resetChannelsScopedToRegion(radioID, "us-west")

        assertEquals(ChannelFloodScope.Inherit, service.getChannel(radioID, 1u)!!.floodScope)
    }

    // MARK: - syncChannels (serial)

    @Test
    fun `syncChannels persists every configured channel and prunes unconfigured slots`() = runTest {
        session.channels[0u] = channelInfo(0u, name = "A")
        session.channels[1u] = channelInfo(1u, name = "", secret = ByteArray(16)) // unconfigured

        val result = service.syncChannels(radioID, maxChannels = 2u, usePipelinedRead = false)

        assertEquals(1, result.channelsSynced)
        assertTrue(result.isComplete)
        val stored = service.getChannels(radioID)
        assertEquals(1, stored.size)
        assertEquals("A", stored.first().name)
    }

    @Test
    fun `syncChannels opens the circuit breaker after 3 consecutive timeouts`() = runTest {
        for (i in 0 until 5) session.timeoutCountByIndex[i.toUByte()] = 10 // every slot always times out

        val result = service.syncChannels(radioID, maxChannels = 5u, usePipelinedRead = false)

        assertTrue(result.circuitBreakerAborted)
        // Indices 0-2 fail normally (3 attempts each); the breaker then marks 3, 4 without calling the device.
        assertEquals(0, session.getChannelCallCount[3u] ?: 0)
        assertEquals(0, session.getChannelCallCount[4u] ?: 0)
    }

    @Test
    fun `syncChannels rejects a concurrent sync`() = runTest {
        session.holdGetChannels = true // getChannels() suspends until released, keeping isSyncing claimed

        val firstSync = async { service.syncChannels(radioID, maxChannels = 1u, usePipelinedRead = true) }
        // Let the first sync actually start and claim isSyncing before firing the second.
        session.awaitGetChannelsEntered()

        try {
            service.syncChannels(radioID, maxChannels = 1u, usePipelinedRead = true)
            fail("expected ChannelServiceError.SyncAlreadyInProgress")
        } catch (error: ChannelServiceError.SyncAlreadyInProgress) {
            // expected
        }

        session.releaseGetChannels()
        firstSync.await()
    }

    // MARK: - syncChannels (pipelined)

    @Test
    fun `syncChannels pipelined persists received channels and reconciles missing`() = runTest {
        session.getChannelsResult = ChannelsFetchResult(
            received = listOf(channelInfo(0u, name = "A")),
            missing = listOf(1u),
        )
        session.channels[1u] = channelInfo(1u, name = "B")

        val result = service.syncChannels(radioID, maxChannels = 2u, usePipelinedRead = true)

        assertEquals(2, result.channelsSynced)
        assertEquals(2, service.getChannels(radioID).size)
    }

    // MARK: - retryFailedChannels

    @Test
    fun `retryFailedChannels persists channels that now succeed`() = runTest {
        session.channels[3u] = channelInfo(3u, name = "Recovered")

        val result = service.retryFailedChannels(radioID, listOf(3u))

        assertEquals(1, result.channelsSynced)
        assertEquals("Recovered", service.getChannel(radioID, 3u)?.name)
    }

    @Test
    fun `retryFailedChannels with no indices is a no-op`() = runTest {
        val result = service.retryFailedChannels(radioID, emptyList())
        assertEquals(0, result.channelsSynced)
        assertTrue(result.errors.isEmpty())
    }
}

/** Hand-written [ChannelSessionOps] test double — see [ChannelServiceTest]'s class doc. */
private class FakeChannelSessionOps : ChannelSessionOps {
    val channels = mutableMapOf<UByte, ChannelInfo>()
    val errorsByIndex = mutableMapOf<UByte, MeshCoreError>()

    /** Number of remaining calls to [getChannel] for this index that should time out before succeeding. */
    val timeoutCountByIndex = mutableMapOf<UByte, Int>()
    val getChannelCallCount = mutableMapOf<UByte, Int>()

    val setChannelCalls = mutableListOf<Triple<UByte, String, ByteArray>>()

    var getChannelsResult: ChannelsFetchResult? = null

    /** When true, [getChannels] suspends on [gate] until [releaseGetChannels] is called. */
    var holdGetChannels = false
    private val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val entered = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun awaitGetChannelsEntered() = entered.await()

    fun releaseGetChannels() {
        gate.complete(Unit)
    }

    override suspend fun getChannel(index: UByte): ChannelInfo {
        getChannelCallCount[index] = (getChannelCallCount[index] ?: 0) + 1

        errorsByIndex[index]?.let { throw it }

        val remaining = timeoutCountByIndex[index] ?: 0
        if (remaining > 0) {
            timeoutCountByIndex[index] = remaining - 1
            throw MeshCoreError.Timeout
        }

        return channels[index] ?: ChannelInfo(index, "", ByteArray(16))
    }

    override suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult {
        if (holdGetChannels) {
            entered.complete(Unit)
            gate.await()
        }
        return getChannelsResult ?: ChannelsFetchResult(received = emptyList(), missing = emptyList())
    }

    override suspend fun setChannel(index: UByte, name: String, secret: ByteArray) {
        setChannelCalls.add(Triple(index, name, secret))
    }
}
