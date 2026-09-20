// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import android.content.Context
import androidx.core.content.edit
import com.meshcoretwo.services.notifications.NotificationPreferences
import java.util.UUID

/** GPS fix source for Settings' Auto-Update Location. Ported from `GPSSource.swift`. */
enum class GPSSource { PHONE, DEVICE }

/**
 * Per-device Settings preferences that live entirely on the phone, not the radio — ported from
 * `DevicePreferenceStore.swift`. Same shared `app_storage` file as
 * [com.meshcoretwo.services.notifications.NotificationPreferencesStore]/
 * [StaleNodeCleanupPreferencesStore], keyed additionally by device [UUID] (Swift keys the same
 * way — per-`deviceID` `UserDefaults` keys rather than a separate file per device — since a phone
 * may pair with more than one radio over its lifetime).
 */
class DevicePreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NotificationPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoUpdateLocationEnabled(deviceId: UUID): Boolean = prefs.getBoolean(autoUpdateLocationKey(deviceId), false)

    fun setAutoUpdateLocationEnabled(enabled: Boolean, deviceId: UUID) {
        prefs.edit { putBoolean(autoUpdateLocationKey(deviceId), enabled) }
    }

    /** Defaults to [GPSSource.PHONE] when unset — see [hasSetGpsSource] for distinguishing that from an explicit choice. */
    fun gpsSource(deviceId: UUID): GPSSource {
        val raw = prefs.getString(gpsSourceKey(deviceId), null) ?: return GPSSource.PHONE
        return GPSSource.entries.find { it.name == raw } ?: GPSSource.PHONE
    }

    /** Whether [gpsSource] has ever been explicitly set for this device, as opposed to just defaulting to [GPSSource.PHONE]. */
    fun hasSetGpsSource(deviceId: UUID): Boolean = prefs.contains(gpsSourceKey(deviceId))

    fun setGpsSource(source: GPSSource, deviceId: UUID) {
        prefs.edit { putString(gpsSourceKey(deviceId), source.name) }
    }

    private fun autoUpdateLocationKey(deviceId: UUID) = "device.$deviceId.autoUpdateLocation"
    private fun gpsSourceKey(deviceId: UUID) = "device.$deviceId.gpsSource"
}
