// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from `AppColorSchemePreferenceTests.swift`. */
class AppColorSchemePreferenceTest {
    @Test
    fun `raw values are pinned to the on-disk format`() {
        assertEquals("system", AppColorSchemePreference.SYSTEM.rawValue)
        assertEquals("light", AppColorSchemePreference.LIGHT.rawValue)
        assertEquals("dark", AppColorSchemePreference.DARK.rawValue)
    }

    @Test
    fun `forcedDark maps system to null and light-dark to their booleans`() {
        assertNull(AppColorSchemePreference.SYSTEM.forcedDark)
        assertEquals(false, AppColorSchemePreference.LIGHT.forcedDark)
        assertEquals(true, AppColorSchemePreference.DARK.forcedDark)
    }

    @Test
    fun `entries is exactly system, light, dark`() {
        assertEquals(listOf(AppColorSchemePreference.SYSTEM, AppColorSchemePreference.LIGHT, AppColorSchemePreference.DARK), AppColorSchemePreference.entries)
    }

    @Test
    fun `fromRawValue resolves known values and returns null for unknown`() {
        assertEquals(AppColorSchemePreference.DARK, AppColorSchemePreference.fromRawValue("dark"))
        assertNull(AppColorSchemePreference.fromRawValue("auto"))
    }
}
