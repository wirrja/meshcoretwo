// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import com.meshcoretwo.services.persistence.DeviceDto

/**
 * Filters saved [DeviceDto] rows to those [DeviceSelectionScreen] can actually offer to connect
 * to. Ported from `DeviceSelectionFilter.isConnectable` (`DeviceSelectionSheet.swift`), simplified:
 * Swift checks a saved row against the system pairing registry (AccessorySetupKit) to catch
 * backup-restored "shadow" devices whose Bluetooth method was stripped by `cleanedForImport()`.
 * Android has no such registry to check against — see `ConnectionManagerPairing.kt`'s class doc —
 * so it falls straight to Swift's own `hasSystemPairingRegistry = false` (macOS) branch: a saved
 * row's own [DeviceDto.bleAddress]/[DeviceDto.wifiHost] presence is reachability enough, the same
 * signal Swift's macOS path uses. [DeviceDto.isGhost] (Android's stand-in for a demoted/shadow
 * row — see `DeviceStore.demoteDeviceToGhost`) is excluded outright, matching Swift's
 * `cleanedForImport()` shadow rows carrying no connection method at all.
 */
object DeviceSelectionFilter {
    fun isSelectable(device: DeviceDto): Boolean =
        !device.isGhost && (device.bleAddress != null || device.wifiHost != null)
}
