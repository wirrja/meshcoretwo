// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from `ThemeStructureTests.swift`, minus `chromeTint`/`productID`-based assertions —
 * this port has no monetization (project constraints) and no separate chrome-tint knob (Compose's
 * [Theme.colorScheme] already derives `primary` from the accent for every theme, see `Theme.kt`'s
 * class doc), so those two Swift tests have no Android counterpart.
 */
class ThemeStructureTest {
    @Test
    fun `Default theme paints no surfaces`() {
        assertNull(Theme.Default.surfaces)
    }

    @Test
    fun `Ember paints the canvas but not the card tier`() {
        val surfaces = requireNotNull(Theme.Ember.surfaces)
        assertEquals(androidx.compose.ui.graphics.Color.Black, surfaces.canvas.light)
        assertEquals(androidx.compose.ui.graphics.Color.Black, surfaces.canvas.dark)
        assertNull(surfaces.card)
    }

    @Test
    fun `Every painted theme defines both canvas and card tiers`() {
        val painted = ThemeRegistry.allThemes.filter { it.id != Theme.Default.id && it.id != Theme.Ember.id }
        assertEquals(8, painted.size)
        for (theme in painted) {
            val surfaces = requireNotNull(theme.surfaces) { "${theme.id} must have surfaces" }
            assertTrue("${theme.id} must define card tier", surfaces.card != null)
        }
    }

    @Test
    fun `Every theme defines a usable identity gamut`() {
        for (theme in ThemeRegistry.allThemes) {
            val gamut = theme.identityGamut
            assertTrue("${theme.id} must define identity hue anchors", gamut.hueAnchors.isNotEmpty())
            assertTrue(
                "${theme.id} hue anchors must be 0..<360",
                gamut.hueAnchors.all { it >= 0.0 && it < 360.0 },
            )
            assertTrue(
                "${theme.id} saturation must be within 0..1",
                gamut.saturation.start >= 0.0 && gamut.saturation.endInclusive <= 1.0,
            )
            assertTrue(
                "${theme.id} saturation must be a non-empty range",
                gamut.saturation.start < gamut.saturation.endInclusive,
            )
        }
    }

    @Test
    fun `Only the Default theme pins fixed category avatar colors`() {
        assertTrue("Default theme must pin category colors", Theme.Default.categoryAvatarOverride != null)
        val others = ThemeRegistry.allThemes.filter { it.id != Theme.Default.id }
        assertEquals(9, others.size)
        for (theme in others) {
            assertNull("${theme.id} must derive category colors from its gamut", theme.categoryAvatarOverride)
        }
    }

    @Test
    fun `Migrated themes' outgoingTextColor resolves differently in light vs dark`() {
        val migrated = listOf(Theme.Fern, Theme.Olive, Theme.Lavender, Theme.Sakura)
        for (theme in migrated) {
            assertNotEquals("${theme.id} outgoingText must differ across appearance", theme.outgoingTextColor.light, theme.outgoingTextColor.dark)
        }
    }
}
