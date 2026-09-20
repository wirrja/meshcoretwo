// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.protocol.MockTransport
import com.meshcoretwo.services.transport.BleError
import com.meshcoretwo.services.transport.BleMeshTransportOps
import kotlinx.coroutines.flow.Flow

/**
 * Test double for [BleMeshTransportOps]. Wraps a real [MockTransport] (swappable, since
 * [MockTransport.disconnect] permanently closes its data channel — a fresh instance is installed
 * on every [switchDevice] and [reconnectMock], mirroring a real BLE link's fresh GATT connection)
 * for the protocol-level wire simulation [com.meshcoretwo.protocol.MeshCoreSession] needs, while
 * the BLE-specific surface (address/handlers) is simple recorded stubs a test can drive directly
 * — `ConnectionManager` never talks to a real state machine, so a real `BleStateMachine`
 * reconnection/discovery dance isn't needed here, unlike `FakeBleStateMachine` (the seam one layer
 * down, used by the not-yet-existing `BleMeshTransport` unit tests).
 */
class FakeBleMeshTransport : BleMeshTransportOps {
    var mock: MockTransport = MockTransport()
        private set

    var currentAddress: String? = null
        private set
    val setDeviceAddressCalls = mutableListOf<String>()
    val switchDeviceCalls = mutableListOf<String>()
    var refreshDataStreamCallCount = 0
        private set
    var disconnectCallCount = 0
        private set

    private var disconnectionHandler: ((String, BleError?) -> Unit)? = null
    private var reconnectionHandler: ((String) -> Unit)? = null

    override fun setDeviceAddress(address: String) {
        currentAddress = address
        setDeviceAddressCalls.add(address)
    }

    override fun setDisconnectionHandler(handler: (String, BleError?) -> Unit) {
        disconnectionHandler = handler
    }

    override fun setReconnectionHandler(handler: (String) -> Unit) {
        reconnectionHandler = handler
    }

    override suspend fun switchDevice(address: String) {
        switchDeviceCalls.add(address)
        currentAddress = address
        mock = MockTransport()
        mock.connect()
    }

    override suspend fun refreshDataStream() {
        refreshDataStreamCallCount++
    }

    override val receivedData: Flow<ByteArray> get() = mock.receivedData

    override suspend fun isConnected(): Boolean = mock.isConnected()

    override suspend fun connect() = mock.connect()

    override suspend fun disconnect() {
        disconnectCallCount++
        mock.disconnect()
    }

    override suspend fun send(data: ByteArray) = mock.send(data)

    /** Simulates a fresh BLE link taking over (e.g. after an auto-reconnect), as [FakeBleMeshTransport.switchDevice] does for a device switch. */
    fun reconnectMock(): MockTransport {
        mock = MockTransport()
        return mock
    }

    /** Fires the registered disconnection handler, as `BleMeshTransport` would on a real link drop. */
    fun simulateDisconnection(deviceAddress: String, error: BleError? = null) {
        disconnectionHandler?.invoke(deviceAddress, error)
    }

    /** Fires the registered reconnection-complete handler, as `BleMeshTransport` would after auto-reconnect. */
    fun simulateReconnectionComplete(deviceAddress: String) {
        reconnectionHandler?.invoke(deviceAddress)
    }
}
