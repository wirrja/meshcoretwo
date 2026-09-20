// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the selected [Theme] and the global [AppColorSchemePreference]. Ported from
 * `ThemeService.swift`, minus its `StoreService` ownership gate — this port has no monetization
 * (project constraints), which on iOS itself is exactly the `#if SIDELOAD` branch of
 * `ThemeService.isAccessible`: every theme unconditionally accessible, `setCurrent` never throws,
 * and `availableToCurrentUser()`/`refreshFromUserDefaults`'s entitlement-revert path have no
 * Android counterpart (there is also no backup-restore feature yet to trigger the latter).
 *
 * [prefs] is injectable so the service is unit-testable in isolation, matching
 * `NotificationPreferencesStore`'s constructor convention.
 */
class ThemeService(private val prefs: SharedPreferences) {
    private val _current = MutableStateFlow(resolveThemeFromPrefs())
    val current: StateFlow<Theme> = _current.asStateFlow()

    private val _colorSchemePreference = MutableStateFlow(resolveSchemePreferenceFromPrefs())
    val colorSchemePreference: StateFlow<AppColorSchemePreference> = _colorSchemePreference.asStateFlow()

    fun setCurrent(theme: Theme) {
        _current.value = theme
        prefs.edit { putString(KEY_SELECTED_THEME_ID, theme.id) }
    }

    fun setColorSchemePreference(preference: AppColorSchemePreference) {
        _colorSchemePreference.value = preference
        prefs.edit { putString(KEY_APP_COLOR_SCHEME_PREFERENCE, preference.rawValue) }
    }

    /** Init contract: registry-based fallback only, no ownership to enforce. */
    private fun resolveThemeFromPrefs(): Theme {
        val storedID = prefs.getString(KEY_SELECTED_THEME_ID, null)
        if (storedID == null) return Theme.Default // missing key: default in memory, no write-back
        val theme = ThemeRegistry.theme(storedID)
        if (theme != null) return theme // adopt as-is; no write-back
        prefs.edit { putString(KEY_SELECTED_THEME_ID, Theme.Default.id) } // unknown ID: overwrite the stale value
        return Theme.Default
    }

    private fun resolveSchemePreferenceFromPrefs(): AppColorSchemePreference {
        val storedRaw = prefs.getString(KEY_APP_COLOR_SCHEME_PREFERENCE, null)
        if (storedRaw == null) return AppColorSchemePreference.SYSTEM // missing key: no write-back
        val preference = AppColorSchemePreference.fromRawValue(storedRaw)
        if (preference != null) return preference
        prefs.edit { putString(KEY_APP_COLOR_SCHEME_PREFERENCE, AppColorSchemePreference.SYSTEM.rawValue) }
        return AppColorSchemePreference.SYSTEM
    }

    private companion object {
        /** Bare-string keys, no `com.meshcoretwo.*` prefix — matching `PersistenceKeys.selectedThemeID`/
         * `appColorSchemePreference` on iOS 1:1 (`PersistenceKeys.swift`). */
        const val KEY_SELECTED_THEME_ID = "selectedThemeID"
        const val KEY_APP_COLOR_SCHEME_PREFERENCE = "appColorSchemePreference"
    }
}
