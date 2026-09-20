// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the port's actual Default-theme wiring (`DefaultIdentityHueAnchors`/
 * `DefaultIdentitySaturation`/the category avatar colors in `Color.kt`) against the port's actual
 * Material3 surfaces (`background`/`surfaceVariant` in `Color.kt`), unlike [IdentityGamutTest]'s
 * fixture gamut and fixture surfaces. Scoped to the Default theme only — the other 9 themes from
 * `ThemeContrastTests.swift`/`ThemeStructureTests.swift` arrive with Phase 6 slice 3's theme system.
 */
class DefaultIdentityColorsTest {
    private val gamut = IdentityGamut(hueAnchors = DefaultIdentityHueAnchors, saturation = DefaultIdentitySaturation)

    /** A broad spread of names, including anagrams and near-duplicates that stress hash collisions. */
    private val names: List<String> =
        (0 until 400).map { "Sender $it" } + listOf("Bob", "obB", "Alice", "alice", "灯火", "Søren", "#general")

    private fun surfaceLuminances(background: androidx.compose.ui.graphics.Color, bubble: androidx.compose.ui.graphics.Color) =
        listOf(WCAGContrast.relativeLuminance(background), WCAGContrast.relativeLuminance(bubble))

    @Test
    fun `Default theme's identity gamut is structurally valid`() {
        assertTrue("hue anchors must not be empty", DefaultIdentityHueAnchors.isNotEmpty())
        assertTrue("hue anchors must be within 0..360", DefaultIdentityHueAnchors.all { it >= 0.0 && it < 360.0 })
        assertTrue("saturation must be within 0..1", DefaultIdentitySaturation.start >= 0.0 && DefaultIdentitySaturation.endInclusive <= 1.0)
        assertTrue("saturation must be a non-empty range", DefaultIdentitySaturation.start < DefaultIdentitySaturation.endInclusive)
    }

    @Test
    fun `identity colors clear AA against background and incoming bubble in light and dark`() {
        val schemes = listOf(
            "light" to surfaceLuminances(md_theme_light_background, md_theme_light_surfaceVariant),
            "dark" to surfaceLuminances(md_theme_dark_background, md_theme_dark_surfaceVariant),
        )
        for ((schemeName, backgrounds) in schemes) {
            for (name in names) {
                val color = gamut.color(name, backgrounds)
                val colorLuminance = WCAGContrast.relativeLuminance(color)
                for (background in backgrounds) {
                    val ratio = WCAGContrast.contrastRatio(colorLuminance, background)
                    assertTrue(
                        "$schemeName identity '$name' vs surface $background is $ratio, below ${WCAGContrast.AA_FLOOR}",
                        ratio >= WCAGContrast.AA_FLOOR,
                    )
                }
            }
        }
    }

    @Test
    fun `avatar glyph clears AA against the identity fill in light and dark`() {
        val schemes = listOf(
            "light" to surfaceLuminances(md_theme_light_background, md_theme_light_surfaceVariant),
            "dark" to surfaceLuminances(md_theme_dark_background, md_theme_dark_surfaceVariant),
        )
        for ((schemeName, backgrounds) in schemes) {
            for (name in names.take(100)) {
                val fill = gamut.color(name, backgrounds)
                val fillLuminance = WCAGContrast.relativeLuminance(fill)
                val glyph = IdentityGamut.glyphColor(fillLuminance)
                val ratio = WCAGContrast.contrastRatio(WCAGContrast.relativeLuminance(glyph), fillLuminance)
                assertTrue("$schemeName glyph vs fill '$name' is $ratio", ratio >= WCAGContrast.AA_FLOOR)
            }
        }
    }

    @Test
    fun `channel, repeater, and room category colors are distinct`() {
        val categoryColors = setOf(CategoryChannel, CategoryRepeaterNode, CategoryRoom)
        assertEquals("category colors must not collide", 3, categoryColors.size)
    }
}
