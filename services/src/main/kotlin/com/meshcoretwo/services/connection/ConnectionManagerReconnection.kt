// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.protocol.MeshCoreSession
import com.meshcoretwo.services.ServiceContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ReconnectionDelegate] method bodies backing [ConnectionManager]'s thin one-line overrides (see
 * that class's doc for why they can't live here directly — Kotlin can't satisfy an interface via
 * an extension function). Ported from `ConnectionManager+BLEReconnectionDelegate.swift`.
 */

private const val REBUILD_SESSION_START_TIMEOUT_MILLIS = 10_000L
private const val REBUILD_QUERY_DEVICE_TIMEOUT_MILLIS = 10_000L

/** Tears down the current session/services for reconnection. Ported from `teardownSessionForReconnect`. */
internal suspend fun ConnectionManager.teardownSessionForReconnectImpl() {
    // Capture and clear synchronously so a concurrent rebuildSession can assume nil-and-rebuild
    // without racing the terminal writes.
    val oldServices = services
    val oldSession = session
    services = null
    session = null

    // Stop the old session (keeping the transport for the pending reconnect) so its receive/
    // auto-fetch loops end now instead of parking on the finished stream until GC.
    oldSession?.stop(disconnectTransport = false)
    // Drop session-live so RSSI cannot refresh a bond stamp while the app stack is gone.
    stateMachine.setAppSessionLive(null)

    if (oldServices != null) {
        sessionsAwaitingReauth = oldServices.remoteNodeService.handleBLEDisconnection().toMutableSet()
        oldServices.tearDown()
    }
    cancelResyncLoopImpl()

    if (oldServices != null) {
        oldServices.syncCoordinator.onDisconnected(oldServices.notificationService)
    }
}

/**
 * Rebuilds the session after BLE auto-reconnect completes. Ported from `rebuildSession`.
 *
 * Background execution note: this should complete quickly — full sync is deferred until
 * [ConnectionManagerSyncRetry.kt]'s `performInitialSyncImpl` returns, called from here.
 */
internal suspend fun ConnectionManager.rebuildSessionImpl(deviceAddress: String) {
    val expectedGeneration = reconnectionCoordinator.reconnectGeneration
    // Nested claim: a health-check rebuild may already hold sessionRebuildDeviceAddress; only
    // clear on exit when this call installed the claim.
    val claimedHere = sessionRebuildDeviceAddress == null
    if (claimedHere) sessionRebuildDeviceAddress = deviceAddress
    try {
        // The auto-reconnect link is already live, so surface CONNECTED up front, matching a
        // fresh connect: the syncing signal stays visible through rebuild and initial sync.
        setConnectionState(DeviceConnectionState.CONNECTED)

        // Session teardown in this rebuild never disconnects the transport: the link belongs to
        // the reconnect cycle (or, when superseded, to a newer one), and an explicit disconnect
        // here would cancel the OS pending connect recovering it.
        session?.stop(disconnectTransport = false)
        session = null
        stateMachine.setAppSessionLive(null)

        // The stopped session's receive-loop cancellation ended the vended stream's shared
        // storage, so the transport must re-vend before the new session reads receivedData.
        transport.refreshDataStream()

        val newSession = MeshCoreSession(transport, sessionConfiguration)
        session = newSession

        val started = withTimeoutOrNull(REBUILD_SESSION_START_TIMEOUT_MILLIS) {
            newSession.start(reconnectingAttempt = 1, disconnectTransportOnFailure = false)
            true
        }
        if (started == null) throw ConnectionError.InitializationFailed("rebuildSession: session.start() timed out")

        // Session traffic flowed over the encrypted UART link, so the bond is proven healthy now.
        val existingDeviceID = deviceStore.fetchDeviceByBleAddress(deviceAddress)?.id
        if (existingDeviceID != null) recordBondVerification(existingDeviceID, deviceAddress)

        if (!connectionIntent.wantsConnection) {
            abandonRebuildAfterHandshakeImpl(newSession, setDisconnected = true)
            return
        }
        if (reconnectionCoordinator.reconnectGeneration != expectedGeneration) {
            abandonRebuildAfterHandshakeImpl(newSession)
            return
        }

        val selfInfo = newSession.currentSelfInfo
            ?: throw ConnectionError.InitializationFailed("rebuildSession: no self info")
        val capabilities = withTimeoutOrNull(REBUILD_QUERY_DEVICE_TIMEOUT_MILLIS) { newSession.queryDevice() }
            ?: throw ConnectionError.InitializationFailed("rebuildSession: queryDevice() timed out")

        configureBLEPacingImpl(capabilities)

        if (!connectionIntent.wantsConnection) {
            abandonRebuildAfterHandshakeImpl(newSession, setDisconnected = true)
            return
        }
        if (reconnectionCoordinator.reconnectGeneration != expectedGeneration) {
            abandonRebuildAfterHandshakeImpl(newSession)
            return
        }

        val (newServices, radioID) = buildServicesAndSaveDeviceImpl(deviceAddress, newSession, selfInfo, capabilities)

        if (!connectionIntent.wantsConnection) {
            abandonRebuildAfterHandshakeImpl(newSession, newServices, setDisconnected = true)
            return
        }
        if (reconnectionCoordinator.reconnectGeneration != expectedGeneration) {
            abandonRebuildAfterHandshakeImpl(newSession, newServices)
            return
        }

        onConnectionReady?.invoke()
        // onConnectionReady can suspend; a reentrant disconnect or reconnect-UI timeout may clear
        // connectedDevice or replace services during that suspension.
        if (!connectionIntent.wantsConnection ||
            reconnectionCoordinator.reconnectGeneration != expectedGeneration ||
            services !== newServices ||
            connectedDevice == null
        ) {
            abandonRebuildAfterHandshakeImpl(newSession, newServices)
            return
        }

        val syncSucceeded = performInitialSyncImpl(radioID, newServices, context = "BLE auto-reconnect")

        if (!connectionIntent.wantsConnection || reconnectionCoordinator.reconnectGeneration != expectedGeneration || services !== newServices) {
            abandonRebuildAfterHandshakeImpl(newSession, newServices)
            return
        }

        if (syncSucceeded) {
            // Re-authenticate room sessions (sends BLE commands — skip on failure path).
            val sessionIDs = sessionsAwaitingReauth.toSet()
            newServices.remoteNodeService.handleBLEReconnection(sessionIDs)

            if (!connectionIntent.wantsConnection || reconnectionCoordinator.reconnectGeneration != expectedGeneration || services !== newServices) {
                // IDs preserved for next reconnect cycle — new IDs may have arrived during
                // handleBLEReconnection if BLE dropped mid-reauth.
                abandonRebuildAfterHandshakeImpl(newSession, newServices)
                return
            }
            sessionsAwaitingReauth.removeAll(sessionIDs)
        }

        val promoted = promoteToReadyImpl(
            syncSucceeded = syncSucceeded,
            expectedServices = newServices,
            transportType = TransportType.BLUETOOTH,
            additionalGuard = { reconnectionCoordinator.reconnectGeneration == expectedGeneration },
        )
        if (!promoted) {
            abandonRebuildAfterHandshakeImpl(newSession, newServices)
            return
        }

        recordConnectionSuccess()
        stopReconnectionWatchdogImpl()
    } finally {
        if (claimedHere && sessionRebuildDeviceAddress == deviceAddress) sessionRebuildDeviceAddress = null
    }
}

/**
 * Stops a mid-rebuild session that already completed the handshake, nils the stored
 * session/services when they match, and clears session-live. Ported from `abandonRebuildAfterHandshake`.
 */
private suspend fun ConnectionManager.abandonRebuildAfterHandshakeImpl(
    rebuildSession: MeshCoreSession,
    rebuildServices: ServiceContainer? = null,
    setDisconnected: Boolean = false,
) {
    rebuildSession.stop(disconnectTransport = false)
    if (rebuildServices != null) {
        rebuildServices.tearDown()
        if (services === rebuildServices) services = null
    }
    if (session === rebuildSession) session = null
    stateMachine.setAppSessionLive(null)
    if (setDisconnected) {
        setConnectionState(DeviceConnectionState.DISCONNECTED)
        connectedDevice = null
        allowedRepeatFreqRanges = emptyList()
    }
}

/**
 * Handles reconnection failure: app-stack teardown with preserve-vs-sever branching on link
 * health, intent, and rebuild budget. Ported from `handleReconnectionFailure`.
 */
internal suspend fun ConnectionManager.handleReconnectionFailureImpl() {
    // Budget only advances when intent still wants a connection — burning the counter on a
    // user-disconnect-during-retry path would exhaust the preserve budget on events unrelated to
    // real reconnect failures.
    if (connectionIntent.wantsConnection) {
        consecutiveRebuildFailures += 1
    }

    // Capture and clear synchronously *before* any suspension so a concurrent rebuild that
    // installs session/services during state-machine queries cannot be torn down by re-reading self.
    val oldSession = session
    val oldServices = services
    session = null
    services = null
    setConnectionState(DeviceConnectionState.DISCONNECTED)
    connectedDevice = null
    allowedRepeatFreqRanges = emptyList()

    // A rebuild failure is an app-layer failure. Severing the link cancels the OS pending
    // connect and the live GATT subscription — the only wake sources that survive process
    // suspension — and discards a link the health-check ladder can rebuild in place.
    val isConnected = stateMachine.isConnected
    val isAutoReconnecting = stateMachine.isAutoReconnecting
    val holdsLink = isConnected || isAutoReconnecting
    val preserveLink = connectionIntent.wantsConnection &&
        holdsLink &&
        consecutiveRebuildFailures <= ConnectionManager.MAX_REBUILD_FAILURES_PRESERVING_LINK

    // Session-live bond refresh must stop while the app stack is dead. Clear on both branches —
    // preserve keeps phase Connected, so phase cleanup never clears the signal there.
    stateMachine.setAppSessionLive(null)

    oldSession?.stop(disconnectTransport = false)
    oldServices?.tearDown()

    if (preserveLink) {
        // n of N still preserving; sever is on the (N+1)th wanting entry.
        if (reconnectionWatchdogJob == null) startReconnectionWatchdogImpl()
        onConnectionLost?.invoke()
    } else {
        if (connectionIntent.wantsConnection && holdsLink) {
            persistDisconnectDiagnostic(
                "source=handleReconnectionFailure.preserveBudgetExhausted, failures=$consecutiveRebuildFailures, intent=$connectionIntent",
            )
        }
        transport.disconnect()
        // Exhaustion / no-link / user-disconnect: full path (cancel+restart watchdog).
        notifyConnectionLost()
    }
}
