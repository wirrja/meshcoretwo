// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.pairing

/**
 * Platform-neutral control-flow signals from the device-pairing seam ([BleScanPairingService] and
 * [com.meshcoretwo.services.connection.pairNewDevice]), so app-layer call sites handle pairing
 * cancellation and re-entry without depending on a platform discovery API. Ported from
 * `DevicePairingError.swift`. Android has no AccessorySetupKit equivalent — every case here is
 * always the "no system pairing registry" branch (Swift's `BluetoothScanPairingService`/macOS
 * path); there is no second implementation to translate a platform error into these.
 */
sealed class DevicePairingError(message: String) : Exception(message) {
    /** The user dismissed the discovery picker. A benign cancellation, not a failure. */
    object Cancelled : DevicePairingError("Device selection was cancelled.")

    /** A pairing flow is already running; the re-entrant request was ignored. */
    object AlreadyInProgress : DevicePairingError("Device pairing is already in progress.")
}
