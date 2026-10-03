// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards what makes the experimental themes what they are, beyond the shared contrast tests. */
class ExperimentalThemesTest {
    private val names: List<String> = (0 until 300).map { "Sender $it" } + listOf("Bob", "Alice", "灯火", "#general")

    /** HSV hue in degrees, or `null` for a color too grey to have one (black included). */
    private fun hue(color: Color): Double? {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val chroma = max - min
        if (chroma < 0.04f) return null
        val h = when (max) {
            color.red -> ((color.green - color.blue) / chroma).mod(6f)
            color.green -> (color.blue - color.red) / chroma + 2f
            else -> (color.red - color.green) / chroma + 4f
        }
        return h * 60.0
    }

    private fun isRed(color: Color): Boolean = hue(color)?.let { it <= 25.0 || it >= 335.0 } ?: true

    private fun ColorScheme.roles(): List<Pair<String, Color>> = listOf(
        "primary" to primary, "onPrimary" to onPrimary, "primaryContainer" to primaryContainer,
        "onPrimaryContainer" to onPrimaryContainer, "inversePrimary" to inversePrimary, "secondary" to secondary,
        "onSecondary" to onSecondary, "secondaryContainer" to secondaryContainer, "onSecondaryContainer" to onSecondaryContainer,
        "tertiary" to tertiary, "onTertiary" to onTertiary, "tertiaryContainer" to tertiaryContainer,
        "onTertiaryContainer" to onTertiaryContainer, "background" to background, "onBackground" to onBackground,
        "surface" to surface, "onSurface" to onSurface, "surfaceVariant" to surfaceVariant, "onSurfaceVariant" to onSurfaceVariant,
        "surfaceTint" to surfaceTint, "inverseSurface" to inverseSurface, "inverseOnSurface" to inverseOnSurface,
        "error" to error, "onError" to onError, "errorContainer" to errorContainer, "onErrorContainer" to onErrorContainer,
        "outline" to outline, "outlineVariant" to outlineVariant, "surfaceBright" to surfaceBright, "surfaceDim" to surfaceDim,
        "surfaceContainer" to surfaceContainer, "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest, "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainerLowest" to surfaceContainerLowest,
    )

    private fun surfaceLuminances(theme: Theme, isDark: Boolean): List<Double> {
        val surfaces = requireNotNull(theme.surfaces)
        return listOf(surfaces.canvas.resolve(isDark), requireNotNull(surfaces.card).resolve(isDark)).map(WCAGContrast::relativeLuminance)
    }

    @Test
    fun `Night renders red only - palette, status colors, accents, identity colors`() {
        val night = ExperimentalThemes.Night
        for ((role, color) in night.darkScheme.roles()) assertTrue("Night $role $color is not red", isRed(color))
        val extended = requireNotNull(night.extendedColorsOverride)
        for (color in listOf(extended.success, extended.caution, extended.warning, extended.danger, extended.info, extended.radioReady, extended.radioConnecting, extended.radioRepeatMode)) {
            assertTrue("Night status color $color is not red", isRed(color))
        }
        val style = night.style
        for (color in listOfNotNull(night.accentColor.dark, night.hashtagColor.dark, style.outline?.dark, style.outgoingBubble?.dark, style.outgoingBubbleText?.dark)) {
            assertTrue("Night accent $color is not red", isRed(color))
        }
        val backgrounds = surfaceLuminances(night, isDark = true)
        for (name in names) {
            val color = night.identityGamut.color(name, backgrounds)
            assertTrue("Night identity '$name' $color is not red", isRed(color))
        }
        assertTrue(style.nightMap)
    }

    @Test
    fun `Phosphor identity colors stay green`() {
        val phosphor = ExperimentalThemes.Phosphor
        val backgrounds = surfaceLuminances(phosphor, isDark = true)
        for (name in names) {
            val h = hue(phosphor.identityGamut.color(name, backgrounds))
            assertTrue("Phosphor identity '$name' hue $h left the greens", h == null || h in 90.0..170.0)
        }
    }

    @Test
    fun `maxJitter keeps every name within that many degrees of an anchor`() {
        val gamut = IdentityGamut(listOf(0.0, 10.0), 0.8..0.9, maxJitter = 3.0)
        for (name in names) {
            val hue = gamut.resolve(name, listOf(1.0)).hue
            val distance = gamut.hueAnchors.minOf { anchor -> minOf((hue - anchor).mod(360.0), (anchor - hue).mod(360.0)) }
            assertTrue("'$name' hue $hue is $distance° from the nearest anchor", distance <= 3.0 + 1e-9)
        }
    }

    @Test
    fun `classic themes keep the classic style and the app's base type and shapes`() {
        for (theme in ThemeRegistry.classicThemes) {
            assertSame(ThemeStyle.Classic, theme.style)
            assertNull(theme.extendedColorsOverride)
            assertSame(MeshCoreTwoTypography, theme.style.typography(MeshCoreTwoTypography, isDark = false))
            assertSame(MeshCoreTwoShapes, theme.style.corners.shapes)
        }
    }

    @Test
    fun `Phosphor sets monospace everywhere with a text glow, Blueprint only on titles, Topo serif titles`() {
        val phosphor = ExperimentalThemes.Phosphor.style.typography(MeshCoreTwoTypography, isDark = true)
        assertEquals(FontFamily.Monospace, phosphor.bodyMedium.fontFamily)
        assertEquals(FontFamily.Monospace, phosphor.titleLarge.fontFamily)
        assertNotNull(phosphor.bodyMedium.shadow)

        val blueprint = ExperimentalThemes.Blueprint.style.typography(MeshCoreTwoTypography, isDark = false)
        assertEquals(FontFamily.Monospace, blueprint.titleLarge.fontFamily)
        assertEquals(MeshCoreTwoTypography.bodyMedium.fontFamily, blueprint.bodyMedium.fontFamily)
        assertNull(blueprint.bodyMedium.shadow)

        val topo = ExperimentalThemes.Topo.style.typography(MeshCoreTwoTypography, isDark = false)
        assertEquals(FontFamily.Serif, topo.headlineMedium.fontFamily)
    }

    @Test
    fun `Daylight thickens body text but not labels`() {
        val daylight = ExperimentalThemes.Daylight.style.typography(MeshCoreTwoTypography, isDark = false)
        assertEquals(FontWeight.Medium, daylight.bodyLarge.fontWeight)
        assertEquals(MeshCoreTwoTypography.labelLarge.fontWeight, daylight.labelLarge.fontWeight)
    }

    @Test
    fun `Neon glows on titles only, Hazard has extra-bold titles, Notebook handwritten titles, Newsprint serif throughout`() {
        val neon = ExperimentalThemes.Neon.style.typography(MeshCoreTwoTypography, isDark = true)
        assertNotNull(neon.titleLarge.shadow)
        assertNull(neon.bodyMedium.shadow)

        val hazard = ExperimentalThemes.Hazard.style.typography(MeshCoreTwoTypography, isDark = false)
        assertEquals(FontWeight.ExtraBold, hazard.titleLarge.fontWeight)
        assertEquals(FontWeight.Medium, hazard.bodyLarge.fontWeight)

        val notebook = ExperimentalThemes.Notebook.style.typography(MeshCoreTwoTypography, isDark = false)
        assertEquals(FontFamily.Cursive, notebook.titleLarge.fontFamily)
        assertEquals(MeshCoreTwoTypography.bodyMedium.fontFamily, notebook.bodyMedium.fontFamily)

        val newsprint = ExperimentalThemes.Newsprint.style.typography(MeshCoreTwoTypography, isDark = false)
        assertEquals(FontFamily.Serif, newsprint.titleLarge.fontFamily)
        assertEquals(FontFamily.Serif, newsprint.bodyMedium.fontFamily)
    }

    @Test
    fun `labels on containers stand out from the container - 7 to 1, or pure white or black where that's the most there is`() {
        for (theme in ThemeRegistry.experimentalThemes) {
            for (isDark in theme.forcedDark?.let { listOf(it) } ?: listOf(false, true)) {
                val scheme = theme.colorScheme(isDark)
                val pairs = listOf(
                    "primary" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                    "secondary" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                    "tertiary" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                    "error" to (scheme.onErrorContainer to scheme.errorContainer),
                )
                for ((role, colors) in pairs) {
                    val (label, container) = colors
                    val ratio = WCAGContrast.contrastRatio(WCAGContrast.relativeLuminance(label), WCAGContrast.relativeLuminance(container))
                    assertTrue(
                        "${theme.id} on${role}Container vs container (dark=$isDark) is $ratio",
                        ratio >= LABEL_CONTRAST || label == Color.White || label == Color.Black,
                    )
                }
            }
        }
    }

    @Test
    fun `patterns drawn in two colors define their accent`() {
        val twoColor = setOf(BackdropPattern.HORIZON, BackdropPattern.RULED, BackdropPattern.STARS)
        for (theme in ThemeRegistry.experimentalThemes.filter { it.style.pattern in twoColor }) {
            assertNotNull("${theme.id} ${theme.style.pattern} needs a pattern accent", theme.style.patternAccent)
        }
    }

    @Test
    fun `a theme with a pattern defines its color, and Mesh glows only in dark`() {
        for (theme in ThemeRegistry.experimentalThemes.filter { it.style.pattern != BackdropPattern.NONE }) {
            assertNotNull("${theme.id} pattern needs a color", theme.style.patternColor)
        }
        val glow = requireNotNull(ExperimentalThemes.Mesh.style.glow)
        assertEquals(0f, glow.light.alpha)
        assertTrue(glow.dark.alpha > 0f)
    }

    private companion object {
        const val LABEL_CONTRAST = 7.0
    }
}
