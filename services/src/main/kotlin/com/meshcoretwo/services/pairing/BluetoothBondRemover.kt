// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.pairing

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Outcome of dropping a saved device's system Bluetooth bond. */
enum class BondRemovalResult {
    /** No bond to drop: a WiFi device, no stored BLE address, or the phone isn't bonded to it. */
    NOT_BONDED,

    /** The system accepted the unpair request. */
    REMOVED,

    /** The bond is still there — the user has to "Forget" the device in system Bluetooth settings. */
    FAILED,
}

/**
 * Drops the phone's Bluetooth bond with [deviceAddress], so a later "Add device" pairs from scratch
 * (PIN prompt included) instead of reusing a stale bond. No iOS counterpart: iOS apps can't touch
 * system pairings at all. Android has no public API for this either — `BluetoothDevice.removeBond()`
 * is hidden, so it's called reflectively and every failure (missing method, hidden-API block,
 * missing permission, `false` return) is reported as [BondRemovalResult.FAILED] for the UI to
 * fall back on system settings.
 */
internal fun removeBluetoothBond(context: Context, deviceAddress: String): BondRemovalResult {
    if (!BluetoothAdapter.checkBluetoothAddress(deviceAddress)) return BondRemovalResult.NOT_BONDED
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return BondRemovalResult.FAILED
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
        return BondRemovalResult.FAILED
    }
    return try {
        val device = adapter.getRemoteDevice(deviceAddress)
        if (device.bondState == BluetoothDevice.BOND_NONE) return BondRemovalResult.NOT_BONDED
        val removed = BluetoothDevice::class.java.getMethod("removeBond").invoke(device) as? Boolean
        if (removed == true) BondRemovalResult.REMOVED else BondRemovalResult.FAILED
    } catch (error: Exception) {
        // SecurityException (no BLUETOOTH_CONNECT), reflective failures, hidden-API denial.
        BondRemovalResult.FAILED
    }
}
