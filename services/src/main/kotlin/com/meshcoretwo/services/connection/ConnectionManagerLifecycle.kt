// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.sync.SyncState
import com.meshcoretwo.services.transport.BleError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * App-lifecycle transitions and activation. Ported from `ConnectionManager+Lifecycle.swift`,
 * minus simulator mode and SwiftData migrations — see [ConnectionManager]'s class doc. WiFi's own
 * foreground health check ([checkWiFiConnectionHealth]) isn't called from [appDidBecomeActive]
 * here, matching Swift: `ConnectionManager+Lifecycle.swift`'s `appDidBecomeActive` only calls
 * `checkBLEConnectionHealth`, and `checkWiFiConnectionHealth` is called separately by the `app`
 * layer's own lifecycle hook (`AppState+Lifecycle.swift`) — not yet ported here (see
 * `MeshCoreTwoApplication.kt`, whose only current lifecycle call is `activate()` on launch).
 */

/** Called when the app enters background. Pauses foreground-only BLE operations. Ported from `appDidEnterBackground`. */
suspend fun ConnectionManager.appDidEnterBackground() {
    withContext(confinedDispatcher) {
        stateMachine.appDidEnterBackground()
        stopReconnectionWatchdogImpl()
    }
}

/** Called when the app becomes active. Reconciles BLE state and restarts foreground operations. Ported from `appDidBecomeActive`. */
suspend fun ConnectionManager.appDidBecomeActive() {
    withContext(confinedDispatcher) {
        stateMachine.appDidBecomeActive()
        checkBLEConnectionHealthImpl()

        if (currentTransportType != null && currentTransportType != TransportType.BLUETOOTH) return@withContext
        if (!connectionIntent.wantsConnection || connectionState != DeviceConnectionState.DISCONNECTED) return@withContext
        if (shouldDeferOpportunisticReconnect) return@withContext
        if (stateMachine.isAutoReconnecting) return@withContext

        startReconnectionWatchdogImpl()
    }
}

/** Triggers resync if connected but sync state is failed. Called on foreground return. Ported from `checkSyncHealth`. */
suspend fun ConnectionManager.checkSyncHealth() {
    withContext(confinedDispatcher) {
        val currentServices = services
        val radioID = connectedDevice?.radioID
        if (!connectionState.isOperational || !connectionIntent.wantsConnection || currentServices == null || radioID == null) return@withContext

        val syncState = currentServices.syncCoordinator.state.value
        if (syncState !is SyncState.Failed) return@withContext

        if (resyncJob != null) return@withContext

        startResyncLoopImpl(radioID, currentServices, TransportType.BLUETOOTH, forceFullSync = false)
    }
}

/**
 * Activates the connection manager on app launch. Call this once during app initialization.
 * Ported from `activate`, minus AccessorySetupKit activation (no Android equivalent — see this
 * class's doc), simulator mode, and one-time SwiftData migrations.
 */
suspend fun ConnectionManager.activate() {
    withContext(confinedDispatcher) {
        // Must complete before anything constructs the underlying platform BLE stack: a missed
        // handler at launch would leave a restored link without a session rebuild.
        wireTransportHandlersImpl()

        // A room session's `isConnected` flag reflects the previous process's BLE link, which
        // cannot have survived a fresh process start.
        remoteNodeSessionStore.resetAllConnections()

        if (connectionIntent.isUserDisconnected) return@withContext

        val lastDeviceID = lastConnectedDeviceID ?: return@withContext
        connectionIntent = ConnectionIntent.WantsConnection()

        val lastDevice = deviceStore.fetchDeviceById(lastDeviceID)

        // If the last device was reached over WiFi, try that first — see ConnectionManagerWiFi.kt.
        val wifiHost = lastDevice?.wifiHost
        val wifiPort = lastDevice?.wifiPort
        if (wifiHost != null && wifiPort != null) {
            try {
                connectViaWiFiImpl(wifiHost, wifiPort, forceFullSync = false)
                return@withContext
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Fall through to try BLE.
            }
        }

        val lastDeviceAddress = lastDevice?.bleAddress ?: return@withContext

        stateMachine.activate()

        // If the state machine is already auto-reconnecting (restoration), let it complete.
        if (stateMachine.isAutoReconnecting) return@withContext

        if (stateMachine.isConnected && stateMachine.connectedDeviceAddress == lastDeviceAddress) return@withContext

        // If the OS kept the BLE link alive across process death but restoration didn't fire,
        // adopt the system-connected peripheral rather than treating it as "connected elsewhere".
        if (startAdoptingLastSystemConnectedPeripheralIfAvailableImpl(lastDeviceAddress, "activate")) return@withContext

        // Silently skip per HIG: minimize interruptions on app launch.
        if (isDeviceConnectedToOtherAppImpl(lastDeviceAddress)) {
            startReconnectionWatchdogImpl()
            return@withContext
        }

        try {
            connect(lastDeviceAddress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.asBleError() is BleError.AuthenticationFailed) {
                surfaceAuthenticationFailure(lastDeviceAddress)
            }
            startReconnectionWatchdogImpl()
            // Don't propagate - auto-reconnect failure is not fatal.
        }
    }
}
