// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.device

import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.DeviceStore
import java.util.UUID

/**
 * Service for managing device-level data and settings persistence that doesn't require MeshCore
 * communication. Ported from `DeviceService.swift` — currently just OCV settings, the only
 * method the (deliberately tiny) Swift service has; device creation/activation itself is
 * `ConnectionManager`'s job (Phase 3, not yet ported), which is why [DeviceStore] rather than
 * this class owns `saveDevice`/`setActiveDevice`.
 */
class DeviceService(private val dataStore: DeviceStore) {
    /** Called after a device update is persisted, so a connection manager can refresh its cached copy. */
    private var onDeviceUpdated: (suspend (DeviceDto) -> Unit)? = null

    fun setDeviceUpdateCallback(callback: suspend (DeviceDto) -> Unit) {
        onDeviceUpdated = callback
    }

    /**
     * Updates the OCV (open-circuit voltage) preset for a device.
     *
     * @param customArray Comma-separated OCV array string; required when [preset] is `"custom"`.
     * @throws DeviceServiceError.DeviceNotFound if no device matches [deviceId].
     * @throws DeviceServiceError.PersistenceFailed if saving the update fails.
     */
    suspend fun updateOCVSettings(deviceId: UUID, preset: String, customArray: String?) {
        val device = dataStore.fetchDeviceById(deviceId) ?: throw DeviceServiceError.DeviceNotFound
        val updated = device.copy(ocvPreset = preset, customOCVArrayString = customArray)

        try {
            dataStore.saveDevice(updated)
        } catch (error: Exception) {
            throw DeviceServiceError.PersistenceFailed(error.message ?: error.toString())
        }

        onDeviceUpdated?.invoke(updated)
    }
}
