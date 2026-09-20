// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.device

/** Errors [DeviceService] can throw. Ported from `DeviceServiceError.swift`. */
sealed class DeviceServiceError(message: String) : Exception(message) {
    object DeviceNotFound : DeviceServiceError("Device not found")

    data class PersistenceFailed(val reason: String) : DeviceServiceError("Failed to save device settings: $reason")
}
