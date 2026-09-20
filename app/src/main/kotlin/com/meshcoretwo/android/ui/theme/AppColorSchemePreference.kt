// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

/**
 * Global app appearance preference, independent of which [Theme] is selected. [rawValue] is
 * pinned (persisted under [ThemeService]'s `appColorSchemePreference` key) — renaming an entry
 * must not change the on-disk format.
 *
 * Ported from `AppColorSchemePreference.swift`.
 */
enum class AppColorSchemePreference(val rawValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    /** `null` means "defer to the system" — mirrors Swift's `colorScheme: ColorScheme?`. */
    val forcedDark: Boolean?
        get() = when (this) {
            SYSTEM -> null
            LIGHT -> false
            DARK -> true
        }

    companion object {
        fun fromRawValue(rawValue: String): AppColorSchemePreference? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
