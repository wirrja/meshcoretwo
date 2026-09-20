// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import android.bluetooth.BluetoothAdapter
import com.meshcoretwo.protocol.MeshCoreSession
import com.meshcoretwo.services.transport.BluetoothAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connect/disconnect/switch-device and BLE lifecycle-handler wiring. Ported from
 * `ConnectionManager.swift`'s `init`/`wireTransportHandlers` plus `ConnectionManager+BLE.swift`'s
 * connection-ceremony and connection-loss sections — see [ConnectionManager]'s class doc for the
 * file split. [disconnectImpl] also tears down [ConnectionManager.wifiTransport] when set — see
 * `ConnectionManagerWiFi.kt` for the WiFi connect/reconnect/heartbeat counterpart of this file.
 */

// MARK: - Public entry points

/**
 * Connects to a device, identified by its BLE MAC address (see [ConnectionManager]'s "Identifier
 * translation" doc — Android has no pre-connect domain identity to key on the way iOS's paired
 * peripheral UUID provides). Handles all connection scenarios: connects if disconnected, no-ops if
 * already connected to this device, switches if connected to a different one.
 *
 * @param forceReconnect When `true`, marks the connect as user-initiated: it bypasses the circuit
 *   breaker and bounds the retry budget to [ConnectionManager.UNVERIFIED_CONNECT_ATTEMPTS] (Android
 *   has no system pairing registry to pre-verify a saved device against — see this class's doc —
 *   so this budget always applies here, unlike Swift where it's conditional on platform).
 *   Background reconnects pass `false` to keep the full budget for unattended recovery.
 */
suspend fun ConnectionManager.connect(deviceAddress: String, forceFullSync: Boolean = false, forceReconnect: Boolean = false) {
    withContext(confinedDispatcher) { connectImpl(deviceAddress, forceFullSync, forceReconnect) }
}

/** Switches to a different device. See [connect]'s doc for identifier scope. */
suspend fun ConnectionManager.switchDevice(deviceAddress: String) {
    withContext(confinedDispatcher) { switchDeviceImpl(deviceAddress) }
}

/** Disconnects from the current device. */
suspend fun ConnectionManager.disconnect(reason: DisconnectReason = DisconnectReason.USER_INITIATED) {
    withContext(confinedDispatcher) { disconnectImpl(reason) }
}

/**
 * Single chokepoint for opportunistic reconnect attempts (Bluetooth powered back on, foreground
 * health check, ...). Ported from `attemptOpportunisticReconnect`.
 */
internal suspend fun ConnectionManager.attemptOpportunisticReconnectImpl(deviceAddress: String, reason: String) {
    if (shouldDeferOpportunisticReconnect) return
    try {
        connectImpl(deviceAddress, forceFullSync = false, forceReconnect = false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (error.asBleError() is com.meshcoretwo.services.transport.BleError.AuthenticationFailed) {
            surfaceAuthenticationFailure(deviceAddress)
        }
    }
}

/** Recovers the original [com.meshcoretwo.services.transport.BleError], whether thrown directly or wrapped as a `MeshTransportError`'s cause (see `BleMeshTransport.toMeshTransportError`'s doc). */
internal fun Throwable.asBleError(): com.meshcoretwo.services.transport.BleError? =
    this as? com.meshcoretwo.services.transport.BleError ?: cause as? com.meshcoretwo.services.transport.BleError

/** Body of [connect], callable directly by other already-confined callers (e.g. `pairNewDevice`) without a deadlock-risking nested `withContext(confinedDispatcher)`. */
internal suspend fun ConnectionManager.connectImpl(deviceAddress: String, forceFullSync: Boolean, forceReconnect: Boolean) {
    // Honor cancellation before any state mutation.
    currentCoroutineContext().ensureActive()

    if (!shouldAllowConnection(forceReconnect)) {
        throw com.meshcoretwo.services.transport.BleError.ConnectionFailed("Connection blocked by circuit breaker (cooling down)")
    }

    if (activeReconnectDeviceAddress == deviceAddress) {
        if (forceReconnect && connectionState == DeviceConnectionState.DISCONNECTED && sessionRebuildDeviceAddress != deviceAddress) {
            abandonStuckReconnectImpl(deviceAddress)
        } else {
            connectionIntent = ConnectionIntent.WantsConnection(forceFullSync)
            persistIntent()
            if (sessionRebuildDeviceAddress != deviceAddress) {
                reconnectionCoordinator.restartTimeout(deviceAddress)
            }
            return
        }
    }

    // Prevent concurrent connection attempts.
    if (connectionState == DeviceConnectionState.CONNECTING) {
        val currentAddress = activeConnectionAttemptDeviceAddress
        if (currentAddress == deviceAddress) {
            if (connectingDeviceAddress == null) {
                connectionIntent = ConnectionIntent.WantsConnection(forceFullSync)
                persistIntent()
                reconnectionCoordinator.restartTimeout(deviceAddress)
            }
            return
        }
        connectingDeviceAddress = null
        reconnectionCoordinator.cancelTimeout()
        reconnectionCoordinator.clearReconnectingDevice()
        cancelResyncLoopImpl()
        stopReconnectionWatchdogImpl()
        cleanupResources()
        transport.disconnect()
        setConnectionState(DeviceConnectionState.DISCONNECTED)
    }

    // Handle already-connected cases.
    if (connectionState != DeviceConnectionState.DISCONNECTED) {
        if (connectedDevice?.bleAddress == deviceAddress) return
        switchDeviceImpl(deviceAddress)
        return
    }

    // Claim the attempt before the pre-connect suspensions below — a superseded call observes
    // the mismatch after each one and bails via CancellationException, mirroring Swift's
    // `throw CancellationError()` guards.
    if (connectingDeviceAddress == deviceAddress) return
    connectingDeviceAddress = deviceAddress

    val transportAutoReconnecting = stateMachine.isAutoReconnecting
    if (connectingDeviceAddress != deviceAddress) throw CancellationException("connect superseded")
    if (transportAutoReconnecting) {
        val restoringAddress = stateMachine.connectedDeviceAddress
        if (connectingDeviceAddress != deviceAddress) throw CancellationException("connect superseded")

        if (restoringAddress != deviceAddress) {
            transport.disconnect()
            if (connectingDeviceAddress != deviceAddress) throw CancellationException("connect superseded")
        } else if (forceReconnect && connectionState == DeviceConnectionState.DISCONNECTED) {
            abandonStuckReconnectImpl(deviceAddress)
            if (connectingDeviceAddress != deviceAddress) throw CancellationException("connect superseded")
        } else {
            // Same device - let auto-reconnect complete instead of racing with it.
            connectingDeviceAddress = null
            connectionIntent = ConnectionIntent.WantsConnection(forceFullSync)
            persistIntent()
            if (connectionState != DeviceConnectionState.CONNECTING) setConnectionState(DeviceConnectionState.CONNECTING)
            reconnectionCoordinator.restartTimeout(deviceAddress)
            return
        }
    }

    // If reconnecting to the last radio and the OS kept a system-level BLE link alive, adopt it.
    if (isLastConnectedDevice(deviceAddress)) {
        connectionIntent = ConnectionIntent.WantsConnection(forceFullSync)
        persistIntent()

        if (startAdoptingLastSystemConnectedPeripheralIfAvailableImpl(deviceAddress, "connect")) {
            if (connectingDeviceAddress == deviceAddress) connectingDeviceAddress = null
            return
        }
        if (connectingDeviceAddress != deviceAddress) throw CancellationException("connect superseded")
    }

    if (isDeviceConnectedToOtherAppImpl(deviceAddress)) {
        if (connectingDeviceAddress == deviceAddress) connectingDeviceAddress = null
        throw com.meshcoretwo.services.transport.BleError.DeviceConnectedToOtherApp
    }
    if (connectingDeviceAddress != deviceAddress) throw CancellationException("connect superseded")

    connectionIntent = ConnectionIntent.WantsConnection(forceFullSync)
    persistIntent()
    setConnectionState(DeviceConnectionState.CONNECTING)

    reconnectionCoordinator.cancelTimeout()
    reconnectionCoordinator.clearReconnectingDevice()

    try {
        val maxAttempts = if (forceReconnect) ConnectionManager.UNVERIFIED_CONNECT_ATTEMPTS else ConnectionManager.DEFAULT_CONNECT_ATTEMPTS
        connectWithRetryImpl(deviceAddress, maxAttempts)
    } catch (error: Exception) {
        if (connectingDeviceAddress != deviceAddress) throw error
        connectingDeviceAddress = null
        setConnectionState(DeviceConnectionState.DISCONNECTED)
        throw error
    }
    connectingDeviceAddress = null
}

/**
 * Break-glass teardown for a user-initiated connect that found a stuck reconnect: the UI already
 * abandoned the cycle but the OS pending connect never resolved. Ported from `abandonStuckReconnect`.
 */
private suspend fun ConnectionManager.abandonStuckReconnectImpl(deviceAddress: String) {
    reconnectionCoordinator.cancelTimeout()
    reconnectionCoordinator.clearReconnectingDevice()
    transport.disconnect()
}

/** Connects with retry logic for reconnection scenarios. Ported from `connectWithRetry`. */
private suspend fun ConnectionManager.connectWithRetryImpl(deviceAddress: String, maxAttempts: Int) {
    var lastError: Throwable = ConnectionError.ConnectionFailed("Unknown error")

    for (attempt in 1..maxAttempts) {
        if (connectingDeviceAddress != deviceAddress) throw CancellationException("connect superseded")

        try {
            performConnectionImpl(deviceAddress)
            recordConnectionSuccess()
            return
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastError = error
            if (connectingDeviceAddress != deviceAddress) throw error

            // BLE precondition failures won't resolve between retries. Exit without retrying or
            // tripping the circuit breaker so a Bluetooth-powered-on recovery can reconnect cleanly.
            // Auth failures also bypass retry — the bond is bad, the user has to intervene.
            when (error.asBleError()) {
                is com.meshcoretwo.services.transport.BleError.BluetoothPoweredOff,
                is com.meshcoretwo.services.transport.BleError.BluetoothUnavailable,
                is com.meshcoretwo.services.transport.BleError.BluetoothUnauthorized,
                -> throw error
                is com.meshcoretwo.services.transport.BleError.AuthenticationFailed -> {
                    cleanupResources()
                    transport.disconnect()
                    throw error
                }
                else -> {}
            }

            cleanupResources()
            transport.disconnect()

            if (attempt < maxAttempts) {
                val baseDelaySeconds = 0.3 * Math.pow(2.0, (attempt - 1).toDouble())
                val jitterSeconds = Math.random() * 0.1 * baseDelaySeconds
                delay(((baseDelaySeconds + jitterSeconds) * 1000).toLong())
            }
        }
    }

    recordConnectionFailure()
    throw lastError
}

/** Performs the actual connection to a device. Ported from `performConnection`. */
private suspend fun ConnectionManager.performConnectionImpl(deviceAddress: String) {
    session?.stop()
    session = null

    transport.setDeviceAddress(deviceAddress)
    transport.connect()

    setConnectionState(DeviceConnectionState.CONNECTED)

    val newSession = MeshCoreSession(transport, sessionConfiguration)
    session = newSession

    val (selfInfo, capabilities) = initializeSessionImpl(newSession)

    // Configure write pacing before any further BLE round trips this connect path makes
    // (buildServicesAndSaveDeviceImpl's getRepeatFreq call included) — reordered relative to
    // Swift, which knows the bond-verification key (deviceID == peripheral UUID) up front;
    // here neither pacing nor bond verification can run before capabilities/selfInfo arrive.
    configureBLEPacingImpl(capabilities)

    val (newServices, radioID) = buildServicesAndSaveDeviceImpl(deviceAddress, newSession, selfInfo, capabilities)
    val deviceID = connectedDevice!!.id

    recordBondVerification(deviceID, deviceAddress)
    persistConnection(deviceID, radioID, selfInfo.name)

    onConnectionReady?.invoke()

    val shouldForceFullSync = when (val intent = connectionIntent) {
        is ConnectionIntent.WantsConnection -> {
            val force = intent.forceFullSync
            if (force) connectionIntent = ConnectionIntent.WantsConnection()
            force
        }
        else -> false
    }
    val syncSucceeded = performInitialSyncImpl(radioID, newServices, forceFullSync = shouldForceFullSync)

    if (!promoteToReadyImpl(syncSucceeded, newServices, TransportType.BLUETOOTH)) {
        newSession.stop()
        return
    }

    stopReconnectionWatchdogImpl()
}

/** Switches to a different device. Ported from `switchDevice`. */
private suspend fun ConnectionManager.switchDeviceImpl(deviceAddress: String) {
    lastCleanChannelSync = null
    lastAttemptedChannelSync = null
    connectionIntent = ConnectionIntent.WantsConnection()
    persistIntent()

    try {
        cancelResyncLoopImpl()
        services?.let { it.syncCoordinator.onDisconnected(it.notificationService) }
        services?.tearDown()
        services = null
        session?.stop()
        stateMachine.setAppSessionLive(null)

        setConnectionState(DeviceConnectionState.CONNECTING)
        transport.switchDevice(deviceAddress)
        setConnectionState(DeviceConnectionState.CONNECTED)

        val newSession = MeshCoreSession(transport, sessionConfiguration)
        session = newSession

        val (selfInfo, capabilities) = initializeSessionImpl(newSession)
        configureBLEPacingImpl(capabilities)

        val (newServices, radioID) = buildServicesAndSaveDeviceImpl(deviceAddress, newSession, selfInfo, capabilities)
        val deviceID = connectedDevice!!.id
        recordBondVerification(deviceID, deviceAddress)
        persistConnection(deviceID, radioID, selfInfo.name)

        onConnectionReady?.invoke()
        val syncSucceeded = performInitialSyncImpl(radioID, newServices, forceFullSync = true, context = "Device switch")

        if (!promoteToReadyImpl(syncSucceeded, newServices, TransportType.BLUETOOTH)) return

        resetPreserveBudgetAfterDeviceSwitch()
        stopReconnectionWatchdogImpl()
    } catch (error: Exception) {
        // Blanket catch (including cancellation) is deliberate: a partial switch would otherwise
        // leave services/session/connectedDevice pointing at the old device with state stuck on
        // CONNECTING/CONNECTED, so this cleanup must run regardless of why the switch stopped.
        cleanupConnection()
        transport.disconnect()
        onConnectionLost?.invoke()
        throw error
    }
}

/** Body of [disconnect], callable directly by other already-confined callers (e.g. `forgetDevice`) without a deadlock-risking nested `withContext(confinedDispatcher)`. Ported from `disconnect(reason:)`. */
internal suspend fun ConnectionManager.disconnectImpl(reason: DisconnectReason) {
    reconnectionCoordinator.cancelTimeout()
    reconnectionCoordinator.clearReconnectingDevice()
    connectingDeviceAddress = null

    cancelWiFiReconnectionImpl()
    stopWiFiHeartbeatImpl()
    stopReconnectionWatchdogImpl()
    cancelResyncLoopImpl()
    cancelChannelRetryImpl()

    when (reason) {
        DisconnectReason.USER_INITIATED, DisconnectReason.STATUS_MENU_DISCONNECT_TAP, DisconnectReason.FORGET_DEVICE,
        DisconnectReason.DEVICE_REMOVED_FROM_SETTINGS, DisconnectReason.FACTORY_RESET, DisconnectReason.SWITCHING_DEVICE,
        -> {
            connectionIntent = ConnectionIntent.UserDisconnected
            persistIntent()
            surfacedAuthFailureDeviceAddress = null
            lastCleanChannelSync = null
            lastAttemptedChannelSync = null
        }
        DisconnectReason.RESYNC_FAILED, DisconnectReason.WIFI_ADDRESS_CHANGE, DisconnectReason.WIFI_RECONNECT_PREP,
        DisconnectReason.PAIRING_FAILED,
        -> {
            // Preserve .wantsConnection so a health check can retry.
        }
    }

    val oldServices = services
    val oldSession = session
    services = null
    session = null
    setConnectionState(DeviceConnectionState.DISCONNECTED)
    connectingDeviceAddress = null
    val disconnectedAddress = connectedDevice?.bleAddress
    connectedDevice = null
    allowedRepeatFreqRanges = emptyList()

    if (oldServices != null) {
        oldServices.remoteNodeService.handleBLEDisconnection()
        sessionsAwaitingReauth = mutableSetOf()
        oldServices.tearDown()
        oldServices.syncCoordinator.onDisconnected(oldServices.notificationService)
    }

    oldSession?.stop()

    val oldWifiTransport = wifiTransport
    if (oldWifiTransport != null) {
        oldWifiTransport.disconnect()
        wifiTransport = null
    } else {
        transport.disconnect()
    }
    currentTransportType = null

    persistDisconnectDiagnostic(
        "source=disconnect(reason), reason=${reason.description}, device=${disconnectedAddress ?: "none"}, intent=$connectionIntent",
    )
}

/** Handles unexpected connection loss. Ported from `handleConnectionLoss`. */
internal suspend fun ConnectionManager.handleConnectionLossImpl(deviceAddress: String, error: com.meshcoretwo.services.transport.BleError?) {
    if (connectionState == DeviceConnectionState.CONNECTING) {
        val activeAddress = connectingDeviceAddress ?: reconnectionCoordinator.reconnectingDeviceAddress
        if (activeAddress != null && activeAddress != deviceAddress) return
    }

    reconnectionCoordinator.cancelTimeout()
    reconnectionCoordinator.clearReconnectingDevice()
    cancelResyncLoopImpl()

    val oldServices = services
    services = null
    session = null

    setConnectionState(DeviceConnectionState.DISCONNECTED)
    if (connectingDeviceAddress == deviceAddress) connectingDeviceAddress = null
    connectedDevice = null
    allowedRepeatFreqRanges = emptyList()

    if (oldServices != null) {
        oldServices.remoteNodeService.handleBLEDisconnection()
        oldServices.tearDown()
        oldServices.syncCoordinator.onDisconnected(oldServices.notificationService)
    }

    persistDisconnectDiagnostic(
        "source=handleConnectionLoss, device=$deviceAddress, error=${error?.message ?: "none"}, intent=$connectionIntent",
    )

    onConnectionLost?.invoke()

    if (error is com.meshcoretwo.services.transport.BleError.AuthenticationFailed) {
        surfaceAuthenticationFailure(deviceAddress)
    }

    if (connectionIntent.wantsConnection) {
        startReconnectionWatchdogImpl()
    }
}

/** Publishes user-actionable Bluetooth availability. Ported from `handleBluetoothStateChange`. */
internal fun ConnectionManager.handleBluetoothStateChangeImpl(state: Int) {
    bluetoothAvailability = when (state) {
        BluetoothAdapter.STATE_OFF -> BluetoothAvailability.POWERED_OFF
        else -> BluetoothAvailability.READY
    }
}

// MARK: - Transport handler wiring

/**
 * Wires the transport and state-machine lifecycle handlers. Ported from `wireTransportHandlers`.
 * Every handler hops onto [ConnectionManager.scope] (confined to the same single-threaded
 * dispatcher [connect]/etc. run on) before touching any state — these callbacks otherwise run on
 * the state machine's own coroutine scope, not this class's confined one.
 */
internal suspend fun ConnectionManager.wireTransportHandlersImpl() {
    transport.setDisconnectionHandler { deviceAddress, error ->
        scope.launch { handleConnectionLossImpl(deviceAddress, error) }
    }

    // A bond verified in a previous launch must still shield an exhausted encryption-timeout
    // budget, so seed the persisted verification before any teardown classification can run.
    val bondDeviceID = lastConnectionStore.bondVerifiedDeviceID
    if (bondDeviceID != null) {
        val verified = lastConnectionStore.bondVerificationDate(bondDeviceID)
        val bondAddress = deviceStore.fetchDeviceById(bondDeviceID)?.bleAddress
        if (verified != null && bondAddress != null) {
            stateMachine.recordBondVerification(bondAddress, verified.toEpochMilli())
        }
    }

    stateMachine.setAutoReconnectingHandler { deviceAddress, errorInfo ->
        scope.launch { handleAutoReconnectingEntryImpl(deviceAddress, errorInfo) }
    }

    // Reconnection completion is signaled by the transport (not the state machine directly) so
    // the data stream is captured before this handler runs — see BleMeshTransport's class doc.
    transport.setReconnectionHandler { deviceAddress ->
        scope.launch { reconnectionCoordinator.handleReconnectionComplete(deviceAddress) }
    }

    stateMachine.setBondRefreshedHandler { deviceAddress ->
        scope.launch { handleBondRefreshedImpl(deviceAddress) }
    }

    stateMachine.setBluetoothPoweredOnHandler {
        scope.launch { handleBluetoothPoweredOnImpl() }
    }

    stateMachine.setBluetoothStateChangeHandler { state ->
        scope.launch { handleBluetoothStateChangeImpl(state) }
    }
}

private suspend fun ConnectionManager.handleAutoReconnectingEntryImpl(deviceAddress: String, errorInfo: String) {
    if (shouldDeferOpportunisticReconnect) {
        // Skip the reconnect-cycle claim and UI timeout (pairing owns the next state
        // transitions once slice D exists), but tear down the prior session so an early-exit
        // pairing path doesn't strand the UI on stale ready state.
        handleConnectionLossImpl(deviceAddress, null)
        return
    }

    val manualConnectAddress = connectingDeviceAddress
    if (manualConnectAddress != null) {
        if (manualConnectAddress != deviceAddress) return
        // The dropped link belongs to the in-flight manual connect; release its claim so the
        // retry loop bails out instead of disconnecting the transport. The cycle claimed below
        // owns teardown, the UI timeout, and the session rebuild from here.
        connectingDeviceAddress = null
    }

    reconnectionCoordinator.handleEnteringAutoReconnect(deviceAddress)

    val diagnostics = stateMachine.linkDiagnostics
    persistDisconnectDiagnostic(
        "source=bleStateMachine.autoReconnectingHandler, device=$deviceAddress, " +
            "bleState=${diagnostics.adapterState}, blePhase=${diagnostics.phase}, " +
            "blePeripheralState=${diagnostics.deviceState ?: "none"}, error=$errorInfo, intent=$connectionIntent",
    )
}

private suspend fun ConnectionManager.handleBondRefreshedImpl(deviceAddress: String) {
    val deviceID = connectedDevice?.takeIf { it.bleAddress == deviceAddress }?.id ?: return
    persistBondRefreshIfStillValid(deviceID, deviceAddress)
}

private suspend fun ConnectionManager.handleBluetoothPoweredOnImpl() {
    if (!connectionIntent.wantsConnection) return
    if (connectionState != DeviceConnectionState.DISCONNECTED) return
    val deviceID = lastConnectedDeviceID ?: return
    if (shouldDeferOpportunisticReconnect) return
    val deviceAddress = deviceStore.fetchDeviceById(deviceID)?.bleAddress ?: return

    val diagnostics = stateMachine.linkDiagnostics
    val bleConnectedAddress = stateMachine.connectedDeviceAddress
    if (diagnostics.phase != com.meshcoretwo.services.transport.BlePhaseKind.IDLE || bleConnectedAddress == deviceAddress) return

    if (activeReconnectDeviceAddress == deviceAddress) return

    attemptOpportunisticReconnectImpl(deviceAddress, "Bluetooth powered on")
}
