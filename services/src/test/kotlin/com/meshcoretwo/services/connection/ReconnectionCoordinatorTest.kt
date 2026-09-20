// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.meshcoretwo.services.connection

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DEVICE_A = "AA:BB:CC:DD:EE:01"
private const val DEVICE_B = "AA:BB:CC:DD:EE:02"

class ReconnectionCoordinatorTest {
    // MARK: - handleEnteringAutoReconnect

    @Test
    fun `handleEnteringAutoReconnect disconnects the transport instead of claiming a cycle when the user disconnected`() = runTest {
        val delegate = FakeReconnectionDelegate().apply { connectionIntent = ConnectionIntent.UserDisconnected }
        val coordinator = ReconnectionCoordinator(this).apply { this.delegate = delegate }

        coordinator.handleEnteringAutoReconnect(DEVICE_A)

        assertEquals(1, delegate.disconnectTransportCallCount)
        assertNull(coordinator.reconnectingDeviceAddress)
        assertEquals(0, delegate.teardownCallCount)
    }

    @Test
    fun `handleEnteringAutoReconnect claims the cycle, sets connecting, and tears down the session`() = runTest {
        val delegate = FakeReconnectionDelegate()
        val coordinator = ReconnectionCoordinator(this).apply { this.delegate = delegate }

        coordinator.handleEnteringAutoReconnect(DEVICE_A)

        assertEquals(DEVICE_A, coordinator.reconnectingDeviceAddress)
        assertEquals(DeviceConnectionState.CONNECTING, delegate.connectionState)
        assertEquals(1, delegate.notifyAutoReconnectStartedCallCount)
        assertEquals(1, delegate.teardownCallCount)
    }

    // MARK: - handleReconnectionComplete

    @Test
    fun `handleReconnectionComplete for an unclaimed device is ignored`() = runTest {
        val delegate = FakeReconnectionDelegate()
        val coordinator = ReconnectionCoordinator(this).apply { this.delegate = delegate }
        coordinator.handleEnteringAutoReconnect(DEVICE_A)

        coordinator.handleReconnectionComplete(DEVICE_B)

        assertTrue(delegate.rebuildSessionCalls.isEmpty())
        assertEquals(DEVICE_A, coordinator.reconnectingDeviceAddress)
    }

    @Test
    fun `handleReconnectionComplete rebuilds the session and clears the claim on success`() = runTest {
        val delegate = FakeReconnectionDelegate()
        val coordinator = ReconnectionCoordinator(this).apply { this.delegate = delegate }
        coordinator.handleEnteringAutoReconnect(DEVICE_A)

        coordinator.handleReconnectionComplete(DEVICE_A)

        assertEquals(listOf(DEVICE_A), delegate.rebuildSessionCalls)
        assertNull(coordinator.reconnectingDeviceAddress)
    }

    @Test
    fun `handleReconnectionComplete disconnects transport and clears claim when the user disconnected`() = runTest {
        val delegate = FakeReconnectionDelegate()
        val coordinator = ReconnectionCoordinator(this).apply { this.delegate = delegate }
        coordinator.handleEnteringAutoReconnect(DEVICE_A)
        delegate.connectionIntent = ConnectionIntent.UserDisconnected

        coordinator.handleReconnectionComplete(DEVICE_A)

        assertEquals(1, delegate.disconnectTransportCallCount)
        assertNull(coordinator.reconnectingDeviceAddress)
        assertTrue(delegate.rebuildSessionCalls.isEmpty())
    }

    @Test
    fun `a failed rebuild retries once after 2 seconds and succeeds`() = runTest {
        val delegate = FakeReconnectionDelegate()
        var attempt = 0
        delegate.rebuildSessionHandler = { attempt++; if (attempt == 1) error("simulated failure") }
        val coordinator = ReconnectionCoordinator(this).apply { this.delegate = delegate }
        coordinator.handleEnteringAutoReconnect(DEVICE_A)

        // handleReconnectionComplete awaits the retry (including its delay) inline — under
        // runTest's virtual time this resolves synchronously, so both attempts have already
        // happened by the time this call returns.
        coordinator.handleReconnectionComplete(DEVICE_A)

        assertEquals(2, delegate.rebuildSessionCalls.size)
        assertNull(coordinator.reconnectingDeviceAddress)
    }

    @Test
    fun `a retry aborts without rebuilding when a newer cycle started during the retry delay`() = runTest {
        val delegate = FakeReconnectionDelegate()
        delegate.rebuildSessionHandler = { error("simulated failure") }
        val coordinator = ReconnectionCoordinator(this).apply { this.delegate = delegate }
        coordinator.handleEnteringAutoReconnect(DEVICE_A)

        // Run the first completion (whose retry sleeps for 2s) as a background job so the test
        // body can claim a newer cycle for a different device while it's still suspended.
        val job = launch { coordinator.handleReconnectionComplete(DEVICE_A) }
        runCurrent()
        assertEquals(1, delegate.rebuildSessionCalls.size)

        // A newer cycle claims a different device before the retry delay elapses.
        coordinator.handleEnteringAutoReconnect(DEVICE_B)

        advanceTimeBy(2_001)
        runCurrent()
        job.join()

        // The stale retry must not have rebuilt again, and must not have clobbered device B's claim.
        assertEquals(1, delegate.rebuildSessionCalls.size)
        assertEquals(DEVICE_B, coordinator.reconnectingDeviceAddress)
    }

    // MARK: - restartTimeout / cancelTimeout / clearReconnectingDevice

    @Test
    fun `restartTimeout on an unclaimed device claims a new cycle`() {
        val delegate = FakeReconnectionDelegate()
        val coordinator = ReconnectionCoordinator(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)).apply { this.delegate = delegate }

        coordinator.restartTimeout(DEVICE_A)

        assertEquals(DEVICE_A, coordinator.reconnectingDeviceAddress)
    }

    @Test
    fun `clearReconnectingDevice clears the claim and bumps the generation`() = runTest {
        val delegate = FakeReconnectionDelegate()
        val coordinator = ReconnectionCoordinator(this).apply { this.delegate = delegate }
        coordinator.handleEnteringAutoReconnect(DEVICE_A)
        val generationBefore = coordinator.reconnectGeneration

        coordinator.clearReconnectingDevice()

        assertNull(coordinator.reconnectingDeviceAddress)
        assertTrue(coordinator.reconnectGeneration > generationBefore)
    }

    // MARK: - UI timeout

    @Test
    fun `the UI timeout fires disconnected when the transport is no longer auto-reconnecting`() = runTest {
        val delegate = FakeReconnectionDelegate().apply { stubbedIsTransportAutoReconnecting = false }
        val coordinator = ReconnectionCoordinator(this, uiTimeoutMillis = 1_000).apply { this.delegate = delegate }

        coordinator.handleEnteringAutoReconnect(DEVICE_A)
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(DeviceConnectionState.DISCONNECTED, delegate.connectionState)
        assertNull(delegate.connectedDevice)
        assertEquals(1, delegate.notifyConnectionLostCallCount)
        assertNull(coordinator.reconnectingDeviceAddress)
    }

    @Test
    fun `the UI timeout re-arms instead of disconnecting while the transport is still auto-reconnecting`() = runTest {
        val delegate = FakeReconnectionDelegate().apply { stubbedIsTransportAutoReconnecting = true }
        val coordinator = ReconnectionCoordinator(this, uiTimeoutMillis = 1_000, maxConnectingUIWindowMillis = 60_000).apply { this.delegate = delegate }

        coordinator.handleEnteringAutoReconnect(DEVICE_A)
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(DeviceConnectionState.CONNECTING, delegate.connectionState)
        assertEquals(DEVICE_A, coordinator.reconnectingDeviceAddress)
        assertFalse(delegate.notifyConnectionLostCallCount > 0)
    }
}
