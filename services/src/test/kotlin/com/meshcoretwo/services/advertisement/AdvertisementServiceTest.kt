// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.advertisement

import androidx.room.Room
import com.meshcoretwo.protocol.AdvertisementSessionOps
import com.meshcoretwo.protocol.ConnectionState
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.DeviceStore
import com.meshcoretwo.services.persistence.DiscoveredNodeStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.utilities.VContactIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Exercises [AdvertisementService] against real in-memory [ContactStore]/[DeviceStore] (Room, via
 * Robolectric) and [FakeAdvertisementSession], a hand-written [AdvertisementSessionOps] test
 * double. The event listener and delta-sync scheduler run on the service's own real
 * `Dispatchers.Default` scope (not `runTest`'s virtual scheduler — see [IncomingMessageServiceTest]
 * for the same reasoning), so event-driven assertions poll with a real, short timeout instead of
 * relying on virtual-time advancement. Debounce/min-interval/busy-backoff are configured small
 * (single-digit milliseconds) to keep that polling fast without being flaky.
 */
@RunWith(RobolectricTestRunner::class)
class AdvertisementServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeAdvertisementSession
    private lateinit var contactStore: ContactStore
    private lateinit var deviceStore: DeviceStore
    private lateinit var discoveredNodeStore: DiscoveredNodeStore
    private lateinit var service: AdvertisementService
    private val radioID = UUID.randomUUID()
    private val selfPublicKey = ByteArray(32) { 0xAA.toByte() }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeAdvertisementSession()
        contactStore = ContactStore(database)
        deviceStore = DeviceStore(database)
        discoveredNodeStore = DiscoveredNodeStore(database)
        service = AdvertisementService(session, contactStore, deviceStore, discoveredNodeStore, debounceMs = 5, minIntervalMs = 10, busyBackoffMs = 5)
    }

    @After
    fun tearDown() = runTest {
        service.stopEventMonitoring()
        database.close()
    }

    private fun meshContact(publicKey: ByteArray, name: String = "Alice") = MeshContact(
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

    private fun deviceDto(publicKey: ByteArray = selfPublicKey) = DeviceDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        publicKey = publicKey,
        nodeName = "Self",
        firmwareVersion = 1u,
        firmwareVersionString = "1.0",
        manufacturerName = "Test",
        buildDate = "2026-01-01",
        maxContacts = 100u,
        maxChannels = 8u,
        frequency = 915_000_000u,
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
    )

    /** Polls a real (non-virtual) timeout since the service's listener/scheduler run on a real dispatcher. */
    private suspend fun awaitUntil(timeoutMs: Long = 10_000, intervalMs: Long = 10, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    // MARK: - Outbound Facade

    @Test
    fun `sendSelfAdvertisement calls the session`() = runTest {
        service.sendSelfAdvertisement(flood = true)
        assertEquals(1, session.sentAdvertisements)
        assertTrue(session.lastAdvertisementFlood!!)
    }

    @Test
    fun `sendSelfAdvertisement wraps a session failure`() = runTest {
        session.sendAdvertisementError = MeshCoreError.Timeout
        try {
            service.sendSelfAdvertisement()
            fail("expected AdvertisementError.SessionError")
        } catch (error: AdvertisementError.SessionError) {
            assertEquals(MeshCoreError.Timeout, error.error)
        }
    }

    @Test
    fun `setAdvertName sets the device name`() = runTest {
        service.setAdvertName("New Name")
        assertEquals("New Name", session.lastName)
    }

    @Test
    fun `setAdvertLocation sets coordinates`() = runTest {
        service.setAdvertLocation(12.5, -34.5)
        assertEquals(12.5 to -34.5, session.lastCoordinates)
    }

    // MARK: - Event Handling

    @Test
    fun `advertisement event for a known contact stamps lastHeardTimestamp and emits ContactUpdated`() = runTest {
        val publicKey = ByteArray(32) { 0x11 }
        val (id, _) = contactStore.saveContact(radioID, meshContact(publicKey))
        val received = CopyOnWriteArrayList<AdvertisementEvent>()
        val collector = collectEvents(received)

        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(publicKey))

        awaitUntil { received.contains(AdvertisementEvent.ContactUpdated) }
        awaitUntil { contactStore.fetchContact(id)!!.lastHeardTimestamp > 0u }
        collector.cancel()
    }

    @Test
    fun `advertisement event for an unknown contact records a pending key without creating a row`() = runTest {
        val publicKey = ByteArray(32) { 0x22 }
        session.contactToReturn = meshContact(publicKey, name = "Discovered")

        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(publicKey))

        // No local row exists yet; the pending key materializes into a real contact on request.
        awaitUntil { service.materializeContactForPendingAdvert(publicKey.copyOf(6), radioID) != null }
        assertTrue(contactStore.fetchContacts(radioID).any { it.publicKey.contentEquals(publicKey) })
    }

    @Test
    fun `contactDeleted event deletes the contact and emits cleanup then storage-not-full then updated`() = runTest {
        val publicKey = ByteArray(32) { 0x33 }
        val (id, _) = contactStore.saveContact(radioID, meshContact(publicKey))
        deviceStore.saveDevice(deviceDto())
        val received = CopyOnWriteArrayList<AdvertisementEvent>()
        val collector = collectEvents(received)

        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.ContactDeleted(publicKey))

        awaitUntil { contactStore.fetchContact(id) == null }
        awaitUntil { received.size >= 3 }
        collector.cancel()

        assertTrue(received[0] is AdvertisementEvent.ContactDeletedCleanup)
        assertEquals(AdvertisementEvent.NodeStorageFullChanged(isFull = false), received[1])
        assertEquals(AdvertisementEvent.ContactUpdated, received[2])
    }

    @Test
    fun `contactDeleted event for the V-contact preserves the local row and emits nothing`() = runTest {
        val vKey = VContactIdentity.publicKey(selfPublicKey)!!
        val (id, _) = contactStore.saveContact(radioID, meshContact(vKey, name = "V"))
        deviceStore.saveDevice(deviceDto())
        val received = CopyOnWriteArrayList<AdvertisementEvent>()
        val collector = collectEvents(received)

        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.ContactDeleted(vKey))
        Thread.sleep(100)

        assertTrue("V-contact row must survive an overwrite-oldest push", contactStore.fetchContact(id) != null)
        assertTrue(received.isEmpty())
        collector.cancel()
    }

    @Test
    fun `newContact event upserts a Discover row and emits NewContactDiscovered`() = runTest {
        val publicKey = ByteArray(32) { 0xC1.toByte() }
        val received = CopyOnWriteArrayList<AdvertisementEvent>()
        val collector = collectEvents(received)

        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.NewContact(meshContact(publicKey, name = "Manual")))

        awaitUntil { received.any { it is AdvertisementEvent.NewContactDiscovered } }
        assertTrue(received.contains(AdvertisementEvent.ContactUpdated))
        val node = discoveredNodeStore.fetchDiscoveredNodes(radioID).single { it.publicKey.contentEquals(publicKey) }
        assertEquals("Manual", node.name)
        collector.cancel()
    }

    @Test
    fun `a repeat newContact event for the same key does not re-notify`() = runTest {
        val publicKey = ByteArray(32) { 0xC2.toByte() }
        val received = CopyOnWriteArrayList<AdvertisementEvent>()
        val collector = collectEvents(received)

        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.NewContact(meshContact(publicKey, name = "Manual")))
        awaitUntil { received.any { it is AdvertisementEvent.NewContactDiscovered } }

        session.emit(MeshEvent.NewContact(meshContact(publicKey, name = "Manual Renamed")))
        Thread.sleep(100)

        assertEquals(1, received.filterIsInstance<AdvertisementEvent.NewContactDiscovered>().size)
        collector.cancel()
    }

    @Test
    fun `0x8A then 0x80 for the same key notifies from each path`() = runTest {
        val publicKey = ByteArray(32) { 0xC4.toByte() }
        service.setDeltaSyncHandler {
            contactStore.saveContact(radioID, meshContact(publicKey, name = "Manual"))
            AdvertContactSyncOutcome.SYNCED
        }
        val received = CopyOnWriteArrayList<AdvertisementEvent>()
        val collector = collectEvents(received)

        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.NewContact(meshContact(publicKey, name = "Manual")))
        awaitUntil { received.count { it is AdvertisementEvent.NewContactDiscovered } >= 1 }

        // 0x8A left a Discover row but no Contact row, so the delta round's pre-round snapshot
        // still lacks the key; once the handler persists it, it lands in insertedKeys and is
        // announced separately from the 0x8A path.
        session.emit(MeshEvent.Advertisement(publicKey))
        awaitUntil { received.count { it is AdvertisementEvent.NewContactDiscovered } >= 2 }

        collector.cancel()
    }

    @Test
    fun `contactsFull event emits NodeStorageFullChanged true`() = runTest {
        val received = CopyOnWriteArrayList<AdvertisementEvent>()
        val collector = collectEvents(received)

        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.ContactsFull)

        awaitUntil { received.contains(AdvertisementEvent.NodeStorageFullChanged(isFull = true)) }
        collector.cancel()
    }

    // MARK: - Pending-Advert Materialization

    @Test
    fun `materializeContactForPendingAdvert with an unambiguous prefix creates the contact`() = runTest {
        val publicKey = ByteArray(32) { 0x44 }
        session.contactToReturn = meshContact(publicKey, name = "Materialized")
        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(publicKey))
        awaitUntil { service.materializeContactForPendingAdvert(publicKey.copyOf(6), radioID) != null }
    }

    @Test
    fun `materializeContactForPendingAdvert also upserts a Discover row`() = runTest {
        val publicKey = ByteArray(32) { 0x45 }
        session.contactToReturn = meshContact(publicKey, name = "Materialized")
        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(publicKey))
        awaitUntil { service.materializeContactForPendingAdvert(publicKey.copyOf(6), radioID) != null }

        val node = discoveredNodeStore.fetchDiscoveredNodes(radioID).singleOrNull { it.publicKey.contentEquals(publicKey) }
        assertTrue(node != null)
        assertEquals("Materialized", node!!.name)
    }

    @Test
    fun `materializeContactForPendingAdvert with no pending match returns null`() = runTest {
        assertNull(service.materializeContactForPendingAdvert(ByteArray(6) { 0x55 }, radioID))
    }

    @Test
    fun `materializeContactForPendingAdvert with an ambiguous prefix returns null`() = runTest {
        val prefix = ByteArray(6) { 0x66 }
        val keyA = prefix + ByteArray(26) { 0x01 }
        val keyB = prefix + ByteArray(26) { 0x02 }
        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(keyA))
        session.emit(MeshEvent.Advertisement(keyB))

        awaitUntil { service.materializeContactForPendingAdvert(ByteArray(0), radioID) == null }
        // Give both adverts a moment to register as pending before asserting the ambiguous case.
        Thread.sleep(50)
        assertNull(service.materializeContactForPendingAdvert(prefix, radioID))
    }

    // MARK: - Delta Sync

    @Test
    fun `delta sync debounces multiple adverts into one handler call`() = runTest {
        // A wide window instead of the fixture's 5 ms: on a slow CI runner two emits can land further
        // apart than 5 ms, which would (correctly) trigger two rounds and make this test flaky.
        val debouncing = AdvertisementService(session, contactStore, deviceStore, discoveredNodeStore, debounceMs = 1_000, minIntervalMs = 10, busyBackoffMs = 5)
        var handlerCalls = 0
        debouncing.setDeltaSyncHandler {
            handlerCalls++
            AdvertContactSyncOutcome.SYNCED
        }
        val keyA = ByteArray(32) { 0x71 }
        val keyB = ByteArray(32) { 0x72 }

        debouncing.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(keyA))
        session.emit(MeshEvent.Advertisement(keyB))

        awaitUntil { handlerCalls >= 1 }
        Thread.sleep(300) // nothing further may follow: both adverts coalesced into the one round
        assertEquals(1, handlerCalls)
        debouncing.stopEventMonitoring()
    }

    @Test
    fun `busy outcome requeues drained work and retries`() = runTest {
        var handlerCalls = 0
        service.setDeltaSyncHandler {
            handlerCalls++
            if (handlerCalls == 1) AdvertContactSyncOutcome.BUSY else AdvertContactSyncOutcome.SYNCED
        }
        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(ByteArray(32) { 0x81.toByte() }))

        awaitUntil { handlerCalls >= 2 }
    }

    @Test
    fun `notReady outcome drops pending work without retry`() = runTest {
        var handlerCalls = 0
        service.setDeltaSyncHandler {
            handlerCalls++
            AdvertContactSyncOutcome.NOT_READY
        }
        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(ByteArray(32) { 0x82.toByte() }))

        awaitUntil { handlerCalls >= 1 }
        Thread.sleep(100)
        assertEquals(1, handlerCalls)
    }

    @Test
    fun `synced outcome emits NewContactDiscovered for a key the pre-round snapshot proves is new`() = runTest {
        val publicKey = ByteArray(32) { 0x91.toByte() }
        service.setDeltaSyncHandler {
            // Simulate the handler's real-world effect: the contact now exists locally.
            contactStore.saveContact(radioID, meshContact(publicKey, name = "Newly Synced"))
            AdvertContactSyncOutcome.SYNCED
        }
        val received = CopyOnWriteArrayList<AdvertisementEvent>()
        val collector = collectEvents(received)

        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(publicKey))

        awaitUntil { received.any { it is AdvertisementEvent.NewContactDiscovered } }
        val event = received.filterIsInstance<AdvertisementEvent.NewContactDiscovered>().single()
        assertEquals("Newly Synced", event.name)
        collector.cancel()
    }

    @Test
    fun `a successful delta sync round refreshes the Discover row for the drained key`() = runTest {
        val publicKey = ByteArray(32) { 0x92.toByte() }
        service.setDeltaSyncHandler {
            contactStore.saveContact(radioID, meshContact(publicKey, name = "Synced Node"))
            AdvertContactSyncOutcome.SYNCED
        }
        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(publicKey))

        awaitUntil { discoveredNodeStore.fetchDiscoveredNodes(radioID).any { it.publicKey.contentEquals(publicKey) } }
        val node = discoveredNodeStore.fetchDiscoveredNodes(radioID).single { it.publicKey.contentEquals(publicKey) }
        assertEquals("Synced Node", node.name)
    }

    @Test
    fun `setSyncingContacts false resumes work deferred while true`() = runTest {
        var handlerCalls = 0
        service.setDeltaSyncHandler {
            handlerCalls++
            AdvertContactSyncOutcome.SYNCED
        }
        service.setSyncingContacts(true)
        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(ByteArray(32) { 0xA1.toByte() }))
        Thread.sleep(100)
        assertEquals("No sync should run while a full/manual sync is in progress", 0, handlerCalls)

        service.setSyncingContacts(false)

        awaitUntil { handlerCalls >= 1 }
    }

    @Test
    fun `stopEventMonitoring clears pending advert keys`() = runTest {
        val publicKey = ByteArray(32) { 0xB1.toByte() }
        service.startEventMonitoring(radioID)
        session.emit(MeshEvent.Advertisement(publicKey))
        awaitUntil { service.materializeContactForPendingAdvert(publicKey.copyOf(6), radioID) != null || true }

        service.stopEventMonitoring()

        assertFalse(session.eventsFlow.subscriptionCount.value > 0)
        assertNull(service.materializeContactForPendingAdvert(publicKey.copyOf(6), radioID))
    }

    private fun collectEvents(into: CopyOnWriteArrayList<AdvertisementEvent>): kotlinx.coroutines.Job {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        return scope.launch { service.events().collect { into.add(it) } }
    }
}

/** Hand-written [AdvertisementSessionOps] test double — see [AdvertisementServiceTest]'s class doc. */
private class FakeAdvertisementSession : AdvertisementSessionOps {
    var contactToReturn: MeshContact? = null
    var sentAdvertisements = 0
    var lastAdvertisementFlood: Boolean? = null
    var lastName: String? = null
    var lastCoordinates: Pair<Double, Double>? = null
    var sendAdvertisementError: MeshCoreError? = null

    val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 16)

    /**
     * Waits for a real subscriber before emitting: the service's event listener runs on its own
     * real `Dispatchers.Default` coroutine and needs a moment to reach `collect()` after
     * `startEventMonitoring` returns, and `MutableSharedFlow` has no replay for a subscriber that
     * arrives late — a real-time wait (not `delay()`, which no-ops under `runTest`'s virtual
     * scheduler) avoids losing the event to that race.
     */
    suspend fun emit(event: MeshEvent) {
        val deadline = System.currentTimeMillis() + 2000
        while (eventsFlow.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(1)
        }
        eventsFlow.emit(event)
    }

    override suspend fun sendAdvertisement(flood: Boolean) {
        sendAdvertisementError?.let { throw it }
        sentAdvertisements++
        lastAdvertisementFlood = flood
    }

    override suspend fun setName(name: String) {
        lastName = name
    }

    override suspend fun setCoordinates(latitude: Double, longitude: Double) {
        lastCoordinates = latitude to longitude
    }

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = contactToReturn

    override val connectionState: Flow<ConnectionState> get() = error("not used by this vertical slice")

    override suspend fun events(): Flow<MeshEvent> = eventsFlow

    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = eventsFlow.filter { filter.matches(it) }

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? =
        error("not used by this vertical slice")
}
