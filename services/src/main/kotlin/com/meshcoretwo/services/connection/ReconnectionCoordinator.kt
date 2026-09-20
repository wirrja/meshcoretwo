// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.persistence.DeviceDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Delegate interface for [ReconnectionCoordinator]. `ConnectionManager` implements this to provide
 * session management. Ported from `BLEReconnectionDelegate` (`BLEReconnectionCoordinator.swift`),
 * renaming every `deviceID: UUID` to `deviceAddress: String` — the coordinator is driven by
 * [com.meshcoretwo.services.transport.BleStateMachineOps]'s auto-reconnect handler, which is keyed
 * by BLE MAC address (see that interface's "Identifier note"), not by the domain [DeviceDto.id]
 * `ConnectionManager` otherwise persists connections under.
 */
interface ReconnectionDelegate {
    val connectionIntent: ConnectionIntent
    val connectionState: DeviceConnectionState

    /** Sets the connection state (used by the coordinator for state transitions). */
    fun setConnectionState(state: DeviceConnectionState)

    /** Sets the connected device (used by the coordinator to clear on timeout). */
    fun setConnectedDevice(device: DeviceDto?)

    /** Tears down the current session and services for reconnection. */
    suspend fun teardownSessionForReconnect()

    /** Rebuilds the session after BLE auto-reconnect completes. Throws on failure. */
    suspend fun rebuildSession(deviceAddress: String)

    /** Disconnects the BLE transport (used when the user disconnected during reconnect). */
    suspend fun disconnectTransport()

    /** Notifies the UI layer that the link dropped and auto-reconnect has begun. */
    suspend fun notifyAutoReconnectStarted()

    /** Notifies the UI layer of connection loss. */
    suspend fun notifyConnectionLost()

    /**
     * Handles reconnection failure: app-stack teardown with preserve-vs-sever branching on link
     * health, intent, and rebuild budget. Does not always disconnect the transport — a live link
     * under budget is preserved for in-place health-check rebuild.
     */
    suspend fun handleReconnectionFailure()

    /** Returns whether the BLE transport is currently in auto-reconnecting phase. */
    suspend fun isTransportAutoReconnecting(): Boolean
}

/**
 * Coordinates the BLE auto-reconnect lifecycle, managing UI-timeout state and orchestrating
 * teardown/rebuild via [delegate]. Ported from `BLEReconnectionCoordinator.swift`.
 *
 * ## Concurrency
 *
 * Swift's `BLEReconnectionCoordinator` and `ConnectionManager` are both `@MainActor`: every method
 * on either runs on the same single-threaded executor, reentrant only at `await` points — which is
 * exactly what the generation-counter/identity re-checks scattered through this class (and
 * documented inline below) are defending against. `Mutex.withLock` would be the wrong translation
 * here: it would make a second caller *block* until the first releases, rather than letting it
 * *run* (and see the same interleaved state a MainActor caller would) — precisely the difference
 * these guards are written to survive. Instead, [scope] is expected to be
 * `ConnectionManager`'s own scope, backed by a `kotlinx.coroutines.CoroutineDispatcher
 * .limitedParallelism(1)` dispatcher — the structural equivalent of a single actor executor:
 * multiple suspended calls can be in flight, but only one runs its synchronous code at a time, and
 * suspension yields to the next runnable one, exactly like `@MainActor` reentrancy. Every public
 * method here is therefore expected to be invoked from a coroutine already confined to that
 * dispatcher (`ConnectionManager` enters through `withContext` at its own public entry points), not
 * called directly from an arbitrary thread.
 */
class ReconnectionCoordinator(
    private val scope: CoroutineScope,
    private val uiTimeoutMillis: Long = 15_000,
    private val maxConnectingUIWindowMillis: Long = 60_000,
) {
    var delegate: ReconnectionDelegate? = null

    /**
     * The reconnect cycle currently claimed by [handleEnteringAutoReconnect]. Completions are
     * accepted only when this matches the completing device; `null` (entry suppressed, or
     * manually superseded) rejects late completions so they can't race a new flow.
     */
    private data class ReconnectCycle(val deviceAddress: String, val generation: Int, var uiTimedOut: Boolean)

    private var activeCycle: ReconnectCycle? = null

    /** The device address for the cycle currently claimed, or `null` if none. */
    val reconnectingDeviceAddress: String? get() = activeCycle?.deviceAddress

    private var timeoutJob: Job? = null

    /**
     * Generation of an in-flight [ReconnectionDelegate.rebuildSession] (including the first-fail
     * retry), or `null` when none. Rejects a second completion only for the same generation so a
     * newer cycle (bumped by [handleEnteringAutoReconnect]) can still rebuild while a stale retry
     * sleeps, without allowing dual entry on one generation.
     */
    private var sessionRebuildInFlightGeneration: Int? = null

    /** Incremented each time a reconnection cycle starts, used to detect stale rebuilds and retries. */
    var reconnectGeneration = 0
        private set

    /** When the current reconnect UI window started (`System.currentTimeMillis()`), used to bound re-arms. */
    private var reconnectUIWindowStartMillis: Long? = null

    /** Handles the device entering BLE auto-reconnect phase. Tears down the session layer and starts a UI timeout. */
    suspend fun handleEnteringAutoReconnect(deviceAddress: String) {
        val delegate = delegate ?: return

        if (!delegate.connectionIntent.wantsConnection) {
            delegate.disconnectTransport()
            return
        }

        // Set connecting state before awaiting teardown so a completion that runs
        // during the teardown await still sees .connecting.
        delegate.setConnectionState(DeviceConnectionState.CONNECTING)
        reconnectGeneration += 1
        activeCycle = ReconnectCycle(deviceAddress, reconnectGeneration, uiTimedOut = false)
        reconnectUIWindowStartMillis = System.currentTimeMillis()

        // Reflect the drop before teardown so the UI observes it at the front of the
        // bounded disconnect window, not behind session teardown. The cycle is already
        // claimed, so this can't strand a completion.
        delegate.notifyAutoReconnectStarted()

        delegate.teardownSessionForReconnect()

        armTimeout(deviceAddress, reconnectGeneration)
    }

    /** Handles BLE auto-reconnect completion. Cancels the UI timeout and delegates session rebuild. */
    suspend fun handleReconnectionComplete(deviceAddress: String) {
        val delegate = delegate ?: return

        if (!delegate.connectionIntent.wantsConnection) {
            cancelTimeout()
            clearActiveCycle(invalidateGeneration = true)
            delegate.disconnectTransport()
            return
        }

        // Reject completions for cycles we didn't claim — an orphaned completion would
        // race the new flow. Don't cancel the active timeout: the current reconnect
        // retains its fallback.
        if (activeCycle?.deviceAddress != deviceAddress) return

        // Kept for parity with Swift's log-only flag; harmless to compute even unused here.
        @Suppress("UNUSED_VARIABLE")
        val completedAfterUITimeout = activeCycle?.uiTimedOut == true

        // This completion is for our device — safe to cancel the timeout. Keep the cycle
        // claimed across rebuild, the first-fail retry sleep, and handleReconnectionFailure
        // so a concurrent health check cannot install a stack the failure handler would tear down.
        cancelTimeout()

        // Accept both DISCONNECTED (normal) and CONNECTING (auto-reconnect in progress).
        // rebuildSession sets CONNECTED early, so a second same-device completion often
        // arrives while state is already CONNECTED. If a rebuild (or its retry/failure
        // path) is in flight, ignore without clearing the claim — that claim is what
        // keeps a health check single-flight during the gap.
        val state = delegate.connectionState
        if (state != DeviceConnectionState.DISCONNECTED && state != DeviceConnectionState.CONNECTING) {
            if (sessionRebuildInFlightGeneration != null) return
            clearActiveCycle(invalidateGeneration = false)
            return
        }

        // Reject a second completion while a rebuild for *this* generation is live (dual
        // completion). A newer handleEnteringAutoReconnect bumps generation first, so a
        // completion after that can rebuild while a stale retry still sleeps under an older mark.
        if (sessionRebuildInFlightGeneration == reconnectGeneration) return

        reconnectGeneration += 1
        val expectedGeneration = reconnectGeneration

        delegate.setConnectionState(DeviceConnectionState.CONNECTING)
        sessionRebuildInFlightGeneration = expectedGeneration
        try {
            delegate.rebuildSession(deviceAddress)
            // Only clear if we still own the generation — a non-throwing supersession
            // abort leaves a newer cycle's claim intact.
            if (expectedGeneration == reconnectGeneration) {
                clearActiveCycle(invalidateGeneration = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            retryRebuild(deviceAddress, expectedGeneration)
        } finally {
            if (sessionRebuildInFlightGeneration == expectedGeneration) {
                sessionRebuildInFlightGeneration = null
            }
        }
    }

    /** Restarts the UI timeout without tearing down the session — used when the user retries while auto-reconnect is already in progress. */
    fun restartTimeout(deviceAddress: String) {
        val cycle = activeCycle
        if (cycle == null || cycle.deviceAddress != deviceAddress) {
            reconnectGeneration += 1
            activeCycle = ReconnectCycle(deviceAddress, reconnectGeneration, uiTimedOut = false)
        } else {
            cycle.uiTimedOut = false
        }
        reconnectUIWindowStartMillis = System.currentTimeMillis()
        val generation = activeCycle?.generation ?: return
        armTimeout(deviceAddress, generation)
    }

    private fun armTimeout(deviceAddress: String, generation: Int) {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(uiTimeoutMillis)
            handleUITimeout(deviceAddress, generation)
        }
    }

    /** Cancels the UI timeout timer. */
    fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    /** Clears the reconnecting device, used when a manual connect supersedes auto-reconnect. */
    fun clearReconnectingDevice() = clearActiveCycle(invalidateGeneration = true)

    private fun clearActiveCycle(invalidateGeneration: Boolean) {
        if (activeCycle == null) return
        activeCycle = null
        reconnectUIWindowStartMillis = null
        if (invalidateGeneration) reconnectGeneration += 1
    }

    /** Retries a failed session rebuild after a short delay, aborting if the generation changed or the user disconnected during the wait. */
    private suspend fun retryRebuild(deviceAddress: String, expectedGeneration: Int) {
        val delegate = delegate
        if (delegate == null) {
            if (expectedGeneration == reconnectGeneration) clearActiveCycle(invalidateGeneration = false)
            return
        }

        delay(2_000)

        if (expectedGeneration != reconnectGeneration) {
            // A newer cycle owns the claim; do not clear theirs.
            return
        }
        if (!delegate.connectionIntent.wantsConnection) {
            // Hold the claim through failure handling so a concurrent health check cannot
            // rebuild under a torn-down stack, then release only if we still own the generation.
            delegate.handleReconnectionFailure()
            if (expectedGeneration == reconnectGeneration) clearActiveCycle(invalidateGeneration = false)
            return
        }

        try {
            delegate.rebuildSession(deviceAddress)
            if (expectedGeneration == reconnectGeneration) clearActiveCycle(invalidateGeneration = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            delegate.handleReconnectionFailure()
            if (expectedGeneration == reconnectGeneration) clearActiveCycle(invalidateGeneration = false)
        }
    }

    private suspend fun handleUITimeout(deviceAddress: String, generation: Int) {
        val delegate = delegate ?: return
        if (delegate.connectionState != DeviceConnectionState.CONNECTING) return
        val cycle = activeCycle ?: return
        if (cycle.deviceAddress != deviceAddress || cycle.generation != generation) return

        val startMillis = reconnectUIWindowStartMillis ?: System.currentTimeMillis()
        val elapsedMillis = System.currentTimeMillis() - startMillis

        // If the BLE transport is still actively auto-reconnecting and the max connecting
        // window hasn't elapsed, re-arm instead of forcing disconnected state — handles a
        // timeout armed before suspension that fires immediately on resume.
        val transportAutoReconnecting = delegate.isTransportAutoReconnecting()

        // Re-validate after the await: handleReconnectionComplete can run to completion
        // during the suspension, and this timeout must not force a freshly rebuilt session
        // back to disconnected.
        if (delegate.connectionState != DeviceConnectionState.CONNECTING) return
        val currentCycle = activeCycle ?: return
        if (currentCycle.deviceAddress != deviceAddress || currentCycle.generation != generation) return

        if (transportAutoReconnecting && elapsedMillis < maxConnectingUIWindowMillis) {
            armTimeout(deviceAddress, generation)
            return
        }

        if (transportAutoReconnecting) {
            activeCycle?.uiTimedOut = true
        } else {
            clearActiveCycle(invalidateGeneration = true)
        }
        delegate.setConnectionState(DeviceConnectionState.DISCONNECTED)
        delegate.setConnectedDevice(null)
        delegate.notifyConnectionLost()
    }
}
