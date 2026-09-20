// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

/** Errors that can occur during connection operations, above the BLE transport layer. Ported from `ConnectionError.swift`. */
sealed class ConnectionError(message: String) : Exception(message) {
    data class ConnectionFailed(val reason: String) : ConnectionError("Connection failed: $reason")
    object DeviceNotFound : ConnectionError("Device not found")
    object NotConnected : ConnectionError("Not connected to device")
    data class InitializationFailed(val reason: String) : ConnectionError("Device initialization failed: $reason")
}
