// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live, editable notification preferences — the Settings screen's counterpart to
 * [NotificationPreferences] (the read-only snapshot [NotificationService] reads at notify-time).
 * This is the piece [NotificationPreferences]'s class doc calls out as iOS's `@Observable
 * NotificationPreferencesStore`, deferred until a settings screen existed to edit it (Phase 5
 * slice 7, Settings MVP). Writes go straight to the same `app_storage` file
 * [NotificationPreferences.load] reads, so [NotificationService] picks up a toggle change on its
 * very next notification with no extra plumbing — it re-reads that file fresh every time.
 *
 * Only the seven top-level toggles are exposed (not the three discovery-by-type sub-toggles
 * nested under "new contact discovered" on iOS) — see `SettingsScreen.kt`'s doc for what's
 * deferred in this first slice.
 */
class NotificationPreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NotificationPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(NotificationPreferences.load(prefs))
    val preferences: StateFlow<NotificationPreferences> = _preferences.asStateFlow()

    fun setContactMessagesEnabled(enabled: Boolean) =
        update(NotificationPreferences.Keys.CONTACT_MESSAGES, enabled) { copy(contactMessagesEnabled = enabled) }

    fun setChannelMessagesEnabled(enabled: Boolean) =
        update(NotificationPreferences.Keys.CHANNEL_MESSAGES, enabled) { copy(channelMessagesEnabled = enabled) }

    fun setRoomMessagesEnabled(enabled: Boolean) =
        update(NotificationPreferences.Keys.ROOM_MESSAGES, enabled) { copy(roomMessagesEnabled = enabled) }

    fun setNewContactDiscoveredEnabled(enabled: Boolean) =
        update(NotificationPreferences.Keys.NEW_CONTACTS, enabled) { copy(newContactDiscoveredEnabled = enabled) }

    fun setReactionNotificationsEnabled(enabled: Boolean) =
        update(NotificationPreferences.Keys.REACTIONS, enabled) { copy(reactionNotificationsEnabled = enabled) }

    fun setSoundEnabled(enabled: Boolean) =
        update(NotificationPreferences.Keys.SOUND, enabled) { copy(soundEnabled = enabled) }

    fun setLowBatteryEnabled(enabled: Boolean) =
        update(NotificationPreferences.Keys.LOW_BATTERY, enabled) { copy(lowBatteryEnabled = enabled) }

    private inline fun update(key: String, value: Boolean, transform: NotificationPreferences.() -> NotificationPreferences) {
        prefs.edit { putBoolean(key, value) }
        _preferences.value = _preferences.value.transform()
    }
}
