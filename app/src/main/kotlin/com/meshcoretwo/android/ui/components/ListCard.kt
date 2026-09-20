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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
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
        .shadow(1.dp, shape, clip = false)
        .clip(shape)
        .background(fill)
        .combinedClickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick, onLongClick = onLongClick)
}

/** Soft accent wash fading into the canvas — painted behind the tab screens so the app has color at
 * the top instead of a flat sheet. */
@Composable
fun Modifier.accentBackdrop(): Modifier {
    val isDark = LocalIsDarkTheme.current
    val canvas = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    return this.background(
        Brush.verticalGradient(
            0f to accent.copy(alpha = if (isDark) 0.22f else 0.16f).compositeOver(canvas),
            0.35f to canvas,
            1f to canvas,
        ),
    )
}
