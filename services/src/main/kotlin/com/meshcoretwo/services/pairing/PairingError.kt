// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.pairing

import com.meshcoretwo.services.connection.asBleError
import com.meshcoretwo.services.transport.BleError

/**
 * Errors surfaced by [com.meshcoretwo.services.connection.pairNewDevice] after discovery succeeds
 * but the connect ceremony fails. Ported from `PairingError.swift`; keyed by BLE MAC address
 * instead of the CoreBluetooth peripheral UUID (see `ConnectionManager`'s "Identifier translation"
 * doc).
 */
sealed class PairingError(message: String, val deviceAddress: String) : Exception(message) {
    /** Discovery succeeded but the BLE connection failed (e.g. wrong PIN). */
    class ConnectionFailed(deviceAddress: String, val underlying: Throwable) :
        PairingError("Connection failed: ${underlying.message}", deviceAddress)

    /** Discovery succeeded but the device is already connected to another app. */
    class DeviceConnectedToOtherApp(deviceAddress: String) :
        PairingError("Device is connected to another app.", deviceAddress)

    /**
     * True when the underlying BLE failure is an auth/encryption error. Detection goes through
     * [asBleError] (unwraps a `MeshTransportError`'s cause) — the single source of truth for which
     * failures map to [BleError.AuthenticationFailed] at the throw site.
     */
    val isAuthenticationFailure: Boolean
        get() = this is ConnectionFailed && underlying.asBleError() is BleError.AuthenticationFailed
}
