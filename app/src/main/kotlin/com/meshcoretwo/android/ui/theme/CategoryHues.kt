// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

/**
 * Curated per-category avatar hues (degrees) for a gamut-derived theme, each chosen from the
 * theme's own anchors to look good and stay distinct. The Default theme pins full colors via
 * [CategoryAvatarColors] and leaves this `null`; a gamut theme that also leaves it `null` falls
 * back to a distinct on-anchor pick (see [Theme.categoryHue]).
 *
 * Ported from `CategoryHues.swift`.
 */
data class CategoryHues(
    val channel: Double,
    val repeater: Double,
    val room: Double,
) {
    fun hue(category: AvatarCategory): Double = when (category) {
        AvatarCategory.CHANNEL -> channel
        AvatarCategory.REPEATER -> repeater
        AvatarCategory.ROOM -> room
    }
}
