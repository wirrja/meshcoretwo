// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Errors that can occur at the transport layer.
 *
 * These errors indicate problems with the underlying transport connection (e.g., Bluetooth LE),
 * rather than protocol-level errors.
 */
sealed class MeshTransportError : Exception() {
    /** The transport is not connected. */
    data object NotConnected : MeshTransportError()

    /** A connection attempt failed with a specific reason. */
    data class ConnectionFailed(val description: String) : MeshTransportError()

    /** Sending data failed with a specific reason. */
    data class SendFailed(val description: String) : MeshTransportError()

    /** The target device could not be found. */
    data object DeviceNotFound : MeshTransportError()

    /** A required service was not found on the device. */
    data object ServiceNotFound : MeshTransportError()

    /** A required characteristic was not found on the device. */
    data object CharacteristicNotFound : MeshTransportError()
}
