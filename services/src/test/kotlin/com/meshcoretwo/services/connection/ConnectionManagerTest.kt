// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import android.content.Context
import androidx.room.Room
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.SessionConfiguration
import com.meshcoretwo.services.pairing.DevicePairingError
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.security.KeychainService
import com.meshcoretwo.services.transport.FakeBleStateMachine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

private const val DEVICE_A = "AA:BB:CC:DD:EE:01"

/**
 * Exercises [ConnectionManager] against a real in-memory Room database and a real
 * [com.meshcoretwo.protocol.MeshCoreSession] wired over a [FakeBleMeshTransport] (which itself
 * wraps a real [com.meshcoretwo.protocol.MockTransport]) — the "real service, fake wire" pattern
 * this codebase uses throughout, extended one layer further here because [ConnectionManager]
 * constructs its `MeshCoreSession` directly rather than accepting an injected one (matching
 * Swift). [com.meshcoretwo.services.transport.FakeBleStateMachine] stands in for the BLE state
 * machine seam (see its own class doc).
 *
 * Focuses on what's genuinely [ConnectionManager]-specific and reachable without a live radio:
 * the circuit breaker, `activate()`'s early-exit guards, bond-verification persistence, and one
 * end-to-end happy-path connect (proving the whole slice's wiring — transport, session,
 * `ServiceContainer`, device persistence — actually holds together). Full sync success, the
 * resync retry loop, and auto-reconnect rebuild are not covered here — they'd need a much larger
 * wire-level simulation (contacts/channels/messages responses) than this first pass buys; tracked
 * as a coverage gap in PLAN.md.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionManagerTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var stateMachine: FakeBleStateMachine
    private lateinit var transport: FakeBleMeshTransport
    private lateinit var connectionManager: ConnectionManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = context.getSharedPreferences("connection-manager-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        stateMachine = FakeBleStateMachine()
        transport = FakeBleMeshTransport()

        connectionManager = ConnectionManager(
            context = context,
            database = database,
            prefs = prefs,
            stateMachine = stateMachine,
            transport = transport,
            sessionConfiguration = SessionConfiguration(defaultTimeout = 2.0),
            // Robolectric has no working AndroidKeyStore provider — see ServiceContainer's and
            // ConnectionManager's own "Testing note" docs.
            keychainServiceFactory = {
                KeychainService(context.getSharedPreferences("connection-manager-test-keychain-${UUID.randomUUID()}", Context.MODE_PRIVATE))
            },
        )
    }

    @After
    fun tearDown() {
        // Bounds any background job connect() may have started (e.g. a resync retry loop) —
        // ConnectionManager.scope runs on a real dispatcher, not this test's virtual one, so a
        // leaked job would otherwise keep retrying against a database this closes next.
        connectionManager.scope.cancel()
        database.close()
    }

    // MARK: - Circuit breaker

    @Test
    fun `circuit breaker allows a connection while closed`() {
        assertTrue(connectionManager.shouldAllowConnection(force = false))
    }

    @Test
    fun `circuit breaker blocks after a recorded failure until the cooldown elapses`() {
        connectionManager.recordConnectionFailure()

        assertFalse(connectionManager.shouldAllowConnection(force = false))
        assertTrue(connectionManager.shouldAllowConnection(force = true))
    }

    @Test
    fun `circuit breaker closes again after a recorded success`() {
        connectionManager.recordConnectionFailure()
        connectionManager.recordConnectionSuccess()

        assertTrue(connectionManager.shouldAllowConnection(force = false))
    }

    // MARK: - disconnect() when idle

    @Test
    fun `disconnect when idle is a safe no-op`() = runBlocking {
        connectionManager.disconnect()

        assertEquals(DeviceConnectionState.DISCONNECTED, connectionManager.connectionState)
        assertNull(connectionManager.connectedDevice)
        assertEquals(1, transport.disconnectCallCount)
    }

    // MARK: - activate() early exits

    @Test
    fun `activate does not attempt a connection when the user previously disconnected`() = runBlocking {
        connectionManager.connectionIntent = ConnectionIntent.UserDisconnected
        connectionManager.connectionIntent.persist(prefs)

        connectionManager.activate()

        assertTrue(transport.setDeviceAddressCalls.isEmpty())
        assertEquals(DeviceConnectionState.DISCONNECTED, connectionManager.connectionState)
    }

    @Test
    fun `activate does not attempt a connection when there is no last-connected device`() = runBlocking {
        connectionManager.activate()

        assertTrue(transport.setDeviceAddressCalls.isEmpty())
        assertEquals(DeviceConnectionState.DISCONNECTED, connectionManager.connectionState)
    }

    // MARK: - checkBLEConnectionHealth() early exits

    @Test
    fun `checkBLEConnectionHealth is a no-op when intent does not want a connection`() = runBlocking {
        connectionManager.checkBLEConnectionHealth()

        assertTrue(transport.setDeviceAddressCalls.isEmpty())
    }

    // MARK: - Bond verification persistence

    @Test
    fun `recordBondVerification then clearPersistedConnection round-trips through the state machine and store`() = runBlocking {
        val deviceID = UUID.randomUUID()

        connectionManager.recordBondVerification(deviceID, DEVICE_A)
        assertTrue(stateMachine.hasBondVerification(DEVICE_A))
        assertTrue(stateMachine.isAppSessionLive(DEVICE_A))

        connectionManager.clearPersistedConnection(deviceID)
        // No device row exists for deviceID, so the address lookup finds nothing to clear —
        // this only exercises the epoch bump and last-connection-store clear path here.
        assertTrue(stateMachine.hasBondVerification(DEVICE_A))
    }

    // MARK: - Happy-path connect()

    @Test
    fun `connect establishes a session, persists the device, and reaches at least CONNECTED`() = runBlocking {
        runConnectAndAnswerHandshake(transport.mock) { connectionManager.connect(DEVICE_A) }

        assertEquals(listOf(DEVICE_A), transport.setDeviceAddressCalls)

        val device = connectionManager.connectedDevice
        assertNotNull(device)
        assertEquals(DEVICE_A, device!!.bleAddress)
        assertEquals("TestNode", device.nodeName)
        assertNotNull(connectionManager.services)

        // Sync itself fails (no contacts/channels response simulated — out of scope for this
        // test, see class doc), so promotion lands on SYNCING rather than READY, but the
        // connect ceremony itself must have completed.
        assertEquals(DeviceConnectionState.SYNCING, connectionManager.connectionState)

        val persisted = connectionManager.deviceStore.fetchDeviceById(device.id)
        assertNotNull(persisted)
        assertEquals(DEVICE_A, persisted!!.bleAddress)

        assertEquals(device.id, connectionManager.lastConnectedDeviceID)
        assertTrue(stateMachine.hasBondVerification(DEVICE_A))
    }

    // MARK: - Pairing (slice D)

    @Test
    fun `pairNewDevice rejects re-entry without clearing the outer call's flag`() = runBlocking {
        connectionManager.isPairingInProgress = true

        try {
            connectionManager.pairNewDevice()
            fail("expected DevicePairingError.AlreadyInProgress")
        } catch (e: DevicePairingError.AlreadyInProgress) {
            // expected
        }

        // The inner call's `finally` must not unwind the outer call's state.
        assertTrue(connectionManager.isPairingInProgress)
    }

    @Test
    fun `pairNewDevice discovers, connects, and reaches at least SYNCING`() = runBlocking {
        val pairJob = launch { connectionManager.pairNewDevice() }
        withTimeout(2000) { connectionManager.pairingService.isPresenting.first { it } }

        connectionManager.pairingService.select(DEVICE_A)
        // waitForOtherAppReconnection's up-to-6x400ms polling (stateMachine's default stub always
        // reports "not connected elsewhere") runs before the handshake starts sending, so the
        // generous per-call timeouts below (rather than answerConnectHandshake's tighter default)
        // are needed to not race that ~2s of real-time delay.
        waitUntilSent(transport.mock, minCount = 1, timeoutMs = 5000)
        transport.mock.simulateReceive(makeSelfInfoPacket())
        waitUntilSent(transport.mock, minCount = 2, timeoutMs = 5000)
        transport.mock.simulateReceive(makeDeviceInfoPacket())
        pairJob.join()

        assertFalse(connectionManager.isPairingInProgress)
        val device = connectionManager.connectedDevice
        assertNotNull(device)
        assertEquals(DEVICE_A, device!!.bleAddress)
        assertEquals(DeviceConnectionState.SYNCING, connectionManager.connectionState)
    }

    @Test
    fun `pairNewDevice surfaces cancellation as DevicePairingError Cancelled without leaving isPairingInProgress set`() = runBlocking {
        // Caught inside the child coroutine itself, not via an outer try/catch around `.join()`/
        // `.await()`: a plain (non-supervisor) parent scope cancels every sibling the instant an
        // unhandled child failure surfaces, racing an outer catch clause — see the kotlinx.coroutines
        // "Exception aggregation" docs. Catching here, inside the failing child, sidesteps that race.
        var caught: Throwable? = null
        val pairJob = launch {
            try {
                connectionManager.pairNewDevice()
            } catch (e: DevicePairingError.Cancelled) {
                caught = e
            }
        }
        withTimeout(2000) { connectionManager.pairingService.isPresenting.first { it } }

        connectionManager.pairingService.cancel()
        pairJob.join()

        assertTrue(caught is DevicePairingError.Cancelled)
        assertFalse(connectionManager.isPairingInProgress)
    }

    // MARK: - waitForOtherAppReconnection

    @Test
    fun `waitForOtherAppReconnection returns true on immediate detection`() = runBlocking {
        stateMachine.stubbedIsDeviceConnectedToSystem = true

        val result = connectionManager.waitForOtherAppReconnection(DEVICE_A)

        assertTrue(result)
        assertEquals(1, stateMachine.isDeviceConnectedToSystemCalls.size)
    }

    @Test
    fun `waitForOtherAppReconnection returns false after all checks`() = runBlocking {
        stateMachine.stubbedIsDeviceConnectedToSystem = false

        val result = connectionManager.waitForOtherAppReconnection(DEVICE_A)

        assertFalse(result)
        assertEquals(6, stateMachine.isDeviceConnectedToSystemCalls.size)
    }

    @Test
    fun `waitForOtherAppReconnection detects delayed reconnection`() = runBlocking {
        var callCount = 0
        stateMachine.isDeviceConnectedToSystemHandler = { callCount += 1; callCount >= 3 }

        val result = connectionManager.waitForOtherAppReconnection(DEVICE_A)

        assertTrue(result)
        assertEquals(3, stateMachine.isDeviceConnectedToSystemCalls.size)
    }

    // MARK: - Forget/delete device

    @Test
    fun `deleteDevice completes without error for non-existent device`() = runBlocking {
        connectionManager.deleteDevice(UUID.randomUUID())
    }

    @Test
    fun `deleteDevice clears the persisted connection when removing the last-connected radio`() = runBlocking {
        val deviceID = UUID.randomUUID()
        connectionManager.persistConnection(deviceID, UUID.randomUUID(), "Radio")
        assertEquals(deviceID, connectionManager.lastConnectedDeviceID)

        connectionManager.deleteDevice(deviceID)

        assertNull(connectionManager.lastConnectedDeviceID)
    }

    @Test
    fun `deleteDevice preserves the persisted connection when removing a different radio`() = runBlocking {
        val lastConnected = UUID.randomUUID()
        connectionManager.persistConnection(lastConnected, UUID.randomUUID(), "Radio")

        connectionManager.deleteDevice(UUID.randomUUID())

        assertEquals(lastConnected, connectionManager.lastConnectedDeviceID)
    }

    @Test
    fun `forgetDevice by id clears the persisted connection when removing the last-connected radio`() = runBlocking {
        val deviceID = UUID.randomUUID()
        connectionManager.persistConnection(deviceID, UUID.randomUUID(), "Radio")
        assertEquals(deviceID, connectionManager.lastConnectedDeviceID)

        connectionManager.forgetDevice(deviceID)

        assertNull(connectionManager.lastConnectedDeviceID)
    }

    @Test
    fun `forgetDevice by id preserves the persisted connection when removing a different radio`() = runBlocking {
        val lastConnected = UUID.randomUUID()
        connectionManager.persistConnection(lastConnected, UUID.randomUUID(), "Radio")

        connectionManager.forgetDevice(UUID.randomUUID())

        assertEquals(lastConnected, connectionManager.lastConnectedDeviceID)
    }

    @Test
    fun `forgetDevice with deleteData false demotes the device to a ghost instead of deleting it`() = runBlocking {
        runConnectAndAnswerHandshake(transport.mock) { connectionManager.connect(DEVICE_A) }
        val device = connectionManager.connectedDevice!!

        connectionManager.forgetDevice(deleteData = false)

        assertNull(connectionManager.connectedDevice)
        assertNull(connectionManager.deviceStore.fetchDeviceById(device.id))
        val ghost = connectionManager.deviceStore.fetchDevice(device.publicKey)!!
        assertTrue(ghost.isGhost)
        assertFalse(ghost.isActive)
        assertEquals(device.radioID, ghost.radioID)
    }

    @Test
    fun `forgetDevice with deleteData true cascades the connected devices data, not just the device row`() = runBlocking {
        runConnectAndAnswerHandshake(transport.mock) { connectionManager.connect(DEVICE_A) }
        val device = connectionManager.connectedDevice!!
        val contactStore = ContactStore(database)
        contactStore.saveContact(
            device.radioID,
            MeshContact(
                id = "aa",
                publicKey = ByteArray(32) { 0xAA.toByte() },
                type = ContactType.CHAT,
                flags = ContactFlags.NONE,
                outPathLength = 0xFFu,
                outPath = ByteArray(0),
                advertisedName = "Bob",
                lastAdvertisement = Instant.ofEpochSecond(900),
                latitude = 0.0,
                longitude = 0.0,
                lastModified = Instant.ofEpochSecond(1000),
            ),
        )
        assertEquals(1, contactStore.fetchContacts(device.radioID).size)

        connectionManager.forgetDevice()

        assertNull(connectionManager.deviceStore.fetchDeviceById(device.id))
        assertTrue(contactStore.fetchContacts(device.radioID).isEmpty())
    }

    // MARK: - Identity reconciliation

    @Test
    fun `reconcileIdentity returns null without touching the DB when a newer connection has already replaced the expected services`() = runBlocking {
        runConnectAndAnswerHandshake(transport.mock) { connectionManager.connect(DEVICE_A) }
        val staleServices = connectionManager.services!!
        val device = connectionManager.connectedDevice!!
        connectionManager.disconnect()

        val result = connectionManager.reconcileIdentity(expectedServices = staleServices, deviceID = device.id)

        assertNull(result)
        // Unaffected — the guard short-circuited before ever reading the (now-torn-down) session.
        assertEquals(device.radioID, connectionManager.deviceStore.fetchDeviceById(device.id)?.radioID)
    }

    // MARK: - Data operations / inert accessory surface

    @Test
    fun `fetchSavedDevices returns empty list when no devices saved`() = runBlocking {
        assertTrue(connectionManager.fetchSavedDevices().isEmpty())
    }

    @Test
    fun `hasAccessory and pairedAccessoryInfos match the no-system-registry platform`() {
        assertTrue(connectionManager.hasAccessory(DEVICE_A))
        assertTrue(connectionManager.pairedAccessoryInfos().isEmpty())
    }

    @Test
    fun `renameCurrentDevice throws when not connected`() = runBlocking {
        try {
            connectionManager.renameCurrentDevice()
            fail("expected ConnectionError.NotConnected")
        } catch (e: ConnectionError.NotConnected) {
            // expected
        }
    }
}
