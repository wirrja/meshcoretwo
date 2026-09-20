// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import android.content.SharedPreferences
import androidx.core.content.edit
import com.meshcoretwo.services.notifications.NotificationPreferences

/**
 * Snapshot of the `app_storage` [SharedPreferences] keys this port actually persists, used for
 * backup export/import. Ported from `BackupUserDefaults.swift`, trimmed to the 13 keys with a real
 * Android home today — the Swift source also snapshots ~25 more keys (map style/filters,
 * chat/composer display toggles, discovery/nodes sort order, trace-path view mode, link previews,
 * frequent/recent emoji, `hasCompletedOnboarding`'s onboarding-path variant, `liveActivityEnabled`)
 * for *features* that don't exist on Android yet (see PLAN.md's "Backup/restore — план среза",
 * BackupUserDefaults sub-slice, for the full property-by-property survey) — nothing to snapshot
 * until those features themselves are ported. `notificationBadgeEnabled` is dropped for the same
 * reason [NotificationPreferences] already drops it (no stock-Android badge-count API).
 *
 * `hasCompletedOnboarding` and `hasSeenRepeaterDragHint` also exist on Android, but under
 * `AppContainer`'s separate connection-infrastructure `SharedPreferences` file, not `app_storage` —
 * both are write-once flags a user restoring a backup has already tripped by the time Settings (and
 * this feature) are reachable, so a write-if-missing restore of either can never actually fire in
 * this port's navigation flow. Left unported rather than adding a second `SharedPreferences` file
 * dependency to this class for a restore path that's dead code by construction.
 *
 * `regionSelection` has no persistence at all yet on Android — `AppViewModel` holds it in memory
 * only (see its class doc) — so porting it means building that persistence layer first, out of
 * scope here.
 *
 * Each property is nullable — `null` means the key was unset at export time, mirroring Swift's
 * `nil`. Unlike [com.meshcoretwo.android.ui.theme.ThemeService]/`NotificationPreferencesStore`'s own
 * cached `StateFlow`s, [restore] writes straight to [SharedPreferences] and does not refresh either
 * service's in-memory state — same already-accepted gap as backup/restore's other sub-slices not
 * live-reloading contacts/messages/channels after import; a restored preference takes effect on
 * this screen's/service's next fresh read (typically the next app launch), not mid-session.
 */
data class BackupUserDefaults(
    val selectedThemeID: String? = null,
    val appColorSchemePreference: String? = null,
    val notifyContactMessages: Boolean? = null,
    val notifyChannelMessages: Boolean? = null,
    val notifyRoomMessages: Boolean? = null,
    val notifyNewContacts: Boolean? = null,
    val notifyNewContactsContact: Boolean? = null,
    val notifyNewContactsRepeater: Boolean? = null,
    val notifyNewContactsRoom: Boolean? = null,
    val notifyReactions: Boolean? = null,
    val notificationSoundEnabled: Boolean? = null,
    val notifyLowBattery: Boolean? = null,
    val autoDeleteStaleNodesDays: Int? = null,
) {
    /**
     * Write-if-missing restore, matching Swift's `restore(to:)`: only fills keys not already
     * present, so restoring never clobbers a choice this install's user already made.
     * @return `true` if any key was newly written (mirrors Swift's non-empty `addedKeys`).
     */
    fun restore(prefs: SharedPreferences): Boolean {
        var wroteAny = false
        prefs.edit {
            fun putStringIfMissing(key: String, value: String?) {
                if (value != null && !prefs.contains(key)) {
                    putString(key, value)
                    wroteAny = true
                }
            }
            fun putBooleanIfMissing(key: String, value: Boolean?) {
                if (value != null && !prefs.contains(key)) {
                    putBoolean(key, value)
                    wroteAny = true
                }
            }
            putStringIfMissing(KEY_SELECTED_THEME_ID, selectedThemeID)
            putStringIfMissing(KEY_APP_COLOR_SCHEME_PREFERENCE, appColorSchemePreference)
            putBooleanIfMissing(NotificationPreferences.Keys.CONTACT_MESSAGES, notifyContactMessages)
            putBooleanIfMissing(NotificationPreferences.Keys.CHANNEL_MESSAGES, notifyChannelMessages)
            putBooleanIfMissing(NotificationPreferences.Keys.ROOM_MESSAGES, notifyRoomMessages)
            putBooleanIfMissing(NotificationPreferences.Keys.NEW_CONTACTS, notifyNewContacts)
            putBooleanIfMissing(NotificationPreferences.Keys.NEW_CONTACTS_CONTACT, notifyNewContactsContact)
            putBooleanIfMissing(NotificationPreferences.Keys.NEW_CONTACTS_REPEATER, notifyNewContactsRepeater)
            putBooleanIfMissing(NotificationPreferences.Keys.NEW_CONTACTS_ROOM, notifyNewContactsRoom)
            putBooleanIfMissing(NotificationPreferences.Keys.REACTIONS, notifyReactions)
            putBooleanIfMissing(NotificationPreferences.Keys.SOUND, notificationSoundEnabled)
            putBooleanIfMissing(NotificationPreferences.Keys.LOW_BATTERY, notifyLowBattery)
            if (autoDeleteStaleNodesDays != null && !prefs.contains(KEY_AUTO_DELETE_STALE_NODES_DAYS)) {
                putInt(KEY_AUTO_DELETE_STALE_NODES_DAYS, autoDeleteStaleNodesDays)
                wroteAny = true
            }
        }
        return wroteAny
    }

    companion object {
        /** Bare-string keys, pinned to [com.meshcoretwo.android.ui.theme.ThemeService]'s own (private) key constants. */
        private const val KEY_SELECTED_THEME_ID = "selectedThemeID"
        private const val KEY_APP_COLOR_SCHEME_PREFERENCE = "appColorSchemePreference"

        /** Pinned to `StaleNodeCleanupPreferencesStore`'s own (private) key constant. */
        private const val KEY_AUTO_DELETE_STALE_NODES_DAYS = "autoDeleteStaleNodesDays"

        /** Creates a snapshot by reading every known key from [prefs]. A missing key snapshots as `null`. */
        fun snapshot(prefs: SharedPreferences): BackupUserDefaults = BackupUserDefaults(
            selectedThemeID = prefs.getString(KEY_SELECTED_THEME_ID, null),
            appColorSchemePreference = prefs.getString(KEY_APP_COLOR_SCHEME_PREFERENCE, null),
            notifyContactMessages = prefs.getBooleanOrNull(NotificationPreferences.Keys.CONTACT_MESSAGES),
            notifyChannelMessages = prefs.getBooleanOrNull(NotificationPreferences.Keys.CHANNEL_MESSAGES),
            notifyRoomMessages = prefs.getBooleanOrNull(NotificationPreferences.Keys.ROOM_MESSAGES),
            notifyNewContacts = prefs.getBooleanOrNull(NotificationPreferences.Keys.NEW_CONTACTS),
            notifyNewContactsContact = prefs.getBooleanOrNull(NotificationPreferences.Keys.NEW_CONTACTS_CONTACT),
            notifyNewContactsRepeater = prefs.getBooleanOrNull(NotificationPreferences.Keys.NEW_CONTACTS_REPEATER),
            notifyNewContactsRoom = prefs.getBooleanOrNull(NotificationPreferences.Keys.NEW_CONTACTS_ROOM),
            notifyReactions = prefs.getBooleanOrNull(NotificationPreferences.Keys.REACTIONS),
            notificationSoundEnabled = prefs.getBooleanOrNull(NotificationPreferences.Keys.SOUND),
            notifyLowBattery = prefs.getBooleanOrNull(NotificationPreferences.Keys.LOW_BATTERY),
            autoDeleteStaleNodesDays = if (prefs.contains(KEY_AUTO_DELETE_STALE_NODES_DAYS)) prefs.getInt(KEY_AUTO_DELETE_STALE_NODES_DAYS, 0) else null,
        )

        private fun SharedPreferences.getBooleanOrNull(key: String): Boolean? = if (contains(key)) getBoolean(key, false) else null
    }
}
