// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

/**
 * The catalog of built-in themes.
 *
 * Ported from `ThemeRegistry.swift`.
 */
object ThemeRegistry {
    val allThemes: List<Theme> = listOf(
        Theme.Default,
        Theme.Ember,
        Theme.Fern,
        Theme.Marine,
        Theme.Olive,
        Theme.Lavender,
        Theme.Sakura,
        Theme.Solarized,
        Theme.Nord,
        Theme.Catppuccin,
    )

    fun theme(id: String): Theme? = allThemes.firstOrNull { it.id == id }
}
