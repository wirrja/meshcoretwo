// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.diagnostics

import com.meshcoretwo.protocol.ACLResponse
import com.meshcoretwo.protocol.BinaryProtocolSessionOps
import com.meshcoretwo.protocol.ConnectionState
import com.meshcoretwo.protocol.ContactFetchResult
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MMAResponse
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.NeighboursResponse
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.protocol.TelemetryResponse
import com.meshcoretwo.protocol.TraceInfo
import com.meshcoretwo.protocol.TraceNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Exercises [BinaryProtocolService] against [FakeBinaryProtocolSessionOps], a hand-written test
 * double for the [BinaryProtocolSessionOps] role interface — the same pattern
 * [com.meshcoretwo.services.contacts.ContactServiceTest] established. No persistence store is
 * involved (this service is a pure session wrapper, like [com.meshcoretwo.services.settings.SettingsService]),
 * so this test needs neither Room nor Robolectric.
 */
class BinaryProtocolServiceTest {
    private lateinit var session: FakeBinaryProtocolSessionOps
    private lateinit var service: BinaryProtocolService
    private val publicKey = ByteArray(32) { it.toByte() }

    private fun statusResponse(battery: Int = 3700) = StatusResponse(
        publicKeyPrefix = publicKey.copyOf(6),
        battery = battery,
        txQueueLength = 0,
        noiseFloor = -100,
        lastRSSI = -80,
        packetsReceived = 1u,
        packetsSent = 2u,
        airtime = 3u,
        uptime = 4u,
        sentFlood = 5u,
        sentDirect = 6u,
        receivedFlood = 7u,
        receivedDirect = 8u,
        fullEvents = 0,
        lastSNR = 5.0,
        directDuplicates = 0,
        floodDuplicates = 0,
        rxAirtime = 9u,
    )

    @Before
    fun setUp() {
        session = FakeBinaryProtocolSessionOps()
        service = BinaryProtocolService(session)
    }

    @Test
    fun `requestStatus returns the session's status response`() = runTest {
        session.status = statusResponse(battery = 4100)
        assertEquals(4100, service.requestStatus(publicKey).battery)
    }

    @Test
    fun `requestStatus with a type forwards it to the session`() = runTest {
        session.status = statusResponse()
        service.requestStatus(publicKey, ContactType.REPEATER)
        assertEquals(ContactType.REPEATER, session.lastRequestStatusType)
    }

    @Test
    fun `requestTelemetry returns the session's telemetry response`() = runTest {
        session.telemetry = TelemetryResponse(publicKeyPrefix = publicKey.copyOf(6), tag = null, rawData = byteArrayOf(1))
        assertTrue(service.requestTelemetry(publicKey).rawData.contentEquals(byteArrayOf(1)))
    }

    @Test
    fun `requestNeighbours defaults the pubkey prefix length to 6, not the session's own default of 4`() = runTest {
        session.neighbours = NeighboursResponse(publicKeyPrefix = publicKey.copyOf(6), tag = byteArrayOf(), totalCount = 0, neighbours = emptyList())
        service.requestNeighbours(publicKey)
        assertEquals(BinaryProtocolService.DEFAULT_PUBKEY_PREFIX_LENGTH, session.lastPubkeyPrefixLength)
        assertEquals(6u.toUByte(), session.lastPubkeyPrefixLength)
    }

    @Test
    fun `fetchAllNeighbours forwards orderBy and pubkeyPrefixLength`() = runTest {
        session.neighbours = NeighboursResponse(publicKeyPrefix = publicKey.copyOf(6), tag = byteArrayOf(), totalCount = 0, neighbours = emptyList())
        service.fetchAllNeighbours(publicKey, orderBy = 1u, pubkeyPrefixLength = 8u)
        assertEquals(1u.toUByte(), session.lastOrderBy)
        assertEquals(8u.toUByte(), session.lastPubkeyPrefixLength)
    }

    @Test
    fun `requestMMA forwards the time range`() = runTest {
        session.mma = MMAResponse(publicKeyPrefix = publicKey.copyOf(6), tag = byteArrayOf(), data = emptyList())
        val start = Instant.ofEpochSecond(1000)
        val end = Instant.ofEpochSecond(2000)
        service.requestMMA(publicKey, start, end)
        assertEquals(start to end, session.lastMmaRange)
    }

    @Test
    fun `requestACL returns the session's ACL response`() = runTest {
        session.acl = ACLResponse(publicKeyPrefix = publicKey.copyOf(6), tag = byteArrayOf(), entries = emptyList())
        assertTrue(service.requestACL(publicKey).entries.isEmpty())
    }

    @Test
    fun `getSelfTelemetry returns the session's local telemetry`() = runTest {
        session.selfTelemetry = TelemetryResponse(publicKeyPrefix = ByteArray(0), tag = null, rawData = byteArrayOf(9))
        assertTrue(service.getSelfTelemetry().rawData.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun `sendPathDiscovery returns the session's sent-message info`() = runTest {
        session.sentInfo = MessageSentInfo(route = 1u, expectedAck = byteArrayOf(1, 2), suggestedTimeoutMs = 5000u)
        assertEquals(1u.toUByte(), service.sendPathDiscovery(publicKey).route)
    }

    @Test
    fun `sendTrace forwards its arguments`() = runTest {
        session.sentInfo = MessageSentInfo(route = 0u, expectedAck = byteArrayOf(), suggestedTimeoutMs = 1000u)
        service.sendTrace(tag = 42u, authCode = 7u, flags = 1u, path = byteArrayOf(1))
        assertEquals(42u.toUInt(), session.lastTraceTag)
        assertEquals(7u.toUInt(), session.lastTraceAuthCode)
    }

    // MARK: - Path reset on timeout

    @Test
    fun `a non-timeout session error is wrapped without resetting the path`() = runTest {
        session.statusError = MeshCoreError.InvalidInput("bad request")

        try {
            service.requestStatus(publicKey)
            fail("expected BinaryProtocolError.SessionError")
        } catch (error: BinaryProtocolError.SessionError) {
            assertTrue(error.error is MeshCoreError.InvalidInput)
        }
        assertFalse(session.resetPathCalled)
    }

    @Test
    fun `a timeout resets the path and retries once, succeeding on the retry`() = runTest {
        session.statusError = MeshCoreError.Timeout
        session.failStatusOnce = true
        session.status = statusResponse(battery = 4200)

        val result = service.requestStatus(publicKey)

        assertTrue(session.resetPathCalled)
        assertEquals(4200, result.battery)
    }

    @Test
    fun `when resetPath itself fails, the original timeout is what's thrown`() = runTest {
        session.statusError = MeshCoreError.Timeout
        session.failStatusOnce = true
        session.resetPathError = MeshCoreError.NotConnected

        try {
            service.requestStatus(publicKey)
            fail("expected BinaryProtocolError.SessionError")
        } catch (error: BinaryProtocolError.SessionError) {
            assertTrue(error.error is MeshCoreError.Timeout)
        }
    }

    @Test
    fun `when the retry also fails, that error is what's thrown`() = runTest {
        session.statusError = MeshCoreError.Timeout
        // failStatusOnce left false: every call keeps timing out, including the retry.

        try {
            service.requestStatus(publicKey)
            fail("expected BinaryProtocolError.SessionError")
        } catch (error: BinaryProtocolError.SessionError) {
            assertTrue(error.error is MeshCoreError.Timeout)
        }
        assertTrue(session.resetPathCalled)
    }

    // MARK: - Event monitoring

    @Test
    fun `startEventMonitoring dispatches status telemetry and neighbours responses to their handlers`() = runTest {
        val statusReceived = mutableListOf<StatusResponse>()
        val telemetryReceived = mutableListOf<TelemetryResponse>()
        val neighboursReceived = mutableListOf<NeighboursResponse>()
        service.setStatusResponseHandler { statusReceived.add(it) }
        service.setTelemetryResponseHandler { telemetryReceived.add(it) }
        service.setNeighboursResponseHandler { neighboursReceived.add(it) }

        // The listener runs on the service's own real Dispatchers.Default scope (see
        // AdvertisementServiceTest for the same reasoning), so give it a moment to actually
        // subscribe before emitting — a SharedFlow with no replay drops events emitted before
        // a collector attaches.
        service.startEventMonitoring()
        Thread.sleep(100)

        session.emit(MeshEvent.StatusResponseEvent(statusResponse()))
        session.emit(MeshEvent.TelemetryResponseEvent(TelemetryResponse(publicKeyPrefix = ByteArray(0), tag = null, rawData = ByteArray(0))))
        session.emit(MeshEvent.NeighboursResponseEvent(NeighboursResponse(publicKeyPrefix = ByteArray(0), tag = byteArrayOf(), totalCount = 0, neighbours = emptyList())))

        awaitUntil { statusReceived.size == 1 && telemetryReceived.size == 1 && neighboursReceived.size == 1 }

        service.stopEventMonitoring()
    }

    @Test
    fun `startEventMonitoring dispatches trace responses to their handler, not synchronously from sendTrace`() = runTest {
        val traceReceived = mutableListOf<TraceInfo>()
        service.setTraceResponseHandler { traceReceived.add(it) }

        service.startEventMonitoring()
        Thread.sleep(100)

        val info = TraceInfo(tag = 42u, authCode = 7u, flags = 0u, pathLength = 1u, path = listOf(TraceNode(hashBytes = null, snr = 5.0)))
        session.emit(MeshEvent.TraceData(info))

        awaitUntil { traceReceived.size == 1 }
        assertEquals(42u.toUInt(), traceReceived.single().tag)

        service.stopEventMonitoring()
    }

    @Test
    fun `stopEventMonitoring stops delivering events to handlers`() = runTest {
        val statusReceived = mutableListOf<StatusResponse>()
        service.setStatusResponseHandler { statusReceived.add(it) }

        service.startEventMonitoring()
        Thread.sleep(100)
        service.stopEventMonitoring()

        session.emit(MeshEvent.StatusResponseEvent(statusResponse()))
        Thread.sleep(100)

        assertTrue(statusReceived.isEmpty())
    }

    /** Polls a real (non-virtual) timeout since the event listener runs on a real dispatcher. */
    private suspend fun awaitUntil(timeoutMs: Long = 2000, intervalMs: Long = 10, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }
}

private class FakeBinaryProtocolSessionOps : BinaryProtocolSessionOps {
    lateinit var status: StatusResponse
    lateinit var telemetry: TelemetryResponse
    lateinit var neighbours: NeighboursResponse
    lateinit var mma: MMAResponse
    lateinit var acl: ACLResponse
    lateinit var selfTelemetry: TelemetryResponse
    lateinit var sentInfo: MessageSentInfo

    var statusError: MeshCoreError? = null
    var resetPathError: MeshCoreError? = null

    /** When true, the *next* status request fails with [statusError]; the one after succeeds. */
    var failStatusOnce = false
    private var statusFailurePending = false

    var resetPathCalled = false
    var lastRequestStatusType: ContactType? = null
    var lastPubkeyPrefixLength: UByte? = null
    var lastOrderBy: UByte? = null
    var lastMmaRange: Pair<Instant, Instant>? = null
    var lastTraceTag: UInt? = null
    var lastTraceAuthCode: UInt? = null

    private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)

    suspend fun emit(event: MeshEvent) = eventsFlow.emit(event)

    private fun maybeThrowStatusError() {
        val error = statusError ?: return
        if (failStatusOnce) {
            if (!statusFailurePending) {
                statusFailurePending = true
                throw error
            }
            // The retry succeeds.
        } else {
            throw error
        }
    }

    override suspend fun requestStatus(publicKey: ByteArray): StatusResponse {
        maybeThrowStatusError()
        return status
    }

    override suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse {
        lastRequestStatusType = type
        maybeThrowStatusError()
        return status
    }

    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse = telemetry

    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: UByte,
        offset: UShort,
        orderBy: UByte,
        pubkeyPrefixLength: UByte,
    ): NeighboursResponse {
        lastOrderBy = orderBy
        lastPubkeyPrefixLength = pubkeyPrefixLength
        return neighbours
    }

    override suspend fun fetchAllNeighbours(publicKey: ByteArray, orderBy: UByte, pubkeyPrefixLength: UByte): NeighboursResponse {
        lastOrderBy = orderBy
        lastPubkeyPrefixLength = pubkeyPrefixLength
        return neighbours
    }

    override suspend fun requestMMA(publicKey: ByteArray, start: Instant, end: Instant): MMAResponse {
        lastMmaRange = start to end
        return mma
    }

    override suspend fun requestACL(publicKey: ByteArray): ACLResponse = acl

    override suspend fun getSelfTelemetry(): TelemetryResponse = selfTelemetry

    override suspend fun sendTrace(tag: UInt?, authCode: UInt?, flags: UByte, path: ByteArray?): MessageSentInfo {
        lastTraceTag = tag
        lastTraceAuthCode = authCode
        return sentInfo
    }

    // MARK: - ContactSessionOps (only resetPath/sendPathDiscovery are exercised)

    override suspend fun getContacts(since: Instant?): List<MeshContact> = error("not used by this vertical slice")

    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult = error("not used by this vertical slice")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = error("not used by this vertical slice")

    override suspend fun addContact(contact: MeshContact) = error("not used by this vertical slice")

    override suspend fun removeContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun resetPath(publicKey: ByteArray) {
        resetPathCalled = true
        resetPathError?.let { throw it }
    }

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo = sentInfo

    override suspend fun shareContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun exportContact(publicKey: ByteArray?): String = error("not used by this vertical slice")

    override suspend fun importContact(cardData: ByteArray) = error("not used by this vertical slice")

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = error("not used by this vertical slice")

    // MARK: - SessionEventStreaming

    override val connectionState: Flow<ConnectionState>
        get() = error("not used by this vertical slice")

    override suspend fun events(): Flow<MeshEvent> = eventsFlow

    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = error("not used by this vertical slice")

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = error("not used by this vertical slice")
}
