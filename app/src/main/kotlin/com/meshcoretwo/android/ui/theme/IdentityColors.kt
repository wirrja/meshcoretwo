// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Incoming message bubble fill for the active theme. Unconditionally `colorScheme.surfaceContainerHigh`
 * (Phase 10 slice 2) rather than the theme's own [Theme.surfaces] card tier: a tonal role already
 * resolves correctly on all 10 themes without a per-theme override, matching how the outgoing bubble
 * moved off [Theme.accentColor] onto `colorScheme.primary` in the same slice. Ported from
 * `Theme.incomingBubbleColor`, since superseded by the Material 3 layout pass in PLAN.md's Phase 10.
 */
@Composable
fun incomingBubbleColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/**
 * Relative luminances of the surfaces avatars and identity names sit on: the list canvas
 * (`colorScheme.background`, which is the theme's `surfaces.canvas` override when the theme paints
 * one — see `ThemePalettes.kt`) and the incoming-message bubble fill. An identity color must stay
 * legible against both, since the same color renders as both an avatar fill (on the canvas) and a
 * channel sender name (on the bubble). Ported from `Theme.avatarSurfaceLuminances`, minus the
 * `UITraitCollection` resolution step — a Compose [androidx.compose.ui.graphics.Color] already
 * reflects the current theme, light or dark.
 */
@Composable
private fun avatarSurfaceLuminances(): List<Double> = listOf(
    WCAGContrast.relativeLuminance(MaterialTheme.colorScheme.background),
    WCAGContrast.relativeLuminance(incomingBubbleColor()),
)

/**
 * Color for a contact avatar / channel sender name / mention with the given identity [name].
 * Ported from `Theme.identityColor(forName:colorScheme:contrast:)`; `highContrast` is always
 * `false` for now — see [WCAGContrast.INCREASED_CONTRAST_FLOOR]'s doc comment.
 */
@Composable
fun identityColor(name: String): Color {
    val theme = LocalAppTheme.current
    return theme.identityGamut.color(name = name, backgroundLuminances = avatarSurfaceLuminances())
}

/**
 * Hue (degrees) the active theme's category avatar for [category] resolves at: the theme's curated
 * [Theme.categoryHues] value, or a distinct on-anchor pick for a gamut theme that hasn't curated
 * them. Ported from `Theme.categoryHue`.
 */
fun Theme.categoryHue(category: AvatarCategory): Double {
    categoryHues?.let { return it.hue(category) }
    val hues = identityGamut.distinctAnchorHues(AvatarCategory.anchorPriority.map { it.gamutSeed })
    return when (category) {
        AvatarCategory.CHANNEL -> hues[0]
        AvatarCategory.REPEATER -> hues[1]
        AvatarCategory.ROOM -> hues[2]
    }
}

/**
 * The single fixed color for a channel / repeater / room avatar. Ported from
 * `Theme.categoryAvatarColor`: [Theme.Default] pins these via [Theme.categoryAvatarOverride];
 * every other theme resolves each category at its curated [Theme.categoryHue], against the list
 * canvas only and at the darkest legible brightness, so the swatch is a deep, on-theme color.
 */
@Composable
fun categoryAvatarColor(category: AvatarCategory): Color {
    val theme = LocalAppTheme.current
    theme.categoryAvatarOverride?.let { return it.color(category) }
    val canvasLuminance = WCAGContrast.relativeLuminance(MaterialTheme.colorScheme.background)
    return theme.identityGamut.color(
        name = category.gamutSeed,
        backgroundLuminances = listOf(canvasLuminance),
        atHue = theme.categoryHue(category),
        atVariety = CATEGORY_DARKEST_VARIETY,
    )
}

/**
 * Glyph (initials / icon) color for an avatar of the given [fill]. Ported from
 * `Theme.avatarGlyphColor`. [usesCategoryOverride] keeps the historical white glyph for a
 * category avatar pinned by [Theme.categoryAvatarOverride]; a per-name identity fill (or a
 * gamut-derived category fill) adapts so the glyph reads whether the fill resolved light or dark.
 */
@Composable
fun avatarGlyphColor(fill: Color, usesCategoryOverride: Boolean): Color =
    if (usesCategoryOverride) Color.White else IdentityGamut.glyphColor(WCAGContrast.relativeLuminance(fill))

/** Brightness draw category avatars resolve at: the darkest legible value (no variety), so they
 * read as deep, on-theme swatches instead of the washed-out brights the bubble surface forced.
 * Ported from `Theme.categoryDarkestVariety`. */
private const val CATEGORY_DARKEST_VARIETY = 0.0
