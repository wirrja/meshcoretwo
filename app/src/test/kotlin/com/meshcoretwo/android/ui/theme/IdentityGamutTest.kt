// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the identity-color solver in isolation, against a representative fixture gamut: every
 * name must resolve to a color that clears WCAG AA against the surfaces it renders on, in both
 * appearances and both contrast settings, and the avatar glyph must clear AA against that color as
 * a fill. Ported from `IdentityGamutTests.swift`.
 *
 * The fixture surfaces below stand in for iOS's `UIColor.systemBackground`/
 * `secondarySystemBackground`/`systemGray5` resolved per appearance — there's no Android system
 * palette equivalent, so these are representative light/dark relative-luminance values instead of
 * resolved theme colors (the real theme surfaces are covered separately by
 * [DefaultIdentityColorsTest]).
 */
class IdentityGamutTest {
    private val gamut = IdentityGamut(
        hueAnchors = listOf(20.0, 50.0, 95.0, 150.0, 195.0, 235.0, 280.0, 320.0),
        saturation = 0.45..0.75,
    )

    /** A broad spread of names, including anagrams and near-duplicates that stress hash collisions. */
    private val names: List<String> = (0 until 600).map { "Sender $it" } + listOf("Bob", "obB", "Alice", "alice", "灯火", "Søren")

    // Approximate relative luminances of iOS's systemBackground/secondarySystemBackground/
    // systemGray5 (light: ~white/#F2F2F7/#E5E5EA; dark: ~black/#1C1C1E/#3A3A3C) — Android has no
    // matching system palette to resolve directly, so these stand in as representative light/dark
    // surface sets. Kept comfortably under the luminance an AAA (7:1) target can reach with any
    // color (max ~0.10 for a dark surface, since even pure white text tops out there).
    private val lightBackgrounds = listOf(1.0, 0.90, 0.78)
    private val darkBackgrounds = listOf(0.0, 0.007, 0.043)

    @Test
    fun `every identity color clears AA against its surfaces in both appearances and contrasts`() {
        for (backgrounds in listOf(lightBackgrounds, darkBackgrounds)) {
            for (highContrast in listOf(false, true)) {
                val floor = if (highContrast) WCAGContrast.INCREASED_CONTRAST_FLOOR else WCAGContrast.AA_FLOOR
                for (name in names) {
                    val color = gamut.color(name, backgrounds, highContrast)
                    val colorLuminance = WCAGContrast.relativeLuminance(color)
                    for (background in backgrounds) {
                        val ratio = WCAGContrast.contrastRatio(colorLuminance, background)
                        assertTrue(
                            "$name color luminance $colorLuminance vs surface $background is $ratio, below $floor",
                            ratio >= floor,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `avatar glyph clears AA against the identity fill in both appearances`() {
        for (backgrounds in listOf(lightBackgrounds, darkBackgrounds)) {
            for (name in names) {
                val fill = gamut.color(name, backgrounds, highContrast = false)
                val fillLuminance = WCAGContrast.relativeLuminance(fill)
                val glyph = IdentityGamut.glyphColor(fillLuminance)
                val ratio = WCAGContrast.contrastRatio(WCAGContrast.relativeLuminance(glyph), fillLuminance)
                assertTrue("$name glyph vs fill is $ratio", ratio >= WCAGContrast.AA_FLOOR)
            }
        }
    }

    @Test
    fun `hue is stable across appearance and contrast for a given name`() {
        for (name in names.take(50)) {
            val lightHue = gamut.resolve(name, lightBackgrounds, highContrast = false).hue
            val darkHue = gamut.resolve(name, darkBackgrounds, highContrast = false).hue
            assertEquals("$name hue drifted across appearance", lightHue, darkHue, 0.0)
        }
    }
}
