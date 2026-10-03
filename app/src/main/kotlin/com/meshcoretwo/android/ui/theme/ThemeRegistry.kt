// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.annotation.StringRes
import com.meshcoretwo.android.R

/**
 * The catalog of built-in themes: the five classic ones shown on Appearance itself, plus the
 * experimental ones behind its sixth tile. [allThemes] holds both, so a saved ID of either kind
 * resolves.
 *
 * Ported from `ThemeRegistry.swift` (the experimental half has no iOS counterpart).
 */
object ThemeRegistry {
    val classicThemes: List<Theme> = listOf(
        Theme.Default,
        Theme.Aurora,
        Theme.Sunrise,
        Theme.Graphite,
        Theme.Ultraviolet,
    )

    val experimentalThemes: List<Theme> = ExperimentalThemes.all

    val allThemes: List<Theme> = classicThemes + experimentalThemes

    fun theme(id: String): Theme? = allThemes.firstOrNull { it.id == id }

    /** Experimental themes by section, in [ThemeGroup] order. */
    fun experimentalByGroup(): List<Pair<ThemeGroup, List<Theme>>> =
        ThemeGroup.entries.map { group -> group to experimentalThemes.filter { it.group == group } }
}

/** Sections of the experimental-themes screen. */
enum class ThemeGroup(@StringRes val title: Int) {
    /** Picks its own appearance for a job: night vision, direct sunlight. */
    FUNCTIONAL(R.string.appearance_group_functional),

    /** Color-only, like the classic themes. */
    COLOR(R.string.appearance_group_color),

    /** Changes the canvas, cards and fonts too. */
    CHARACTER(R.string.appearance_group_character),
}
