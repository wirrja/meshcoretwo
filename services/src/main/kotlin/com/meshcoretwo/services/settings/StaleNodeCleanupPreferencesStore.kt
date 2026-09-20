// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import android.content.Context
import androidx.core.content.edit
import com.meshcoretwo.services.notifications.NotificationPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Persists the Settings tab's stale-node auto-cleanup threshold and last-run timestamp. Ported
 * from `AppStorageKey.autoDeleteStaleNodesDays`/`lastStaleCleanupDate`
 * (`StaleNodeCleanupSection.swift`/`AppState.performStaleNodeCleanup`) — same shared `app_storage`
 * file as [com.meshcoretwo.services.notifications.NotificationPreferencesStore], matching iOS's
 * single `UserDefaults.standard` namespace. [lastCleanup] is stored under a different key than
 * Swift's `lastStaleCleanupDate` (epoch-second [Long] here vs. reference-date [Double] there) since
 * this port has no cross-platform backup format to stay binary-compatible with yet.
 *
 * Only the manual "change the threshold -> clean up now" path is wired — see
 * `SettingsViewModel.setStaleNodeCleanupThreshold`'s doc for why the automatic post-sync trigger
 * (`AppState.onDeviceSynced` -> `performStaleNodeCleanup()`, 3-hour cooldown) isn't ported here.
 */
class StaleNodeCleanupPreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NotificationPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private val _thresholdDays = MutableStateFlow(prefs.getInt(KEY_THRESHOLD_DAYS, 0))
    /** 0 = disabled; otherwise one of 7/14/30/90, matching `StaleNodeCleanupSection.swift`'s picker options. */
    val thresholdDays: StateFlow<Int> = _thresholdDays.asStateFlow()

    private val _lastCleanup = MutableStateFlow(
        prefs.getLong(KEY_LAST_CLEANUP_EPOCH_SECOND, 0L).takeIf { it > 0 }?.let(Instant::ofEpochSecond),
    )
    val lastCleanup: StateFlow<Instant?> = _lastCleanup.asStateFlow()

    fun setThresholdDays(days: Int) {
        prefs.edit { putInt(KEY_THRESHOLD_DAYS, days) }
        _thresholdDays.value = days
    }

    fun recordCleanupRun(instant: Instant = Instant.now()) {
        prefs.edit { putLong(KEY_LAST_CLEANUP_EPOCH_SECOND, instant.epochSecond) }
        _lastCleanup.value = instant
    }

    private companion object {
        /** Pinned to Swift's `AppStorageKey.autoDeleteStaleNodesDays` raw value. */
        const val KEY_THRESHOLD_DAYS = "autoDeleteStaleNodesDays"
        const val KEY_LAST_CLEANUP_EPOCH_SECOND = "lastStaleCleanupEpochSecond"
    }
}
