// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val DEVICE_A = "AA:BB:CC:DD:EE:01"

/**
 * Covers the [BleStateMachineOps] surface added for the "`BLEStateMachineProtocol`-analog" slice
 * (bond verification, app-session-live, shutdown, app-lifecycle hooks) — the parts of
 * [BleStateMachine] that don't require a live GATT connection to exercise. Full connect/discover/
 * reconnect flows need real MeshCore hardware (see PLAN.md's Phase 2 status) and aren't covered
 * here.
 */
@RunWith(RobolectricTestRunner::class)
class BleStateMachineTest {
    private lateinit var stateMachine: BleStateMachine

    @Before
    fun setUp() {
        stateMachine = BleStateMachine(RuntimeEnvironment.getApplication())
    }

    // MARK: - Bond verification / app-session-live

    @Test
    fun `recordBondVerification then hasBondVerification is true`() = runTest {
        stateMachine.recordBondVerification(DEVICE_A, 1_000L)
        assertTrue(stateMachine.hasBondVerification(DEVICE_A))
    }

    @Test
    fun `clearBondVerification removes the stamp`() = runTest {
        stateMachine.recordBondVerification(DEVICE_A, 1_000L)
        stateMachine.clearBondVerification(DEVICE_A)
        assertFalse(stateMachine.hasBondVerification(DEVICE_A))
    }

    @Test
    fun `hasBondVerification is false for a device that never verified`() = runTest {
        assertFalse(stateMachine.hasBondVerification(DEVICE_A))
    }

    @Test
    fun `setAppSessionLive then isAppSessionLive matches only that device`() = runTest {
        stateMachine.setAppSessionLive(DEVICE_A)
        assertTrue(stateMachine.isAppSessionLive(DEVICE_A))
        assertFalse(stateMachine.isAppSessionLive("AA:BB:CC:DD:EE:02"))
    }

    @Test
    fun `setAppSessionLive(null) clears the live signal`() = runTest {
        stateMachine.setAppSessionLive(DEVICE_A)
        stateMachine.setAppSessionLive(null)
        assertFalse(stateMachine.isAppSessionLive(DEVICE_A))
    }

    @Test
    fun `shouldPersistBondRefresh requires both a bond stamp and a live session for the same device`() = runTest {
        assertFalse(stateMachine.shouldPersistBondRefresh(DEVICE_A))

        stateMachine.recordBondVerification(DEVICE_A, 1_000L)
        assertFalse(stateMachine.shouldPersistBondRefresh(DEVICE_A))

        stateMachine.setAppSessionLive(DEVICE_A)
        assertTrue(stateMachine.shouldPersistBondRefresh(DEVICE_A))

        stateMachine.clearBondVerification(DEVICE_A)
        assertFalse(stateMachine.shouldPersistBondRefresh(DEVICE_A))
    }

    // MARK: - shutdown

    @Test
    fun `shutdown from idle does not invoke the disconnection handler`() = runTest {
        var invoked = false
        stateMachine.setDisconnectionHandler { _, _ -> invoked = true }
        stateMachine.shutdown()
        assertFalse(invoked)
    }

    // MARK: - App lifecycle

    @Test
    fun `appDidEnterBackground then appDidBecomeActive round-trips isAppActive`() = runTest {
        assertTrue(stateMachine.isAppActive)
        stateMachine.appDidEnterBackground()
        assertFalse(stateMachine.isAppActive)
        stateMachine.appDidBecomeActive()
        assertTrue(stateMachine.isAppActive)
    }

    // MARK: - System-connected-peripheral (no permission granted under Robolectric by default)

    @Test
    fun `isDeviceConnectedToSystem is false without BLUETOOTH_CONNECT permission`() {
        assertFalse(stateMachine.isDeviceConnectedToSystem(DEVICE_A))
    }

    @Test
    fun `systemConnectedDeviceAddresses is empty without BLUETOOTH_CONNECT permission`() {
        assertTrue(stateMachine.systemConnectedDeviceAddresses().isEmpty())
    }

    @Test
    fun `startAdoptingSystemConnectedPeripheral returns false when no matching device is connected`() {
        assertFalse(stateMachine.startAdoptingSystemConnectedPeripheral(DEVICE_A))
    }

    // MARK: - Sanity: no active phase

    @Test
    fun `a fresh state machine is idle`() {
        assertFalse(stateMachine.isConnected)
        assertFalse(stateMachine.isAutoReconnecting)
        assertNull(stateMachine.connectedDeviceAddress)
        assertEquals(BlePhaseKind.IDLE, stateMachine.linkDiagnostics.phase)
    }
}
