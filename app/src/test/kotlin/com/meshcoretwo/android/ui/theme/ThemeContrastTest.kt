// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the legibility of outgoing message text against its bubble fill (the theme accent) for
 * every theme, in every appearance it actually renders in, and the legibility of identity and
 * category avatar colors against the surfaces they render on. Ported from
 * `ThemeContrastTests.swift`. The identity/category-color tests run against every theme, matching
 * Swift's own `ThemeRegistry.allThemes` loops there; the outgoing-text/hashtag tests are scoped to
 * [themedThemes] (every theme but [Theme.Default]), matching Swift's `paidThemes` filter
 * (`$0.productID != nil`) — Default's `hashtagColor`/`outgoingTextColor` are legacy constants that
 * iOS's own test suite never asserted AA against either; only the 9 "migrated" themes got that
 * treatment when the theme system was designed. `highContrast` is always `false`, matching the rest
 * of this port's identity-color tests (see `IdentityGamutTest`/`DefaultIdentityColorsTest`).
 */
class ThemeContrastTest {
    /** A broad spread of names, including anagrams and near-duplicates that stress hash collisions. */
    private val identityNames: List<String> =
        (0 until 400).map { "Sender $it" } + listOf("Bob", "obB", "Alice", "alice", "灯火", "Søren", "#general")

    /** Every theme but [Theme.Default] — this registry's equivalent of Swift's `paidThemes`. */
    private val themedThemes = ThemeRegistry.allThemes.filter { it.id != Theme.Default.id }

    /** The appearances [theme] actually renders in: both when it adapts, just the forced one otherwise. */
    private fun appearancesFor(theme: Theme): List<Boolean> = theme.forcedDark?.let { listOf(it) } ?: listOf(false, true)

    /** Canvas (or the theme's own generated background for the surfaceless Default theme) — the
     * list canvas every identity/category avatar color renders on. */
    private fun canvas(theme: Theme, isDark: Boolean) = theme.surfaces?.canvas?.resolve(isDark) ?: theme.colorScheme(isDark).background

    /** Incoming bubble fill (or `surfaceVariant` for a theme with no card tier), mirroring
     * `incomingBubbleColor()`'s fallback in `IdentityColors.kt`. */
    private fun incomingBubble(theme: Theme, isDark: Boolean) =
        theme.surfaces?.card?.resolve(isDark) ?: theme.colorScheme(isDark).surfaceVariant

    private fun surfaceLuminances(theme: Theme, isDark: Boolean) =
        listOf(WCAGContrast.relativeLuminance(canvas(theme, isDark)), WCAGContrast.relativeLuminance(incomingBubble(theme, isDark)))

    @Test
    fun `outgoing text clears WCAG AA against the accent in every appearance`() {
        for (theme in themedThemes) {
            for (isDark in appearancesFor(theme)) {
                val text = WCAGContrast.relativeLuminance(theme.outgoingTextColor.resolve(isDark))
                val fill = WCAGContrast.relativeLuminance(theme.accentColor.resolve(isDark))
                val ratio = WCAGContrast.contrastRatio(text, fill)
                assertTrue("${theme.id} outgoing text vs accent (dark=$isDark) is $ratio", ratio >= WCAGContrast.AA_FLOOR)
            }
        }
    }

    @Test
    fun `incoming hashtag links clear WCAG AA against the incoming bubble in every appearance`() {
        for (theme in themedThemes) {
            for (isDark in appearancesFor(theme)) {
                val hashtag = WCAGContrast.relativeLuminance(theme.hashtagColor.resolve(isDark))
                val bubble = WCAGContrast.relativeLuminance(incomingBubble(theme, isDark))
                val ratio = WCAGContrast.contrastRatio(hashtag, bubble)
                assertTrue("${theme.id} hashtag vs bubble (dark=$isDark) is $ratio", ratio >= WCAGContrast.AA_FLOOR)
            }
        }
    }

    @Test
    fun `identity colors clear AA against their surfaces for every theme and appearance`() {
        for (theme in ThemeRegistry.allThemes) {
            for (isDark in appearancesFor(theme)) {
                val backgrounds = surfaceLuminances(theme, isDark)
                for (name in identityNames) {
                    val color = theme.identityGamut.color(name, backgrounds)
                    val colorLuminance = WCAGContrast.relativeLuminance(color)
                    for (background in backgrounds) {
                        val ratio = WCAGContrast.contrastRatio(colorLuminance, background)
                        assertTrue(
                            "${theme.id} identity '$name' vs surface $background (dark=$isDark) is $ratio",
                            ratio >= WCAGContrast.AA_FLOOR,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `gamut-derived category colors clear AA against the list canvas`() {
        for (theme in ThemeRegistry.allThemes.filter { it.categoryAvatarOverride == null }) {
            for (isDark in appearancesFor(theme)) {
                val canvasLuminance = WCAGContrast.relativeLuminance(canvas(theme, isDark))
                for (category in AvatarCategory.entries) {
                    val color = theme.identityGamut.color(
                        name = category.gamutSeed,
                        backgroundLuminances = listOf(canvasLuminance),
                        atHue = theme.categoryHue(category),
                        atVariety = 0.0,
                    )
                    val ratio = WCAGContrast.contrastRatio(WCAGContrast.relativeLuminance(color), canvasLuminance)
                    assertTrue("${theme.id} category $category vs canvas (dark=$isDark) is $ratio", ratio >= WCAGContrast.AA_FLOOR)
                }
            }
        }
    }

    @Test
    fun `channel, repeater, and room avatars resolve to distinct on-anchor hues for every gamut theme`() {
        for (theme in ThemeRegistry.allThemes.filter { it.categoryAvatarOverride == null }) {
            val anchors = theme.identityGamut.sortedAnchors.toSet()
            val hues = AvatarCategory.entries.map { theme.categoryHue(it) }
            assertTrue("${theme.id} category hues collide: $hues", hues.toSet().size == hues.size)
            assertTrue("${theme.id} category hue left the palette: $hues", hues.all { it in anchors })
        }
    }

    @Test
    fun `avatar glyph clears AA against the identity fill for every theme and appearance`() {
        for (theme in ThemeRegistry.allThemes) {
            for (isDark in appearancesFor(theme)) {
                val backgrounds = surfaceLuminances(theme, isDark)
                for (name in identityNames.take(100)) {
                    val fill = theme.identityGamut.color(name, backgrounds)
                    val fillLuminance = WCAGContrast.relativeLuminance(fill)
                    val glyph = IdentityGamut.glyphColor(fillLuminance)
                    val ratio = WCAGContrast.contrastRatio(WCAGContrast.relativeLuminance(glyph), fillLuminance)
                    assertTrue("${theme.id} glyph vs fill '$name' (dark=$isDark) is $ratio", ratio >= WCAGContrast.AA_FLOOR)
                }
            }
        }
    }
}
