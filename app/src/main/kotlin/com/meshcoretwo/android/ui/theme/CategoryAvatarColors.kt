// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Explicit per-category avatar colors, used only by the Default theme to pin the channel /
 * repeater / room avatars to their historical fixed values (`CategoryChannel`/
 * `CategoryRepeaterNode`/`CategoryRoom` in `Color.kt`). Every other theme leaves this `null` and
 * derives its three category colors from its [IdentityGamut] instead (see [Theme.categoryHues]),
 * which keeps them on-theme and guarantees WCAG AA against the theme's surfaces.
 *
 * Ported from `CategoryAvatarColors.swift`.
 */
data class CategoryAvatarColors(
    val channel: Color,
    val repeaterNode: Color,
    val room: Color,
) {
    fun color(category: AvatarCategory): Color = when (category) {
        AvatarCategory.CHANNEL -> channel
        AvatarCategory.REPEATER -> repeaterNode
        AvatarCategory.ROOM -> room
    }
}
