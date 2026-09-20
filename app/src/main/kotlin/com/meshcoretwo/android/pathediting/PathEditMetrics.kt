// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import androidx.compose.ui.unit.dp

/**
 * Layout constants shared across the Add-Hop picker and (once ported) the contact/room path
 * editor sheet. Ported from `PathEditMetrics`, trimmed to the constants this port's Compose
 * layouts actually read — `segmentPickerVerticalInset`/`disabledOpacity`/`rowInset` fold into
 * plain `Modifier.padding`/`.alpha` calls at each call site instead, the same way every other
 * Tools screen in this port inlines its own spacing rather than centralizing single-use values.
 */
object PathEditMetrics {
    /** 48dp tap target — glove-safe, above Material's 48dp touch-target guidance. */
    val tapTarget = 48.dp
    val rowContentSpacing = 12.dp
    val badgeSpacing = 6.dp

    /** Add Hop CTA label: icon box size and icon-to-text spacing. */
    val ctaIconSize = 22.dp
    val ctaIconSpacing = 8.dp
}
