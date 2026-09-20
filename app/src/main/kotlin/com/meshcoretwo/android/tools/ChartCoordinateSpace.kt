// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Chart padding in pixels (already density-resolved), analogous to SwiftUI's `EdgeInsets`. */
data class ChartPadding(val top: Float, val start: Float, val end: Float, val bottom: Float)

/**
 * Transforms data coordinates (meters) to canvas pixel coordinates. Ported from
 * `ChartCoordinateSpace.swift`; lives in `app` rather than `services/rf` for the same reason as its
 * Swift counterpart — it's `Offset`/`Size`-shaped View-layer math, not portable geometry (contrast
 * [com.meshcoretwo.services.rf.ProfileSample], which has no Canvas/Compose dependency and does live
 * in `services`).
 */
class ChartCoordinateSpace(
    private val canvasSize: Size,
    private val padding: ChartPadding,
    private val xRange: ClosedFloatingPointRange<Double>,
    private val yRange: ClosedFloatingPointRange<Double>,
) {
    private val plotWidth: Float get() = canvasSize.width - padding.start - padding.end
    private val plotHeight: Float get() = canvasSize.height - padding.top - padding.bottom

    /** Converts an x data value (meters) to a pixel x coordinate. */
    fun xPixel(xMeters: Double): Float {
        val fraction = (xMeters - xRange.start) / (xRange.endInclusive - xRange.start)
        return padding.start + (fraction * plotWidth).toFloat()
    }

    /** Converts a y data value (meters) to a pixel y coordinate (inverted — canvas origin is top-left, data origin is bottom-left). */
    fun yPixel(yMeters: Double): Float {
        val fraction = (yMeters - yRange.start) / (yRange.endInclusive - yRange.start)
        return canvasSize.height - padding.bottom - (fraction * plotHeight).toFloat()
    }

    /** Converts a data point (meters) to a canvas pixel point. */
    fun point(x: Double, y: Double): Offset = Offset(xPixel(x), yPixel(y))
}
