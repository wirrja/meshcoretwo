// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

/**
 * User-actionable Bluetooth availability for the device pickers.
 *
 * Ported from `BluetoothAvailability.swift`. Collapses Android's Bluetooth adapter state (plus
 * runtime permission status) to the cases a person can act on: scanning can proceed, Bluetooth
 * is powered off, or the app is not authorized to use Bluetooth. Transient states map to
 * [READY] so a picker keeps showing its scanning state rather than a remedy the user cannot
 * act on.
 */
enum class BluetoothAvailability {
    /** Bluetooth is usable, or in a transient state expected to resolve on its own. */
    READY,

    /** Bluetooth is powered off; the user can turn it on in system settings. */
    POWERED_OFF,

    /** The app lacks Bluetooth/location permission; the user can grant it in system settings. */
    UNAUTHORIZED,
}
