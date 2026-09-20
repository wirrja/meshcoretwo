// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

/**
 * A BLE peripheral surfaced by a device-discovery scan.
 *
 * Ported from `DiscoveredDevice.swift`. Carries only what a picker needs — the MAC-address
 * identifier used to connect, the advertised name to show the user, and the signal strength.
 *
 * Unlike iOS's `CBPeripheral.identifier` (a per-app-install synthetic UUID CoreBluetooth mints
 * from the real MAC), Android's `BluetoothDevice.address` is the real, stable BLE MAC address —
 * the same value across apps and reinstalls. [id] is that address string (e.g. "AA:BB:CC:DD:EE:FF").
 */
data class DiscoveredDevice(
    /** The peripheral's MAC address. Becomes the device's connect identifier. */
    val id: String,
    /** The advertised local name, if the peripheral published one. */
    val name: String?,
    /** The received signal strength indicator, in dBm. */
    val rssi: Int,
)
