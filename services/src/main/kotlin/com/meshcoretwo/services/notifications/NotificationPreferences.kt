// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import android.content.Context
import android.content.SharedPreferences

/**
 * Read-only snapshot of notification preferences. Ported from `NotificationPreferences.swift`.
 * Backed by a plain (unencrypted) [SharedPreferences] file — matches iOS `UserDefaults.standard`,
 * which stores these same booleans with no Keychain-level protection either. Key strings are
 * pinned to the Swift `AppStorageKey` case names (its `rawValue` defaults to the case name) so a
 * future settings-backup format can round-trip against the same on-disk keys as iOS where it
 * matters for parity, and so a rename here can't silently orphan a user's existing choice.
 *
 * `badgeEnabled`/badge-count tracking is dropped entirely: stock Android has no
 * `UIApplication.applicationIconBadgeNumber` equivalent — badge counts are launcher/OEM-specific
 * (Samsung, some Huawei launchers) and out of scope for a GMS-less target (see project constraints). Most
 * launchers already derive a badge dot from the count of active notifications, which this
 * service's normal posting behavior satisfies for free.
 *
 * [NotificationPreferencesStore]'s `@Observable` two-way-binding counterpart (used by the Settings
 * screen for live toggle editing) is Phase 5 UI and not ported here.
 */
data class NotificationPreferences(
    val contactMessagesEnabled: Boolean,
    val channelMessagesEnabled: Boolean,
    val roomMessagesEnabled: Boolean,
    val newContactDiscoveredEnabled: Boolean,
    val discoveryContactEnabled: Boolean,
    val discoveryRepeaterEnabled: Boolean,
    val discoveryRoomEnabled: Boolean,
    val reactionNotificationsEnabled: Boolean,
    val soundEnabled: Boolean,
    val lowBatteryEnabled: Boolean,
) {
    // A plain (non-companion) nested object, deliberately: a type nested inside a `companion
    // object` is only reachable as `NotificationPreferences.Companion.Keys`, not the shorter
    // `NotificationPreferences.Keys` this class's own `load()` (and every caller) uses.
    object Keys {
        const val CONTACT_MESSAGES = "notifyContactMessages"
        const val CHANNEL_MESSAGES = "notifyChannelMessages"
        const val ROOM_MESSAGES = "notifyRoomMessages"
        const val NEW_CONTACTS = "notifyNewContacts"
        const val NEW_CONTACTS_CONTACT = "notifyNewContactsContact"
        const val NEW_CONTACTS_REPEATER = "notifyNewContactsRepeater"
        const val NEW_CONTACTS_ROOM = "notifyNewContactsRoom"
        const val REACTIONS = "notifyReactions"
        const val SOUND = "notificationSoundEnabled"
        const val LOW_BATTERY = "notifyLowBattery"
    }

    companion object {
        /** Matches iOS `UserDefaults.standard` — one shared, unencrypted preferences file. */
        const val PREFS_NAME = "app_storage"

        /** Shared default for every notification toggle, matching `AppStorageKey.defaultNotificationEnabled`. */
        private const val DEFAULT_ENABLED = true

        fun load(prefs: SharedPreferences): NotificationPreferences {
            fun flag(key: String) = prefs.getBoolean(key, DEFAULT_ENABLED)
            return NotificationPreferences(
                contactMessagesEnabled = flag(Keys.CONTACT_MESSAGES),
                channelMessagesEnabled = flag(Keys.CHANNEL_MESSAGES),
                roomMessagesEnabled = flag(Keys.ROOM_MESSAGES),
                newContactDiscoveredEnabled = flag(Keys.NEW_CONTACTS),
                discoveryContactEnabled = flag(Keys.NEW_CONTACTS_CONTACT),
                discoveryRepeaterEnabled = flag(Keys.NEW_CONTACTS_REPEATER),
                discoveryRoomEnabled = flag(Keys.NEW_CONTACTS_ROOM),
                reactionNotificationsEnabled = flag(Keys.REACTIONS),
                soundEnabled = flag(Keys.SOUND),
                lowBatteryEnabled = flag(Keys.LOW_BATTERY),
            )
        }

        fun load(context: Context): NotificationPreferences =
            load(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
