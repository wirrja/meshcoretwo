// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

import com.meshcoretwo.protocol.MeshTransportError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * BLE transport backed by [BleStateMachine], conforming to `MeshTransport` for the `protocol`
 * layer. Ported from `iOSBLETransport.swift`.
 *
 * Swift stores the current data stream behind an `OSAllocatedUnfairLock` so
 * [BleStateMachine]'s reconnection callback — which there might otherwise need to spawn a `Task`
 * to hop back into actor isolation — can capture a fresh stream synchronously. Android's
 * reconnection callback already runs inside a coroutine on the state machine's own scope (see
 * [BleStateMachine.onReconnection]), so a plain `@Volatile` field is sufficient here: the write
 * happens on that coroutine, and [receivedData]'s read is a single volatile load, needing no
 * lock of its own.
 *
 * `BleStateMachine` is injected rather than defaulted, unlike Swift's
 * `init(stateMachine: BLEStateMachine? = nil)`: Android has no equivalent implicit "just make one
 * with a Context" default without this class reaching for a `Context` itself, so ownership and
 * lifecycle of the single [BleStateMachine] instance are left to the caller (e.g. a DI module in
 * `app`).
 */
class BleMeshTransport(private val stateMachine: BleStateMachine) : BleMeshTransportOps {
    @Volatile
    private var deviceAddress: String? = null

    @Volatile
    private var currentDataFlow: Flow<ByteArray>? = null

    @Volatile
    private var reconnectionHandler: ((String) -> Unit)? = null

    init {
        stateMachine.setReconnectionHandler { deviceAddress, flow ->
            // Capture the stream before invoking the caller's handler, mirroring
            // iOSBLETransport's composition — the handler may read receivedData immediately.
            currentDataFlow = flow
            reconnectionHandler?.invoke(deviceAddress)
        }
    }

    override fun setDeviceAddress(address: String) {
        deviceAddress = address
    }

    override fun setDisconnectionHandler(handler: (String, BleError?) -> Unit) {
        stateMachine.setDisconnectionHandler(handler)
    }

    override fun setReconnectionHandler(handler: (String) -> Unit) {
        reconnectionHandler = handler
    }

    override val receivedData: Flow<ByteArray>
        get() = currentDataFlow ?: emptyFlow()

    override suspend fun isConnected(): Boolean = stateMachine.isConnected

    /**
     * Connects to the configured device. Idempotent if already connected to that same device;
     * throws if connected to a different one instead — use [switchDevice] to change devices.
     */
    override suspend fun connect() {
        val connectedAddress = stateMachine.connectedDeviceAddress
        val targetAddress = deviceAddress ?: connectedAddress ?: throw MeshTransportError.DeviceNotFound

        if (stateMachine.isConnected) {
            if (connectedAddress == targetAddress) return
            throw MeshTransportError.ConnectionFailed(
                "Already connected to different device: $connectedAddress. Use switchDevice() instead.",
            )
        }

        val flow = try {
            stateMachine.connect(targetAddress)
        } catch (error: BleError) {
            throw error.toMeshTransportError()
        }
        currentDataFlow = flow
    }

    override suspend fun disconnect() {
        stateMachine.disconnect()
        currentDataFlow = null
    }

    override suspend fun send(data: ByteArray) {
        try {
            stateMachine.send(data)
        } catch (error: BleError) {
            throw error.toMeshTransportError()
        }
    }

    override suspend fun refreshDataStream() {
        val flow = stateMachine.renewDataStream() ?: return
        currentDataFlow = flow
    }

    override suspend fun switchDevice(address: String) {
        deviceAddress = address
        stateMachine.disconnect()
        currentDataFlow = null
        val flow = try {
            stateMachine.connect(address)
        } catch (error: BleError) {
            throw error.toMeshTransportError()
        }
        currentDataFlow = flow
    }
}

/**
 * Converts to the protocol layer's transport-agnostic error type. [MeshTransportError] has no
 * case for most of [BleError]'s variants (it's shared with a future TCP/WiFi transport), so the
 * original is attached via [Throwable.initCause] rather than discarded — `ConnectionManager`'s
 * retry policy needs to distinguish "don't retry, Bluetooth is off" and "don't retry, the bond is
 * invalid" from a generically retryable failure, which the collapsed [MeshTransportError] case
 * alone can't tell apart.
 */
private fun BleError.toMeshTransportError(): MeshTransportError {
    val transportError = when (this) {
        is BleError.BluetoothUnavailable -> MeshTransportError.ConnectionFailed("Bluetooth is not available on this device.")
        is BleError.BluetoothUnauthorized -> MeshTransportError.ConnectionFailed("Bluetooth permission is required.")
        is BleError.BluetoothPoweredOff -> MeshTransportError.ConnectionFailed("Bluetooth is turned off.")
        is BleError.DeviceNotFound -> MeshTransportError.DeviceNotFound
        is BleError.ConnectionFailed -> MeshTransportError.ConnectionFailed(reason)
        is BleError.ConnectionTimeout -> MeshTransportError.ConnectionFailed("Connection timed out.")
        is BleError.NotConnected -> MeshTransportError.NotConnected
        is BleError.CharacteristicNotFound -> MeshTransportError.CharacteristicNotFound
        is BleError.WriteError -> MeshTransportError.SendFailed(reason)
        is BleError.OperationTimeout -> MeshTransportError.SendFailed("Operation timed out.")
        is BleError.AuthenticationFailed -> MeshTransportError.ConnectionFailed("Authentication failed.")
        is BleError.PairingFailed -> MeshTransportError.ConnectionFailed("Bluetooth pairing failed: $reason")
        is BleError.DeviceConnectedToOtherApp -> MeshTransportError.ConnectionFailed(
            "Device is connected to another app.",
        )
    }
    transportError.initCause(this)
    return transportError
}
