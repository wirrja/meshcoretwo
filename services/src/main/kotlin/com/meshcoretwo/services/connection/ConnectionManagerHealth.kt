// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.ServiceContainer
import com.meshcoretwo.services.transport.BlePhaseKind
import com.meshcoretwo.services.transport.DiscoveredDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * BLE connection health checks, the reconnection watchdog, device-scanning, and
 * "connected-to-another-app"/system-connected-peripheral adoption. Ported from the matching
 * sections of `ConnectionManager+BLE.swift` — see [ConnectionManager]'s class doc for the file
 * split.
 */

/** Resolves whether [deviceAddress] is the BLE address of the last persisted connection. */
internal suspend fun ConnectionManager.isLastConnectedDevice(deviceAddress: String): Boolean {
    val lastID = lastConnectedDeviceID ?: return false
    return deviceStore.fetchDeviceById(lastID)?.bleAddress == deviceAddress
}

// MARK: - BLE device checks

/** Checks if a device is connected to the system by another app. Ported from `isDeviceConnectedToOtherApp`. */
internal suspend fun ConnectionManager.isDeviceConnectedToOtherAppImpl(deviceAddress: String): Boolean {
    val isAutoReconnecting = stateMachine.isAutoReconnecting
    val smIsConnected = stateMachine.isConnected
    val smConnectedAddress = stateMachine.connectedDeviceAddress
    val systemConnected = stateMachine.isDeviceConnectedToSystem(deviceAddress)

    // Don't check during auto-reconnect - that's our own connection.
    if (isAutoReconnecting) return false
    // Don't check if we're already connected (switching devices).
    if (connectionState != DeviceConnectionState.DISCONNECTED) return false
    // Don't report our own connection as "another app" (state restoration may have completed).
    if (smIsConnected && smConnectedAddress == deviceAddress) return false

    return systemConnected
}

/**
 * Checks if [deviceAddress] is connected to the system by another app — public wrapper around
 * [isDeviceConnectedToOtherAppImpl] for device-selection UI to flag a saved BLE row before the
 * user attempts to connect. Ported from `isDeviceConnectedToOtherApp`'s `DeviceSelectionSheet`
 * call site (`DeviceSelectionSheet.swift`'s `loadDevices`).
 */
suspend fun ConnectionManager.isDeviceConnectedToOtherApp(deviceAddress: String): Boolean =
    withContext(confinedDispatcher) { isDeviceConnectedToOtherAppImpl(deviceAddress) }

/**
 * Attempts to adopt a system-connected BLE link for the *last connected* device. Android can keep
 * a BLE link alive across app process death while the state machine restarts idle; rather than
 * treating this as "connected elsewhere", the restoration discovery chain runs against the
 * existing link. Ported from `startAdoptingLastSystemConnectedPeripheralIfAvailable`.
 *
 * @return `true` if an adoption attempt was started.
 */
internal suspend fun ConnectionManager.startAdoptingLastSystemConnectedPeripheralIfAvailableImpl(
    deviceAddress: String,
    context: String,
): Boolean {
    if (!isLastConnectedDevice(deviceAddress)) return false
    if (currentTransportType != null && currentTransportType != TransportType.BLUETOOTH) return false
    if (connectionState != DeviceConnectionState.DISCONNECTED) return false
    if (!connectionIntent.wantsConnection) return false

    // Don't interfere with auto-reconnect or an active BLE connection.
    if (stateMachine.isAutoReconnecting) return false
    if (stateMachine.isConnected && stateMachine.connectedDeviceAddress == deviceAddress) return false

    // Adoption is only valid from an idle state machine.
    val diagnostics = stateMachine.linkDiagnostics
    if (diagnostics.phase != BlePhaseKind.IDLE) return false

    if (!stateMachine.isDeviceConnectedToSystem(deviceAddress)) return false

    // Prepare session layer + UI timeout window before starting adoption.
    reconnectionCoordinator.handleEnteringAutoReconnect(deviceAddress)

    val started = stateMachine.startAdoptingSystemConnectedPeripheral(deviceAddress)
    if (!started) {
        reconnectionCoordinator.cancelTimeout()
        reconnectionCoordinator.clearReconnectingDevice()
        if (connectionState == DeviceConnectionState.CONNECTING) setConnectionState(DeviceConnectionState.DISCONNECTED)
        return false
    }

    persistDisconnectDiagnostic(
        "source=$context.adoptSystemConnectedPeripheral, device=$deviceAddress, " +
            "bleState=${diagnostics.adapterState}, blePhase=${diagnostics.phase}, intent=$connectionIntent",
    )
    return true
}

// MARK: - BLE scanning

/** Starts scanning for nearby BLE devices. Scanning is orthogonal to the connection lifecycle. Cancel collection to stop scanning automatically. Ported from `startBLEScanning`. */
fun ConnectionManager.startBLEScanning(): Flow<DiscoveredDevice> = callbackFlow {
    bleScanJob?.cancel()
    bleScanRequestID += 1
    val requestID = bleScanRequestID

    bleScanJob = scope.launch {
        if (bleScanRequestID != requestID) return@launch
        stateMachine.setDeviceDiscoveredHandler { device -> trySend(device) }
        if (bleScanRequestID != requestID) return@launch
        stateMachine.startScanning()
    }

    awaitClose {
        scope.launch {
            if (bleScanRequestID != requestID) return@launch
            bleScanRequestID += 1
            bleScanJob?.cancel()
            bleScanJob = null
            stateMachine.setDeviceDiscoveredHandler { }
            stateMachine.stopScanning()
        }
    }
}

/** Manually stops BLE scanning. Ported from `stopBLEScanning`. */
suspend fun ConnectionManager.stopBLEScanning() {
    withContext(confinedDispatcher) { stopBLEScanningImpl() }
}

/** Body of [stopBLEScanning], callable directly by other already-confined callers (e.g. `pairNewDevice`) without a deadlock-risking nested `withContext(confinedDispatcher)`. */
internal fun ConnectionManager.stopBLEScanningImpl() {
    bleScanRequestID += 1
    bleScanJob?.cancel()
    bleScanJob = null
    stateMachine.setDeviceDiscoveredHandler { }
    stateMachine.stopScanning()
}

// MARK: - Reconnection watchdog

/**
 * Starts a watchdog that periodically retries connection when the user wants to be connected but
 * the device is stuck disconnected. Exponential backoff: 30s -> 60s -> 120s (capped). Ported from
 * `startReconnectionWatchdog`.
 */
internal fun ConnectionManager.startReconnectionWatchdogImpl() {
    stopReconnectionWatchdogImpl()

    reconnectionWatchdogGeneration += 1
    val generation = reconnectionWatchdogGeneration
    reconnectionWatchdogJob = scope.launch {
        try {
            var delayMillis = 30_000L
            val maxDelayMillis = 120_000L

            while (isActive) {
                delay(delayMillis)
                if (!isActive) return@launch

                if (!connectionIntent.wantsConnection || connectionState != DeviceConnectionState.DISCONNECTED) return@launch

                if (stateMachine.isBluetoothPoweredOff) {
                    delayMillis = (delayMillis * 2).coerceAtMost(maxDelayMillis)
                    continue
                }
                if (stateMachine.isAutoReconnecting) {
                    delayMillis = (delayMillis * 2).coerceAtMost(maxDelayMillis)
                    continue
                }

                checkBLEConnectionHealthImpl()

                delayMillis = (delayMillis * 2).coerceAtMost(maxDelayMillis)
            }
        } finally {
            if (reconnectionWatchdogGeneration == generation) reconnectionWatchdogJob = null
        }
    }
}

/** Stops the reconnection watchdog. Ported from `stopReconnectionWatchdog`. */
internal fun ConnectionManager.stopReconnectionWatchdogImpl() {
    reconnectionWatchdogGeneration += 1
    reconnectionWatchdogJob?.cancel()
    reconnectionWatchdogJob = null
}

// MARK: - BLE connection health

/**
 * Attempts BLE reconnection if the user expects to be connected but auto-reconnect gave up.
 * Called on foreground return and from the watchdog loop. Ported from `checkBLEConnectionHealth`
 * — the guard ladder's ordering is load-bearing, see the Swift original's doc for why.
 */
suspend fun ConnectionManager.checkBLEConnectionHealth() {
    withContext(confinedDispatcher) { checkBLEConnectionHealthImpl() }
}

internal suspend fun ConnectionManager.checkBLEConnectionHealthImpl() {
    if (currentTransportType != null && currentTransportType != TransportType.BLUETOOTH) return
    if (shouldDeferOpportunisticReconnect) return

    if (!connectionIntent.wantsConnection) return
    val deviceID = lastConnectedDeviceID ?: return
    val deviceAddress = deviceStore.fetchDeviceById(deviceID)?.bleAddress ?: return

    if (activeReconnectDeviceAddress == deviceAddress) return

    // A live BLE transport is only healthy if the session, services, and listeners are alive too.
    if (stateMachine.isConnected) {
        reconcileConnectedBLEAppStackImpl(deviceAddress)
        return
    }

    if (stateMachine.isAutoReconnecting) return
    if (stateMachine.isBluetoothPoweredOff) return

    // Detect stale connection state: app thinks connected but BLE is actually disconnected.
    if (connectionState.isConnected) {
        handleConnectionLossImpl(deviceAddress, null)
    }

    if (startAdoptingLastSystemConnectedPeripheralIfAvailableImpl(deviceAddress, "checkBLEConnectionHealth")) return

    if (isDeviceConnectedToOtherAppImpl(deviceAddress)) {
        if (reconnectionWatchdogJob == null) startReconnectionWatchdogImpl()
        return
    }

    attemptOpportunisticReconnectImpl(deviceAddress, "foreground health check")
}

private suspend fun ConnectionManager.reconcileConnectedBLEAppStackImpl(deviceAddress: String) {
    if (stateMachine.isAutoReconnecting) return

    val bleConnectedAddress = stateMachine.connectedDeviceAddress
    if (bleConnectedAddress != null && bleConnectedAddress != deviceAddress) return

    val currentServices = services
    val currentDevice = connectedDevice
    if (currentServices == null || session == null || currentDevice == null || currentDevice.bleAddress != deviceAddress) {
        rebuildConnectedBLEAppStackImpl(deviceAddress)
        return
    }

    if (!connectionState.isOperational) return

    reconcileConnectedBLEListenersImpl(currentServices, currentDevice.radioID)
}

private suspend fun ConnectionManager.rebuildConnectedBLEAppStackImpl(deviceAddress: String) {
    // Claim before any further suspension so a concurrent health check cannot both observe
    // connected and enter rebuild.
    if (activeReconnectDeviceAddress != null) return
    sessionRebuildDeviceAddress = deviceAddress
    try {
        setConnectionState(DeviceConnectionState.CONNECTING)
        try {
            rebuildSessionImpl(deviceAddress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            handleReconnectionFailureImpl()
        }
    } finally {
        if (sessionRebuildDeviceAddress == deviceAddress) sessionRebuildDeviceAddress = null
    }
}

/**
 * Reconciles a connected app stack whose event pipeline went quiet. Simplified from Swift's
 * `reconcileConnectedBLEListeners` (which checks a container-wide `isEventMonitoringActive` flag
 * this port's [ServiceContainer] doesn't track): [ServiceContainer.startEventMonitoring] is
 * idempotent (see its class doc), so unconditionally restarting it when auto-fetch looks stalled
 * is safe even when the rest of the pipeline was actually fine.
 */
private suspend fun ConnectionManager.reconcileConnectedBLEListenersImpl(services: ServiceContainer, radioID: UUID) {
    if (services.incomingMessageService.isAutoFetching) return
    services.startEventMonitoring(radioID)
}

/** Returns a best-effort snapshot of the BLE state machine for debug exports. Ported from `currentBLEDiagnosticsSummary`. */
suspend fun ConnectionManager.currentBLEDiagnosticsSummary(): String = withContext(confinedDispatcher) {
    val diagnostics = stateMachine.linkDiagnostics
    "BLE: state=${diagnostics.adapterState}, phase=${diagnostics.phase}, " +
        "peripheralState=${diagnostics.deviceState ?: "none"}, " +
        "isConnected=${stateMachine.isConnected}, isAutoReconnecting=${stateMachine.isAutoReconnecting}, " +
        "connectedDevice=${stateMachine.connectedDeviceAddress ?: "none"}, sessionPresent=${session != null}, " +
        "servicesPresent=${services != null}, " +
        "activeReconnectDevice=${reconnectionCoordinator.reconnectingDeviceAddress ?: "none"}, " +
        "sessionRebuildDevice=${sessionRebuildDeviceAddress ?: "none"}"
}
