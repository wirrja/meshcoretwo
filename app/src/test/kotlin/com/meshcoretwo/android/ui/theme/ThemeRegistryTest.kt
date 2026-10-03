// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from `ThemeRegistryTests.swift`, minus its `productID`/`StoreCatalog` assertions — this
 * port has no monetization (project constraints), so every theme is a plain built-in with no product to own.
 */
class ThemeRegistryTest {
    @Test
    fun `classicThemes holds the five built-ins, Default first`() {
        assertEquals(listOf("default", "aurora", "sunrise", "graphite", "ultraviolet"), ThemeRegistry.classicThemes.map { it.id })
    }

    @Test
    fun `allThemes is the classic five followed by the fifteen experimental themes`() {
        assertEquals(15, ThemeRegistry.experimentalThemes.size)
        assertEquals(ThemeRegistry.classicThemes + ThemeRegistry.experimentalThemes, ThemeRegistry.allThemes)
    }

    @Test
    fun `only experimental themes carry a group, and every group is populated`() {
        assertTrue(ThemeRegistry.classicThemes.all { it.group == null })
        assertTrue(ThemeRegistry.experimentalThemes.all { it.group != null })
        assertTrue(ThemeRegistry.experimentalByGroup().all { (_, themes) -> themes.isNotEmpty() })
        assertEquals(ThemeRegistry.experimentalThemes.size, ThemeRegistry.experimentalByGroup().sumOf { it.second.size })
    }

    @Test
    fun `theme(id) resolves known IDs and returns null for unknown`() {
        assertEquals(Theme.Default.id, ThemeRegistry.theme("default")?.id)
        assertEquals("aurora", ThemeRegistry.theme("aurora")?.id)
        assertEquals("night", ThemeRegistry.theme("night")?.id)
        assertNull(ThemeRegistry.theme("ember"))
        assertNull(ThemeRegistry.theme("does-not-exist"))
    }

    @Test
    fun `no classic theme forces a color scheme`() {
        assertTrue(ThemeRegistry.classicThemes.all { it.forcedDark == null })
    }

    @Test
    fun `Night, Phosphor and Neon are dark-only, Daylight light-only, and each ships one scheme`() {
        val forced = ThemeRegistry.allThemes.filter { it.forcedDark != null }.associate { it.id to it.forcedDark }
        assertEquals(mapOf("night" to true, "daylight" to false, "phosphor" to true, "neon" to true), forced)
        for (theme in ThemeRegistry.allThemes.filter { it.forcedDark != null }) {
            assertTrue("${theme.id} must use one scheme for both appearances", theme.lightScheme === theme.darkScheme)
        }
    }

    @Test
    fun `theme IDs are unique`() {
        val ids = ThemeRegistry.allThemes.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
