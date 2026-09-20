// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards WCAG AA legibility of the Phase 10 redesign's tonal roles — the outgoing message bubble
 * (`colorScheme.primary`/`onPrimary`, replacing the old `theme.accentColor`/`outgoingTextColor`
 * pair [ThemeContrastTest] still separately checks for iOS-parity reasons — slice 2 moved the
 * *rendered* bubble onto `colorScheme.primary`/`onPrimary`, so that older test no longer covers
 * what's actually on screen), the presence dot ([MeshExtendedColors.success]/`caution` against the
 * `ColorScheme.surface` stroke separating it from the avatar underneath, slice 1), the route chip
 * (`onSurfaceVariant` text on a `surfaceContainer` fill, slice 5), and the Settings row leading
 * icon (`onPrimaryContainer` on `primaryContainer`, slices 7–8) — for every one of
 * [ThemeRegistry]'s 10 themes, in every appearance each theme actually renders in. PLAN.md's Phase
 * 10 slice 9 calls for this check by name. This only catches contrast math, not layout/legibility a
 * human eye would catch — the corresponding manual pass across all 10 themes on-device is separate.
 */
class RedesignContrastTest {
    private fun appearancesFor(theme: Theme): List<Boolean> = theme.forcedDark?.let { listOf(it) } ?: listOf(false, true)

    private fun assertAA(label: String, foreground: Color, background: Color) {
        val ratio = WCAGContrast.contrastRatio(WCAGContrast.relativeLuminance(foreground), WCAGContrast.relativeLuminance(background))
        assertTrue("$label is $ratio", ratio >= WCAGContrast.AA_FLOOR)
    }

    @Test
    fun `outgoing bubble text clears WCAG AA against colorScheme primary for every theme and appearance`() {
        for (theme in ThemeRegistry.allThemes) {
            for (isDark in appearancesFor(theme)) {
                val scheme = theme.colorScheme(isDark)
                assertAA("${theme.id} onPrimary vs primary (dark=$isDark)", scheme.onPrimary, scheme.primary)
            }
        }
    }

    @Test
    fun `presence dot clears WCAG AA against its surface stroke for every theme and appearance`() {
        for (theme in ThemeRegistry.allThemes) {
            for (isDark in appearancesFor(theme)) {
                val scheme = theme.colorScheme(isDark)
                val extended = if (isDark) DarkExtendedColors else LightExtendedColors
                assertAA("${theme.id} success dot vs surface (dark=$isDark)", extended.success, scheme.surface)
                assertAA("${theme.id} caution dot vs surface (dark=$isDark)", extended.caution, scheme.surface)
            }
        }
    }

    @Test
    fun `route chip text clears WCAG AA against surfaceContainer for every theme and appearance`() {
        for (theme in ThemeRegistry.allThemes) {
            for (isDark in appearancesFor(theme)) {
                val scheme = theme.colorScheme(isDark)
                assertAA("${theme.id} onSurfaceVariant vs surfaceContainer (dark=$isDark)", scheme.onSurfaceVariant, scheme.surfaceContainer)
            }
        }
    }

    @Test
    fun `settings row leading icon clears WCAG AA against its primaryContainer tile for every theme and appearance`() {
        for (theme in ThemeRegistry.allThemes) {
            for (isDark in appearancesFor(theme)) {
                val scheme = theme.colorScheme(isDark)
                assertAA("${theme.id} onPrimaryContainer vs primaryContainer (dark=$isDark)", scheme.onPrimaryContainer, scheme.primaryContainer)
            }
        }
    }
}
