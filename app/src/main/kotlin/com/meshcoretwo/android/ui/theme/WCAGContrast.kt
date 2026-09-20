// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * WCAG relative-luminance and contrast-ratio math, shared by the identity-color solver
 * ([IdentityGamut]). Kept in one place so the app never ships two divergent copies of the formula.
 *
 * Ported from `WCAGContrast.swift`. The `UIColor`-resolving overload (`resolvedColor(with:)`
 * against a `UITraitCollection`) has no Android equivalent and isn't ported: a Compose [Color]
 * already carries its actual resolved sRGB components for the current theme, with no separate
 * appearance-trait resolution step needed.
 */
object WCAGContrast {
    /** Standard WCAG AA floor for normal-size text. */
    const val AA_FLOOR = 4.5

    /**
     * Floor for a high-contrast / increased-contrast mode. Ported from `increasedContrastFloor`
     * but unused for now: Android has no direct equivalent of iOS's system Increased Contrast
     * toggle, so [IdentityGamut.resolve]'s `highContrast` parameter is always `false` today. Kept
     * so the solver's signature already matches Swift's when that setting is wired up.
     */
    const val INCREASED_CONTRAST_FLOOR = 7.0

    private const val LINEAR_THRESHOLD = 0.03928
    private const val LINEAR_DIVISOR = 12.92
    private const val CURVE_OFFSET = 0.055
    private const val CURVE_DIVISOR = 1.055
    private const val CURVE_EXPONENT = 2.4

    private const val RED_COEFFICIENT = 0.2126
    private const val GREEN_COEFFICIENT = 0.7152
    private const val BLUE_COEFFICIENT = 0.0722

    /** Additive term in the WCAG contrast ratio, modelling ambient flare. */
    private const val CONTRAST_FLARE = 0.05

    private fun linearize(component: Double): Double {
        val c = component.coerceIn(0.0, 1.0)
        return if (c <= LINEAR_THRESHOLD) c / LINEAR_DIVISOR else ((c + CURVE_OFFSET) / CURVE_DIVISOR).pow(CURVE_EXPONENT)
    }

    /** Relative luminance of an sRGB color expressed as 0..1 components. */
    fun relativeLuminance(red: Double, green: Double, blue: Double): Double =
        RED_COEFFICIENT * linearize(red) + GREEN_COEFFICIENT * linearize(green) + BLUE_COEFFICIENT * linearize(blue)

    /** Relative luminance of a resolved Compose [Color]. */
    fun relativeLuminance(color: Color): Double =
        relativeLuminance(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble())

    /** Contrast ratio between two relative-luminance values (order-independent). */
    fun contrastRatio(lhs: Double, rhs: Double): Double {
        val lighter = maxOf(lhs, rhs)
        val darker = minOf(lhs, rhs)
        return (lighter + CONTRAST_FLARE) / (darker + CONTRAST_FLARE)
    }
}
