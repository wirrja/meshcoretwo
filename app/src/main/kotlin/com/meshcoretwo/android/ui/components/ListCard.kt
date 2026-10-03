// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import com.meshcoretwo.android.ui.theme.LocalAppTheme
import com.meshcoretwo.android.ui.theme.LocalIsDarkTheme

/**
 * Fill of a floating card (list rows, search pill, filter chips): the theme's `card` surface when it
 * defines one, otherwise white in light mode — a step *lighter* than the tinted canvas so cards lift
 * off it — and the regular `surfaceContainer` in dark mode.
 */
@Composable
fun cardSurfaceColor(): Color {
    val theme = LocalAppTheme.current
    val isDark = LocalIsDarkTheme.current
    return theme.surfaces?.card?.resolve(isDark)
        ?: if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
}

/**
 * Turns a list row into a soft floating card: outer breathing room, rounded corners, a whisper of
 * shadow, tappable across the whole surface. The caller adds its own inner padding after this.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.listCard(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, emphasized: Boolean = false): Modifier {
    val shape = MaterialTheme.shapes.large
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.98f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "listCardPress",
    )
    val base = cardSurfaceColor()
    val fill = if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f).compositeOver(base) else base
    return this
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 4.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .cardChrome(shape)
        .clip(shape)
        .background(fill)
        .combinedClickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick, onLongClick = onLongClick)
}

/** Soft accent wash fading into the canvas — painted behind the tab screens so the app has color at
 * the top instead of a flat sheet. Scaled by how colorful the accent is, so near-monochrome themes
 * (ink-on-paper Graphite) get a barely-there tint instead of a muddy grey wash. A theme may drop the
 * wash (Daylight's pure white) and add a decorative [backdropPattern] over the canvas. */
@Composable
fun Modifier.accentBackdrop(): Modifier {
    val isDark = LocalIsDarkTheme.current
    val canvas = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    // Colorfulness (channel spread), not HSV saturation: near-black ink accents are "saturated" in HSV
    // yet colorless, and would otherwise paint a grey wash.
    val chroma = remember(accent) { maxOf(accent.red, accent.green, accent.blue) - minOf(accent.red, accent.green, accent.blue) }
    val strength = (chroma / 0.35f).coerceIn(0f, 1f).let { if (isDark) maxOf(it, 0.5f) else it }
    val alpha = (if (isDark) 0.22f else 0.16f) * strength
    val style = LocalAppTheme.current.style
    val fill = if (style.accentWash) {
        Modifier.background(
            Brush.verticalGradient(
                0f to accent.copy(alpha = alpha).compositeOver(canvas),
                0.35f to canvas,
                1f to canvas,
            ),
        )
    } else {
        Modifier.background(canvas)
    }
    val patternColor = style.patternColor?.resolve(isDark)
    val patternAccent = style.patternAccent?.resolve(isDark)?.takeIf { it.alpha > 0f }
    val pattern = if (patternColor != null) Modifier.backdropPattern(style.pattern, patternColor, patternAccent) else Modifier
    return this.then(fill).then(pattern)
}

/**
 * The floating-card treatment for a surface of [shape]: the classic soft drop shadow, or the theme's
 * colored glow, plus its hairline outline when it has one ([com.meshcoretwo.android.ui.theme.ThemeStyle]).
 * Apply before `clip`/`background` so the shadow sits outside the shape and the outline draws over
 * the fill.
 */
@Composable
fun Modifier.cardChrome(shape: Shape, elevation: Dp = 1.dp): Modifier {
    val style = LocalAppTheme.current.style
    val isDark = LocalIsDarkTheme.current
    val glow = style.glow?.resolve(isDark)?.takeIf { it.alpha > 0f }
    val outline = style.outline?.resolve(isDark)
    val shadowed = when {
        glow != null -> shadow(elevation * GLOW_ELEVATION_FACTOR, shape, clip = false, ambientColor = glow, spotColor = glow)
        style.shadow -> shadow(elevation, shape, clip = false)
        else -> this
    }
    return if (outline != null) shadowed.border(style.outlineWidth, outline, shape) else shadowed
}

/** A glow needs more blur than a drop shadow to read as light rather than as a dark rim. */
private const val GLOW_ELEVATION_FACTOR = 6f

/** The theme's card outline color, or transparent when it has none — for components that take a
 * border color rather than a stroke (outlined text fields, chips). */
@Composable
fun themeOutlineColor(): Color = LocalAppTheme.current.style.outline?.resolve(LocalIsDarkTheme.current) ?: Color.Transparent

/** The theme's card outline as a [BorderStroke] for Material components that take one (`Card`,
 * `Surface`); `null` when the theme has none. */
@Composable
fun themeOutlineStroke(): BorderStroke? {
    val style = LocalAppTheme.current.style
    val outline = style.outline?.resolve(LocalIsDarkTheme.current) ?: return null
    return BorderStroke(style.outlineWidth, outline)
}
