// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

/**
 * Errors that can occur during BLE operations.
 *
 * Ported from `BLEError.swift`. Kept as a distinct type from
 * [com.meshcoretwo.protocol.MeshTransportError] (the platform-neutral contract `MeshTransport`
 * callers see) because this carries BLE-specific detail (pairing, authentication, characteristic
 * discovery) the protocol layer has no business knowing about; [BleMeshTransport] maps a
 * [BleError] to the nearest [com.meshcoretwo.protocol.MeshTransportError] at the `MeshTransport`
 * boundary.
 */
sealed class BleError(message: String) : Exception(message) {
    object BluetoothUnavailable : BleError("Bluetooth is not available on this device.")
    object BluetoothUnauthorized : BleError("Bluetooth permission is required. Please enable it in Settings.")
    object BluetoothPoweredOff : BleError("Bluetooth is turned off. Please enable Bluetooth to connect.")
    object DeviceNotFound : BleError("Device not found. Please make sure it's powered on and nearby.")
    data class ConnectionFailed(val reason: String) : BleError("Connection failed: $reason")
    object ConnectionTimeout : BleError("Connection timed out. Please try again.")
    object NotConnected : BleError("Not connected to a device.")
    object CharacteristicNotFound : BleError("Unable to communicate with device. Please try reconnecting.")
    data class WriteError(val reason: String) : BleError("Failed to send data: $reason")
    object OperationTimeout : BleError("Operation timed out. Please try again.")
    object AuthenticationFailed : BleError("Authentication failed. Please check your device's PIN.")
    data class PairingFailed(val reason: String) : BleError("Bluetooth pairing failed: $reason")
    object DeviceConnectedToOtherApp : BleError(
        "This device is connected to another app. Only one app can use a mesh radio at a time to prevent communication issues.",
    )
}
