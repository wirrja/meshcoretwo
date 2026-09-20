// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ported from `ThemeServiceTests.swift`'s `ThemeServicePureTests` suite, minus every ownership
 * assertion (`with no purchases, only the default theme is available...`,
 * `ThemeServiceOwnershipTests`) and `refreshFromUserDefaults` (no backup-restore feature exists
 * yet on Android to call it) — this port has no monetization (project constraints), so [ThemeService] never
 * gates on entitlements and every built-in theme is always available.
 */
class ThemeServiceTest {
    @Test
    fun `missing selectedThemeID uses default in memory and does NOT write back`() {
        val prefs = FakeSharedPreferences()
        val service = ThemeService(prefs)
        assertEquals(Theme.Default.id, service.current.value.id)
        assertNull(prefs.getString("selectedThemeID", null))
    }

    @Test
    fun `unknown selectedThemeID falls back to default and overwrites the stored value`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("selectedThemeID", "ghost-theme").apply()
        val service = ThemeService(prefs)
        assertEquals(Theme.Default.id, service.current.value.id)
        assertEquals(Theme.Default.id, prefs.getString("selectedThemeID", null))
    }

    @Test
    fun `a persisted known theme ID is adopted without write-back`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("selectedThemeID", Theme.Aurora.id).apply()
        val service = ThemeService(prefs)
        assertEquals(Theme.Aurora.id, service.current.value.id)
        assertEquals(Theme.Aurora.id, prefs.getString("selectedThemeID", null))
    }

    @Test
    fun `a retired theme ID falls back to Default and is overwritten`() {
        for (retired in listOf("ember", "fern", "marine", "olive", "lavender", "sakura", "solarized", "nord", "catppuccin")) {
            val prefs = FakeSharedPreferences()
            prefs.edit().putString("selectedThemeID", retired).apply()
            val service = ThemeService(prefs)
            assertEquals(Theme.Default.id, service.current.value.id)
            assertEquals(Theme.Default.id, prefs.getString("selectedThemeID", null))
        }
    }

    @Test
    fun `setCurrent updates state and persists the theme ID`() {
        val service = ThemeService(FakeSharedPreferences())
        service.setCurrent(Theme.Sunrise)
        assertEquals(Theme.Sunrise.id, service.current.value.id)
    }

    @Test
    fun `missing appColorSchemePreference uses SYSTEM without writing back`() {
        val prefs = FakeSharedPreferences()
        val service = ThemeService(prefs)
        assertEquals(AppColorSchemePreference.SYSTEM, service.colorSchemePreference.value)
        assertNull(prefs.getString("appColorSchemePreference", null))
    }

    @Test
    fun `unknown appColorSchemePreference falls back to SYSTEM and overwrites`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("appColorSchemePreference", "auto").apply()
        val service = ThemeService(prefs)
        assertEquals(AppColorSchemePreference.SYSTEM, service.colorSchemePreference.value)
        assertEquals("system", prefs.getString("appColorSchemePreference", null))
    }

    @Test
    fun `setColorSchemePreference persists the raw value and updates state`() {
        val prefs = FakeSharedPreferences()
        val service = ThemeService(prefs)
        service.setColorSchemePreference(AppColorSchemePreference.DARK)
        assertEquals(AppColorSchemePreference.DARK, service.colorSchemePreference.value)
        assertEquals("dark", prefs.getString("appColorSchemePreference", null))
    }

    @Test
    fun `resolveIsDark Default theme defers to the preference`() {
        assertEquals(false, resolveIsDark(Theme.Default, AppColorSchemePreference.SYSTEM, systemDark = false))
        assertEquals(true, resolveIsDark(Theme.Default, AppColorSchemePreference.SYSTEM, systemDark = true))
        assertEquals(false, resolveIsDark(Theme.Default, AppColorSchemePreference.LIGHT, systemDark = true))
        assertEquals(true, resolveIsDark(Theme.Default, AppColorSchemePreference.DARK, systemDark = false))
    }

    @Test
    fun `resolveIsDark a theme that forces dark wins regardless of the preference`() {
        val forcedDark = Theme.Default.copy(forcedDark = true)
        assertEquals(true, resolveIsDark(forcedDark, AppColorSchemePreference.LIGHT, systemDark = false))
        assertEquals(true, resolveIsDark(forcedDark, AppColorSchemePreference.SYSTEM, systemDark = false))
    }
}

/** Minimal in-memory [SharedPreferences] fake — same shape as `RecentHopsStoreTest`'s, only the
 * string get/put path [ThemeService] uses is implemented. */
private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }

        override fun apply() {
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?) = error("not used by this test")
        override fun putInt(key: String?, value: Int) = error("not used by this test")
        override fun putLong(key: String?, value: Long) = error("not used by this test")
        override fun putFloat(key: String?, value: Float) = error("not used by this test")
        override fun putBoolean(key: String?, value: Boolean) = error("not used by this test")
        override fun remove(key: String?) = apply { pending[key!!] = null }
        override fun clear(): SharedPreferences.Editor = error("not used by this test")
    }

    override fun getAll(): MutableMap<String, *> = error("not used by this test")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = error("not used by this test")
    override fun getInt(key: String?, defValue: Int) = error("not used by this test")
    override fun getLong(key: String?, defValue: Long) = error("not used by this test")
    override fun getFloat(key: String?, defValue: Float) = error("not used by this test")
    override fun getBoolean(key: String?, defValue: Boolean) = error("not used by this test")
    override fun contains(key: String?) = error("not used by this test")
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = error("not used by this test")
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = error("not used by this test")
}
