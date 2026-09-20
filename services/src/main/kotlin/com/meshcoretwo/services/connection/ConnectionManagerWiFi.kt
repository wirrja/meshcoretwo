// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.protocol.DeviceCapabilities
import com.meshcoretwo.protocol.MeshCoreSession
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.protocol.WiFiTransport
import com.meshcoretwo.protocol.WiFiTransportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * WiFi/TCP connection support: connecting, disconnection handling, a reconnect-with-backoff loop,
 * and a heartbeat that detects dead links the ESP32 TCP stack won't otherwise surface (it doesn't
 * answer TCP keepalives — see [WiFiTransport]'s class doc). Ported from
 * `ConnectionManager+WiFi.swift` — see [ConnectionManager]'s class doc for the file split.
 *
 * Unlike BLE ([com.meshcoretwo.services.transport.BleStateMachineOps]/
 * [com.meshcoretwo.services.transport.BleMeshTransportOps]), [WiFiTransport] has no injected
 * test-double seam: Swift's `ConnectionManager+WiFi.swift` constructs it directly too, and this
 * port keeps the same shape rather than introducing a `WiFiTransportOps` abstraction speculatively.
 * [com.meshcoretwo.protocol.WiFiTransportTest]/[com.meshcoretwo.protocol.WiFiFrameCodecTest] cover
 * the transport itself; the reconnect/heartbeat state machine below has no automated coverage of
 * its own — same real-device-only testing gap this port already accepts for BLE (see the project's
 * testing policy), just without BLE's `Fake*` infrastructure standing in for the unit-testable
 * parts. Add a `WiFiTransportOps` seam (matching [com.meshcoretwo.services.transport
 * .BleMeshTransportOps]'s precedent) if that gap needs closing later.
 */

private const val WIFI_HEARTBEAT_INTERVAL_MILLIS = 30_000L
private const val WIFI_RECONNECT_COOLDOWN_MILLIS = 35_000L
private const val WIFI_MAX_RECONNECT_DURATION_MILLIS = 30_000L

// MARK: - Public entry points

/**
 * Connects to a device via WiFi/TCP. Disconnects any existing connection first. Ported from
 * `connectViaWiFi`.
 */
suspend fun ConnectionManager.connectViaWiFi(host: String, port: Int, forceFullSync: Boolean = false) {
    withContext(confinedDispatcher) { connectViaWiFiImpl(host, port, forceFullSync) }
}

/**
 * Checks whether the WiFi connection is still alive — call on app foreground. Handles both a
 * transport that died while backgrounded (probes [WiFiTransport.isConnected]) and a connection
 * that already finished tearing down while backgrounded, attempting a fresh reconnect from the
 * last-persisted WiFi host/port. Ported from `checkWiFiConnectionHealth`.
 */
suspend fun ConnectionManager.checkWiFiConnectionHealth() {
    withContext(confinedDispatcher) { checkWiFiConnectionHealthImpl() }
}

// MARK: - Connection

/** Body of [connectViaWiFi]. Ported from `connectViaWiFi`. */
internal suspend fun ConnectionManager.connectViaWiFiImpl(host: String, port: Int, forceFullSync: Boolean) {
    val trimmedHost = host.trim()
    if (trimmedHost.isEmpty()) throw WiFiTransportError.InvalidHost

    if (connectionState != DeviceConnectionState.DISCONNECTED) {
        disconnectImpl(DisconnectReason.WIFI_RECONNECT_PREP)
    }

    connectionIntent = ConnectionIntent.WantsConnection()
    persistIntent()
    setConnectionState(DeviceConnectionState.CONNECTING)

    try {
        val newWifiTransport = WiFiTransport()
        newWifiTransport.setConnectionInfo(trimmedHost, port)
        wifiTransport = newWifiTransport

        newWifiTransport.connect()
        setConnectionState(DeviceConnectionState.CONNECTED)

        val newSession = MeshCoreSession(newWifiTransport, sessionConfiguration)
        session = newSession

        val (selfInfo, capabilities) = initializeSessionImpl(newSession)
        detectAndStorePlatformImpl(capabilities.model, TransportType.WIFI)

        val (newServices, radioID) = buildServicesAndSaveDeviceImpl(
            deviceAddress = null,
            session = newSession,
            selfInfo = selfInfo,
            capabilities = capabilities,
            wifiHost = trimmedHost,
            wifiPort = port,
        )
        val deviceID = connectedDevice!!.id
        persistConnection(deviceID, radioID, selfInfo.name)

        onConnectionReady?.invoke()
        val syncSucceeded = performInitialSyncImpl(radioID, newServices, TransportType.WIFI, forceFullSync = forceFullSync)

        // Wire disconnection handler before promotion — needed even if promotion fails.
        newWifiTransport.setDisconnectionHandler { error -> scope.launch { handleWiFiDisconnectionImpl(error) } }

        if (!promoteToReadyImpl(syncSucceeded, newServices, TransportType.WIFI)) return

        stopReconnectionWatchdogImpl()
        if (connectionState == DeviceConnectionState.READY) startWiFiHeartbeatImpl()
    } catch (error: Exception) {
        // Blanket catch (including cancellation) is deliberate, same as switchDeviceImpl's: a
        // partial connect would otherwise leave wifiTransport/session/connectedDevice pointing at
        // a half-built connection.
        val failedTransport = wifiTransport
        if (failedTransport != null) {
            failedTransport.disconnect()
            wifiTransport = null
        }
        currentTransportType = null
        cleanupConnection()
        throw error
    }
}

// MARK: - Disconnection handling

/** Handles unexpected loss of the WiFi connection. Ported from `handleWiFiDisconnection`. */
internal suspend fun ConnectionManager.handleWiFiDisconnectionImpl(error: Throwable?) {
    if (!connectionIntent.wantsConnection) return
    if (currentTransportType != TransportType.WIFI) return

    // Prevent re-entrant calls: multiple disconnection callbacks can fire simultaneously from the
    // transport handler and the heartbeat.
    if (isHandlingWiFiDisconnection || wifiReconnectJob != null) return
    isHandlingWiFiDisconnection = true
    try {
        stopWiFiHeartbeatImpl()
        cancelResyncLoopImpl()
        cancelChannelRetryImpl()

        // Capture and clear synchronously: a reconnect can install a new container during the
        // awaits below, and re-reading `services` would tear that new container down.
        val oldServices = services
        services = null
        session = null

        if (oldServices != null) {
            oldServices.remoteNodeService.handleBLEDisconnection()
            oldServices.syncCoordinator.onDisconnected(oldServices.notificationService)
            oldServices.tearDown()
        }

        setConnectionState(DeviceConnectionState.CONNECTING)
        startWiFiReconnectionImpl()
    } finally {
        isHandlingWiFiDisconnection = false
    }
}

// MARK: - Reconnection

/** Starts the WiFi reconnection retry loop. Ported from `startWiFiReconnection`. */
internal fun ConnectionManager.startWiFiReconnectionImpl() {
    if (wifiReconnectJob != null) return

    val lastStart = lastWiFiReconnectStartTimeMillis
    val now = System.currentTimeMillis()
    if (lastStart != null && now - lastStart < WIFI_RECONNECT_COOLDOWN_MILLIS) {
        scope.launch { cleanupConnection() }
        return
    }
    lastWiFiReconnectStartTimeMillis = now

    wifiReconnectAttempt = 0
    wifiReconnectJob?.cancel()

    wifiReconnectJob = scope.launch {
        // Only self-nil when not cancelled: this launch cancels the old job right above before
        // assigning a new one, and an unconditional self-nil in the cancelled old job's finally
        // would clobber the replacement job's own assignment.
        try {
            val startTimeMillis = System.currentTimeMillis()

            while (isActive && connectionIntent.wantsConnection) {
                val elapsedMillis = System.currentTimeMillis() - startTimeMillis
                if (elapsedMillis > WIFI_MAX_RECONNECT_DURATION_MILLIS) {
                    cleanupConnection()
                    return@launch
                }

                wifiReconnectAttempt += 1

                try {
                    reconnectWiFiImpl()
                    return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Retry with backoff below.
                }

                val delayMillis = (500.0 * 2.0.pow(wifiReconnectAttempt - 1)).toLong().coerceAtMost(4_000L)
                delay(delayMillis)
            }
        } finally {
            if (currentCoroutineContext()[Job]?.isCancelled != true) {
                wifiReconnectJob = null
                wifiReconnectAttempt = 0
            }
        }
    }
}

/** Cancels any WiFi reconnection in progress. Ported from `cancelWiFiReconnection`. */
internal fun ConnectionManager.cancelWiFiReconnectionImpl() {
    wifiReconnectJob?.cancel()
    wifiReconnectJob = null
    wifiReconnectAttempt = 0
}

/** Attempts to reconnect to the WiFi device using its currently-configured connection info. Ported from `reconnectWiFi`. */
private suspend fun ConnectionManager.reconnectWiFiImpl() {
    val currentWifiTransport = wifiTransport ?: throw ConnectionError.ConnectionFailed("No WiFi connection info")
    val (host, port) = currentWifiTransport.connectionInfo() ?: throw ConnectionError.ConnectionFailed("No WiFi connection info")

    // Stop any existing session to prevent receive loops racing for transport data.
    session?.stop()
    session = null

    currentWifiTransport.disconnect()

    val newTransport = WiFiTransport()
    newTransport.setConnectionInfo(host, port)
    wifiTransport = newTransport

    newTransport.connect()
    setConnectionState(DeviceConnectionState.CONNECTED)

    val newSession = MeshCoreSession(newTransport, sessionConfiguration)
    session = newSession

    val (selfInfo, capabilities) = initializeSessionImpl(newSession)

    completeWiFiReconnectionImpl(newSession, newTransport, host, port, selfInfo, capabilities)
}

/** Completes WiFi reconnection by re-establishing services. Ported from `completeWiFiReconnection`. */
private suspend fun ConnectionManager.completeWiFiReconnectionImpl(
    session: MeshCoreSession,
    transport: WiFiTransport,
    host: String,
    port: Int,
    selfInfo: SelfInfo,
    capabilities: DeviceCapabilities,
) {
    detectAndStorePlatformImpl(capabilities.model, TransportType.WIFI)

    val (newServices, radioID) = buildServicesAndSaveDeviceImpl(
        deviceAddress = null,
        session = session,
        selfInfo = selfInfo,
        capabilities = capabilities,
        wifiHost = host,
        wifiPort = port,
    )

    // Wire disconnection handler on the new transport.
    transport.setDisconnectionHandler { error -> scope.launch { handleWiFiDisconnectionImpl(error) } }

    onConnectionReady?.invoke()
    // onConnectionReady can suspend; a reentrant WiFi disconnect may clear connectedDevice or
    // replace services during that await. Recheck so an aborted reconnect bails here instead of
    // syncing a torn-down session.
    if (!connectionIntent.wantsConnection || services !== newServices || connectedDevice == null) {
        session.stop()
        newServices.tearDown()
        services = null
        connectedDevice = null
        allowedRepeatFreqRanges = emptyList()
        return
    }
    val syncSucceeded = performInitialSyncImpl(radioID, newServices, TransportType.WIFI, context = "WiFi reconnect")

    if (!promoteToReadyImpl(syncSucceeded, newServices, TransportType.WIFI)) return

    stopReconnectionWatchdogImpl()
    if (connectionState == DeviceConnectionState.READY) startWiFiHeartbeatImpl()
}

// MARK: - WiFi connection health

/** Body of [checkWiFiConnectionHealth]. Ported from `checkWiFiConnectionHealth`. */
internal suspend fun ConnectionManager.checkWiFiConnectionHealthImpl() {
    // If a reconnect is already running, let it finish.
    if (wifiReconnectJob != null) return

    // Case 1: we think we're connected but the transport died while backgrounded.
    val currentWifiTransport = wifiTransport
    if (currentTransportType == TransportType.WIFI && connectionState.isOperational && currentWifiTransport != null) {
        if (!currentWifiTransport.isConnected()) {
            handleWiFiDisconnectionImpl(null)
            return
        }
    }

    // Case 2: connection was lost and cleanup already ran while backgrounded, but the user still
    // wants to be connected — attempt a fresh reconnect from the last-persisted WiFi host/port.
    if (connectionState == DeviceConnectionState.DISCONNECTED && connectionIntent.wantsConnection) {
        val lastDeviceID = lastConnectedDeviceID ?: return
        val device = deviceStore.fetchDeviceById(lastDeviceID) ?: return
        val host = device.wifiHost
        val port = device.wifiPort
        if (host != null && port != null) {
            try {
                connectViaWiFiImpl(host, port, forceFullSync = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Best-effort, matching Swift's log-and-continue.
            }
        }
    }
}

// MARK: - Heartbeat

/** Skips the heartbeat probe while sync activity is in flight, mirroring `shouldPauseWiFiHeartbeatProbe`. */
internal val ConnectionManager.shouldPauseWiFiHeartbeatProbe: Boolean
    get() = connectionState == DeviceConnectionState.SYNCING || resyncJob != null || channelRetryJob != null

/**
 * Starts a periodic heartbeat that probes the WiFi link with a lightweight command — the ESP32 TCP
 * stack doesn't respond to TCP keepalives, so a dead connection otherwise goes undetected until the
 * next real command times out. Ported from `startWiFiHeartbeat`.
 */
internal fun ConnectionManager.startWiFiHeartbeatImpl() {
    stopWiFiHeartbeatImpl()

    wifiHeartbeatJob = scope.launch {
        while (isActive) {
            delay(WIFI_HEARTBEAT_INTERVAL_MILLIS)
            if (!isActive) break

            if (currentTransportType != TransportType.WIFI || !connectionState.isOperational) break
            val currentSession = session ?: break

            if (shouldPauseWiFiHeartbeatProbe) continue

            try {
                currentSession.getTime()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (shouldPauseWiFiHeartbeatProbe) continue
                handleWiFiDisconnectionImpl(error)
                break
            }
        }
    }
}

/** Stops the WiFi heartbeat loop. Ported from `stopWiFiHeartbeat`. */
internal fun ConnectionManager.stopWiFiHeartbeatImpl() {
    wifiHeartbeatJob?.cancel()
    wifiHeartbeatJob = null
}
