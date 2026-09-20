// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.tracepath

import androidx.room.Room
import com.meshcoretwo.protocol.ACLResponse
import com.meshcoretwo.protocol.BinaryProtocolSessionOps
import com.meshcoretwo.protocol.ConnectionState
import com.meshcoretwo.protocol.ContactFetchResult
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MMAResponse
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.NeighboursResponse
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.protocol.TelemetryResponse
import com.meshcoretwo.protocol.TraceInfo
import com.meshcoretwo.protocol.TraceNode
import com.meshcoretwo.services.diagnostics.BinaryProtocolService
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.TracePathStore
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

/**
 * Exercises [TracePathService]'s two thin halves: CRUD passthrough to a real (in-memory Room)
 * [TracePathStore] — already covered in depth by `TracePathStoreTest`, so only spot-checked here —
 * and the [TracePathService.traceEvents] relay off a real [BinaryProtocolService] against a fake
 * session, matching [com.meshcoretwo.services.diagnostics.BinaryProtocolServiceTest]'s pattern.
 */
@RunWith(RobolectricTestRunner::class)
class TracePathServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeTraceSessionOps
    private lateinit var service: TracePathService
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeTraceSessionOps()
        service = TracePathService(BinaryProtocolService(session), TracePathStore(database))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `createSavedTracePath then fetchSavedTracePaths round-trips through the real store`() = runTest {
        service.createSavedTracePath(radioID, "Tower", byteArrayOf(1, 2), hashSize = 1, initialRun = null)

        val paths = service.fetchSavedTracePaths(radioID)

        assertEquals(listOf("Tower"), paths.map { it.name })
    }

    @Test
    fun `sendTrace forwards to the session`() = runTest {
        session.sentInfo = MessageSentInfo(route = 0u, expectedAck = byteArrayOf(), suggestedTimeoutMs = 1000u)

        service.sendTrace(tag = 11u, authCode = 22u, flags = 0u, path = byteArrayOf(1))

        assertEquals(11u.toUInt(), session.lastTraceTag)
    }

    @Test
    @OptIn(DelicateCoroutinesApi::class)
    fun `traceEvents relays TraceData pushed through the underlying session, not a synchronous reply to sendTrace`() = runTest {
        val received = mutableListOf<TraceInfo>()
        val collector = GlobalScope.launch(Dispatchers.Default) { service.traceEvents.collect { received.add(it) } }

        service.startEventMonitoring()
        Thread.sleep(100)

        session.emit(MeshEvent.TraceData(TraceInfo(tag = 5u, authCode = 0u, flags = 0u, pathLength = 0u, path = listOf(TraceNode(hashBytes = null, snr = 1.0)))))

        val deadline = System.currentTimeMillis() + 2000
        while (received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals(listOf(5u.toUInt()), received.map { it.tag })
        collector.cancel()
        service.stopEventMonitoring()
    }
}

/** Minimal [BinaryProtocolSessionOps] fake — only [sendTrace]/[events] are exercised by this test. */
private class FakeTraceSessionOps : BinaryProtocolSessionOps {
    lateinit var sentInfo: MessageSentInfo
    var lastTraceTag: UInt? = null

    private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)

    suspend fun emit(event: MeshEvent) = eventsFlow.emit(event)

    override suspend fun sendTrace(tag: UInt?, authCode: UInt?, flags: UByte, path: ByteArray?): MessageSentInfo {
        lastTraceTag = tag
        return sentInfo
    }

    override suspend fun requestStatus(publicKey: ByteArray): StatusResponse = error("not used by this test")

    override suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse = error("not used by this test")

    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse = error("not used by this test")

    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: UByte,
        offset: UShort,
        orderBy: UByte,
        pubkeyPrefixLength: UByte,
    ): NeighboursResponse = error("not used by this test")

    override suspend fun fetchAllNeighbours(publicKey: ByteArray, orderBy: UByte, pubkeyPrefixLength: UByte): NeighboursResponse =
        error("not used by this test")

    override suspend fun requestMMA(publicKey: ByteArray, start: Instant, end: Instant): MMAResponse = error("not used by this test")

    override suspend fun requestACL(publicKey: ByteArray): ACLResponse = error("not used by this test")

    override suspend fun getSelfTelemetry(): TelemetryResponse = error("not used by this test")

    override suspend fun getContacts(since: Instant?): List<MeshContact> = error("not used by this test")

    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult = error("not used by this test")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = error("not used by this test")

    override suspend fun addContact(contact: MeshContact) = error("not used by this test")

    override suspend fun removeContact(publicKey: ByteArray) = error("not used by this test")

    override suspend fun resetPath(publicKey: ByteArray) = error("not used by this test")

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo = error("not used by this test")

    override suspend fun shareContact(publicKey: ByteArray) = error("not used by this test")

    override suspend fun exportContact(publicKey: ByteArray?): String = error("not used by this test")

    override suspend fun importContact(cardData: ByteArray) = error("not used by this test")

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = error("not used by this test")

    override val connectionState: Flow<ConnectionState>
        get() = error("not used by this test")

    override suspend fun events(): Flow<MeshEvent> = eventsFlow

    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = error("not used by this test")

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = error("not used by this test")
}
