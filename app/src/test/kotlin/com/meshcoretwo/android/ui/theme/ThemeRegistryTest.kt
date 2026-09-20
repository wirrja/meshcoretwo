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
    fun `allThemes holds the five built-ins`() {
        assertEquals(5, ThemeRegistry.allThemes.size)
        assertEquals("default", ThemeRegistry.allThemes.first().id)
    }

    @Test
    fun `theme(id) resolves known IDs and returns null for unknown`() {
        assertEquals(Theme.Default.id, ThemeRegistry.theme("default")?.id)
        assertEquals("aurora", ThemeRegistry.theme("aurora")?.id)
        assertNull(ThemeRegistry.theme("ember"))
        assertNull(ThemeRegistry.theme("does-not-exist"))
    }

    @Test
    fun `no built-in theme forces a color scheme`() {
        assertTrue(ThemeRegistry.allThemes.all { it.forcedDark == null })
    }

    @Test
    fun `theme IDs are unique`() {
        val ids = ThemeRegistry.allThemes.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
