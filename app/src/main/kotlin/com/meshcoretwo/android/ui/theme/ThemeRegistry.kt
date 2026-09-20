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
        Theme.Aurora,
        Theme.Sunrise,
        Theme.Graphite,
        Theme.Ultraviolet,
    )

    fun theme(id: String): Theme? = allThemes.firstOrNull { it.id == id }
}
