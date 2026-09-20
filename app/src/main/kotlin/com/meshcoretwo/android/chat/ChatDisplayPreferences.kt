// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * "Incoming Path"/"Incoming Hop Count"/"Incoming Region" toggles from Settings' Message Info
 * section — show a path/hop-count/region chip under incoming messages when enabled. Ported from
 * iOS's `AppStorageKey.showIncomingPath`/`showIncomingHopCount`/`showIncomingRegion`
 * (`MessagesSettingsSection.swift`), off by default like the Swift toggles.
 */
object ChatDisplayPreferences {
    private const val KEY_SHOW_INCOMING_PATH = "chat.showIncomingPath"
    private const val KEY_SHOW_INCOMING_HOP_COUNT = "chat.showIncomingHopCount"
    private const val KEY_SHOW_INCOMING_REGION = "chat.showIncomingRegion"

    fun isIncomingPathEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_SHOW_INCOMING_PATH, false)

    fun setIncomingPathEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_INCOMING_PATH, enabled) }
    }

    fun isIncomingHopCountEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_SHOW_INCOMING_HOP_COUNT, false)

    fun setIncomingHopCountEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_INCOMING_HOP_COUNT, enabled) }
    }

    fun isIncomingRegionEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_SHOW_INCOMING_REGION, false)

    fun setIncomingRegionEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_INCOMING_REGION, enabled) }
    }
}
