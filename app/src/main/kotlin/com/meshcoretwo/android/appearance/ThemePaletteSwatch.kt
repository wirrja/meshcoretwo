// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.ui.theme.Theme

private val CornerRadius = 12.dp
private val BubbleCornerRadius = 6.dp
private val BubbleInset = 8.dp
private val TappableMinSide = 44.dp

/**
 * Shared theme preview for [ThemeSelectionCard]. The diagonal split signals light+dark support;
 * an un-split swatch signals a dark-only theme ([Theme.Ember]).
 *
 * Ported from `ThemePaletteSwatch.swift`. [thumbnail] drops the 44dp tappable-minimum enforced by
 * default, matching Swift's `.thumbnail`/`.tappable` `SizeStyle` split — unused until a row of many
 * swatches (e.g. a future Support screen) needs to share a parent width.
 */
@Composable
fun ThemePaletteSwatch(theme: Theme, modifier: Modifier = Modifier, thumbnail: Boolean = false) {
    val isDualMode = theme.forcedDark == null
    Box(
        modifier = modifier
            .then(if (thumbnail) Modifier else Modifier.sizeIn(minWidth = TappableMinSide, minHeight = TappableMinSide))
            .aspectRatio(1f)
            .clip(RoundedCornerShape(CornerRadius)),
    ) {
        if (isDualMode) {
            SwatchPalette(theme, isDark = false, modifier = Modifier.fillMaxSize().clip(DiagonalHalf(top = true)))
            SwatchPalette(theme, isDark = true, modifier = Modifier.fillMaxSize().clip(DiagonalHalf(top = false)))
        } else {
            SwatchPalette(theme, isDark = true, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Accent + background + a sample outgoing bubble, all theme-driven. */
@Composable
private fun SwatchPalette(theme: Theme, isDark: Boolean, modifier: Modifier) {
    val canvas = theme.surfaces?.canvas?.resolve(isDark) ?: theme.colorScheme(isDark).background
    val accent = theme.accentColor.resolve(isDark)
    Box(modifier = modifier.background(canvas)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(BubbleInset)
                .clip(RoundedCornerShape(BubbleCornerRadius))
                .background(accent),
        )
    }
}

/** One triangular half of the swatch, split corner-to-corner (top-leading triangle when [top]).
 * Ported from `ThemePaletteSwatch.swift`'s private `DiagonalHalf` shape. */
private fun DiagonalHalf(top: Boolean) = GenericShape { size, _ ->
    if (top) {
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(0f, size.height)
    } else {
        moveTo(size.width, 0f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
    }
    close()
}
