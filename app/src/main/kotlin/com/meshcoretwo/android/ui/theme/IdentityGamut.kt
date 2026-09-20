// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A theme's identity-color space: the hues that give the theme its character, plus a saturation
 * band. [color] maps any name to a deterministic color drawn from this gamut, with brightness
 * (and, when needed, saturation) solved so the result clears WCAG AA against the surfaces it
 * renders on. The hue/saturation a name resolves to is stable across launches and appearances;
 * only the brightness flips with light/dark so the color stays legible.
 *
 * Ported from `IdentityGamut.swift`. Pure `Double` math — no Compose theme lookups here — so
 * callers resolve their surface colors to relative-luminance values ([WCAGContrast]) and pass
 * them in.
 */
data class IdentityGamut(
    /**
     * Hue centers (degrees, 0..<360) that define the theme. A name lands near one of these and is
     * jittered within the gap to its neighbors, so the union covers a continuous slice of the
     * wheel while every color still reads as on-theme.
     */
    val hueAnchors: List<Double>,
    /**
     * Saturation band the solver draws from. The solver may dip below the lower bound only when a
     * hue physically cannot reach the luminance a dark background demands at full saturation.
     */
    val saturation: ClosedFloatingPointRange<Double>,
) {
    /** Hue, saturation, and brightness a name resolves to, before constructing the [Color]. */
    data class Resolved(val hue: Double, val saturation: Double, val brightness: Double)

    /** Sorted hue anchors, the on-theme hues a name can land on before jitter. */
    val sortedAnchors: List<Double> get() = hueAnchors.sorted()

    /**
     * Resolves the on-theme identity color for [name], guaranteed to clear the contrast floor
     * against every surface in [backgroundLuminances] (all expected to share the current
     * appearance's polarity — e.g. the canvas and incoming-bubble luminances for one appearance).
     */
    fun color(
        name: String,
        backgroundLuminances: List<Double>,
        highContrast: Boolean = false,
        atHue: Double? = null,
        atVariety: Double? = null,
    ): Color {
        val resolved = resolve(name, backgroundLuminances, highContrast, atHue, atVariety)
        // Built from the exact sRGB components the solver evaluated, rather than a library HSV
        // constructor that could re-derive them in a wider gamut and drift the rendered luminance
        // off the solved value.
        val rgb = hsbToRGB(resolved.hue, resolved.saturation, resolved.brightness)
        return Color(red = rgb.red.toFloat(), green = rgb.green.toFloat(), blue = rgb.blue.toFloat())
    }

    /**
     * Assigns each name in [names] a distinct anchor hue (no jitter), bumping to the next anchor
     * on collision so a small fixed set never shares a color. Earlier names win their preferred
     * anchor; later ones step to the next free one.
     */
    fun distinctAnchorHues(names: List<String>): List<Double> {
        val anchors = sortedAnchors
        val taken = mutableSetOf<Int>()
        return names.map { name ->
            var index = (fnv1a(name) % anchors.size.toULong()).toInt()
            while (index in taken) {
                index = (index + 1) % anchors.size
            }
            taken.add(index)
            anchors[index]
        }
    }

    /**
     * Hue (degrees), saturation, and brightness [name] resolves to. Exposed so tests can resolve
     * the same values the renderer will and assert AA on the real color. [atHue] overrides the
     * jittered hue with a fixed one — used to pin category avatars to an anchor — while saturation
     * and brightness still derive from the name's seed. [atVariety] overrides the per-name
     * brightness draw (0 = darkest legible, 1 = lightest legible); category avatars pass 0 so they
     * render as deep, consistent swatches rather than washed-out brights.
     */
    fun resolve(
        name: String,
        backgroundLuminances: List<Double>,
        highContrast: Boolean = false,
        atHue: Double? = null,
        atVariety: Double? = null,
    ): Resolved {
        val seed = fnv1a(name)
        val hue = atHue ?: hueFor(seed)
        val baseSaturation = pickSaturation(seed)
        val varietyFraction = atVariety ?: fraction(seed, shift = 48)
        val floor = (if (highContrast) WCAGContrast.INCREASED_CONTRAST_FLOOR else WCAGContrast.AA_FLOOR) + CONTRAST_SAFETY_MARGIN

        val backgrounds = backgroundLuminances.ifEmpty { listOf(1.0) }
        val darkText = (backgrounds.minOrNull() ?: 1.0) >= APPEARANCE_MIDPOINT_LUMINANCE

        if (darkText) {
            // Light surfaces -> dark text. Binding surface is the darkest (least headroom).
            val bindingBackground = backgrounds.minOrNull() ?: 1.0
            val maxLuminance = (bindingBackground + CONTRAST_FLARE) / floor - CONTRAST_FLARE
            val safeMax = maxOf(maxLuminance, 0.0)
            val targetLuminance = safeMax * (DARK_VARIETY_FLOOR + (1 - DARK_VARIETY_FLOOR) * varietyFraction)
            val brightness = brightnessFor(targetLuminance, hue, baseSaturation)
            return Resolved(hue, baseSaturation, brightness)
        } else {
            // Dark surfaces -> light text. Binding surface is the lightest (least headroom).
            val bindingBackground = backgrounds.maxOrNull() ?: 0.0
            // Required luminance to clear the floor. Not capped: a light dark-mode bubble can
            // demand near-white text, and undershooting it would breach AA.
            val requiredLuminance = maxOf(floor * (bindingBackground + CONTRAST_FLARE) - CONTRAST_FLARE, 0.0)
            // A vivid hue caps the luminance it can reach at full brightness; desaturate as far as
            // needed to reach the requirement. Legibility outranks saturation in the rare
            // high-contrast-on-dark case that forces this; a gray reaches any luminance.
            var sat = baseSaturation
            while (sat > SATURATION_REACH_FLOOR && luminance(hue, sat, 1.0) < requiredLuminance) {
                sat = maxOf(SATURATION_REACH_FLOOR, sat - SATURATION_RELAX_STEP)
            }
            // Vary lighter than the minimum (lighter = more contrast = still AA), capped at what
            // the hue can reach so the target is always achievable.
            val reachableMax = luminance(hue, sat, 1.0)
            val ceiling = maxOf(requiredLuminance, minOf(reachableMax, LIGHT_LUMINANCE_CAP))
            val targetLuminance = minOf(requiredLuminance + (ceiling - requiredLuminance) * varietyFraction, reachableMax)
            val brightness = brightnessFor(targetLuminance, hue, sat)
            return Resolved(hue, sat, brightness)
        }
    }

    private fun hueFor(seed: ULong): Double {
        val sorted = hueAnchors.sorted()
        val index = (seed % sorted.size.toULong()).toInt()
        val anchor = sorted[index]
        // Gap to each neighbor on the circle; jitter within half the gap so anchors tile the wheel.
        val next = sorted[(index + 1) % sorted.size]
        val prev = sorted[(index - 1 + sorted.size) % sorted.size]
        val gapNext = circularGap(anchor, next)
        val gapPrev = circularGap(prev, anchor)
        val fraction = fraction(seed, shift = 16) * 2 - 1
        val jitter = if (fraction >= 0) fraction * gapNext / 2 else fraction * gapPrev / 2
        val raw = (anchor + jitter) % DEGREES_PER_CIRCLE
        return if (raw < 0) raw + DEGREES_PER_CIRCLE else raw
    }

    private fun pickSaturation(seed: ULong): Double =
        saturation.start + (saturation.endInclusive - saturation.start) * fraction(seed, shift = 32)

    companion object {
        private const val DEGREES_PER_CIRCLE = 360.0

        /**
         * Floor the solver desaturates toward when a vivid hue cannot otherwise reach the
         * luminance a dark surface requires. Near zero so legibility is always achievable (a gray
         * reaches any luminance); colors only wash out this far in the rare high-contrast-on-dark
         * case that needs it.
         */
        private const val SATURATION_REACH_FLOOR = 0.0

        /** Step used when relaxing saturation to reach an unreachable luminance target. */
        private const val SATURATION_RELAX_STEP = 0.04

        /**
         * Fraction of the AA-feasible luminance span used as the dark-text variety window. Names
         * spread across `[darkVarietyFloor, boundary]` so brightness varies per identity without
         * breaching AA.
         */
        private const val DARK_VARIETY_FLOOR = 0.45

        /** Upper luminance cap for light-text (dark-appearance) colors so they never go pure white. */
        private const val LIGHT_LUMINANCE_CAP = 0.86

        /**
         * Added to the target contrast ratio so rendering drift (HSB rounding, wide-gamut
         * resolution) cannot drop the rendered color below the real AA floor.
         */
        private const val CONTRAST_SAFETY_MARGIN = 0.35

        /** Binary-search iteration count for mapping a luminance target to a brightness value. */
        private const val BRIGHTNESS_SEARCH_ITERATIONS = 24

        /** Luminance above which a background is treated as "light" (dark text) rather than "dark". */
        private const val APPEARANCE_MIDPOINT_LUMINANCE = 0.3

        private const val CONTRAST_FLARE = 0.05

        /**
         * The glyph (initials / icon) color that reads on a fill of the given luminance: white
         * when the fill is dark enough, otherwise near-black. One of the two always clears AA for
         * any fill.
         */
        fun glyphColor(fillLuminance: Double): Color {
            val whiteContrast = (1.0 + CONTRAST_FLARE) / (fillLuminance + CONTRAST_FLARE)
            val blackContrast = (fillLuminance + CONTRAST_FLARE) / CONTRAST_FLARE
            return if (whiteContrast >= blackContrast) Color.White else Color(red = 0.1f, green = 0.1f, blue = 0.1f)
        }

        private fun brightnessFor(target: Double, hue: Double, saturation: Double): Double {
            var low = 0.0
            var high = 1.0
            repeat(BRIGHTNESS_SEARCH_ITERATIONS) {
                val mid = (low + high) / 2
                if (luminance(hue, saturation, mid) < target) low = mid else high = mid
            }
            return (low + high) / 2
        }

        private fun luminance(hue: Double, saturation: Double, brightness: Double): Double {
            val rgb = hsbToRGB(hue, saturation, brightness)
            return WCAGContrast.relativeLuminance(rgb.red, rgb.green, rgb.blue)
        }

        private fun circularGap(from: Double, to: Double): Double {
            val raw = (to - from) % DEGREES_PER_CIRCLE
            return if (raw <= 0) raw + DEGREES_PER_CIRCLE else raw
        }

        private data class RGB(val red: Double, val green: Double, val blue: Double)

        /**
         * Standard sRGB HSB-to-RGB conversion (`hue` in degrees). Implemented directly (rather
         * than via a Compose HSV helper) so the solver's luminance prediction and the rendered
         * color are built from the exact same math; [CONTRAST_SAFETY_MARGIN] absorbs any residual
         * drift from that assumption.
         */
        private fun hsbToRGB(hue: Double, saturation: Double, brightness: Double): RGB {
            val h = (((hue % DEGREES_PER_CIRCLE) + DEGREES_PER_CIRCLE) % DEGREES_PER_CIRCLE) / 60
            val sector = h.toInt() % 6
            val fraction = h - h.toInt()
            val p = brightness * (1 - saturation)
            val q = brightness * (1 - fraction * saturation)
            val t = brightness * (1 - (1 - fraction) * saturation)
            return when (sector) {
                0 -> RGB(brightness, t, p)
                1 -> RGB(q, brightness, p)
                2 -> RGB(p, brightness, t)
                3 -> RGB(p, q, brightness)
                4 -> RGB(t, p, brightness)
                else -> RGB(brightness, p, q)
            }
        }

        /**
         * FNV-1a 64-bit: a stable, well-distributed hash. Stable across launches (unlike a
         * randomized per-process hash) and free of the order-insensitivity that makes XOR-folding
         * collide anagrams and cluster on a continuous hue space.
         */
        private fun fnv1a(string: String): ULong {
            var hash = 0xCBF29CE484222325uL
            for (byte in string.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toULong() and 0xFFu)
                hash *= 0x100000001B3uL
            }
            return hash
        }

        /**
         * A 0..1 fraction from 16 bits of the hash starting at [shift], giving independent draws
         * for hue jitter, saturation, and brightness variety from one hash.
         */
        private fun fraction(seed: ULong, shift: Int): Double =
            ((seed shr shift) and 0xFFFFu).toDouble() / 0xFFFF.toDouble()
    }
}
