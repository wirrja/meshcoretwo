// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.persistence.DeviceDto

/** Test double for [ReconnectionDelegate], matching this codebase's established `Fake*Ops` convention. */
class FakeReconnectionDelegate : ReconnectionDelegate {
    override var connectionIntent: ConnectionIntent = ConnectionIntent.WantsConnection()

    // Backed by a differently-named field: a same-named `var` here would auto-generate a
    // `setConnectionState`/`setConnectedDevice` JVM accessor clashing with this class's own
    // `override fun setConnectionState`/`setConnectedDevice` below.
    private var _connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED
    override val connectionState: DeviceConnectionState get() = _connectionState

    private var _connectedDevice: DeviceDto? = null
    val connectedDevice: DeviceDto? get() = _connectedDevice

    val connectionStates = mutableListOf<DeviceConnectionState>()

    var teardownCallCount = 0
    var disconnectTransportCallCount = 0
    var notifyAutoReconnectStartedCallCount = 0
    var notifyConnectionLostCallCount = 0
    var handleReconnectionFailureCallCount = 0
    var stubbedIsTransportAutoReconnecting = false

    val rebuildSessionCalls = mutableListOf<String>()

    /** Called for every [rebuildSession] invocation; throw to simulate a failed rebuild. */
    var rebuildSessionHandler: suspend (String) -> Unit = {}

    override fun setConnectionState(state: DeviceConnectionState) {
        _connectionState = state
        connectionStates.add(state)
    }

    override fun setConnectedDevice(device: DeviceDto?) {
        _connectedDevice = device
    }

    override suspend fun teardownSessionForReconnect() {
        teardownCallCount++
    }

    override suspend fun rebuildSession(deviceAddress: String) {
        rebuildSessionCalls.add(deviceAddress)
        rebuildSessionHandler(deviceAddress)
    }

    override suspend fun disconnectTransport() {
        disconnectTransportCallCount++
    }

    override suspend fun notifyAutoReconnectStarted() {
        notifyAutoReconnectStartedCallCount++
    }

    override suspend fun notifyConnectionLost() {
        notifyConnectionLostCallCount++
    }

    override suspend fun handleReconnectionFailure() {
        handleReconnectionFailureCallCount++
    }

    override suspend fun isTransportAutoReconnecting(): Boolean = stubbedIsTransportAutoReconnecting
}
