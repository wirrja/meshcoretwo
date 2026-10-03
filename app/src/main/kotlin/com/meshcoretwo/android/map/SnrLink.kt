// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import com.meshcoretwo.android.tools.LOSFormatters
import com.meshcoretwo.services.rendering.SNRQuality
import java.util.Locale
import kotlin.math.abs

/**
 * Pieces of an SNR-graded link drawn between two nodes, shared by the neighbor SNR map and the
 * trace path map. Ported from `MapLine+SNR.swift`.
 */
object SnrLink {
    /** Midpoint distance/SNR label, "120 m · -3.2 dB". */
    fun badgeText(distanceMeters: Double, snr: Double): String =
        "${LOSFormatters.formatDistance(distanceMeters)} · ${"%.1f".format(Locale.US, snr)} dB"

    /**
     * Geographic midpoint of two coordinates as (latitude, longitude), shifting one longitude by
     * 360° before averaging when the pair straddles the antimeridian so the badge lands between
     * them rather than on the opposite hemisphere.
     */
    fun midpoint(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        var a = lon1
        var b = lon2
        if (abs(a - b) > 180) {
            if (a < b) a += 360 else b += 360
        }
        var midLongitude = (a + b) / 2
        if (midLongitude > 180) midLongitude -= 360
        return (lat1 + lat2) / 2 to midLongitude
    }

    /**
     * Hex line color per [SNRQuality] — the light-theme values of `ui.theme`'s success/caution
     * /danger tokens (same ones `TraceResultHopRow.kt`'s `signalColor` uses), fixed rather than read
     * from [LocalMeshExtendedColors][com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors]: map
     * layers are built once per style load, not in a `@Composable`, and aren't re-styled on theme
     * change.
     */
    fun lineColorHex(quality: SNRQuality): String = when (quality) {
        SNRQuality.EXCELLENT, SNRQuality.GOOD -> "#1B6D24"
        SNRQuality.FAIR -> "#835400"
        SNRQuality.POOR -> "#B91D20"
        SNRQuality.UNKNOWN -> "#9E9E9E"
    }
}
