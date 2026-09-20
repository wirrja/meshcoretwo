// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.services.rf.ClearanceStatus
import com.meshcoretwo.services.rf.GeoCoordinate
import java.util.Locale

/**
 * Display color for a [ClearanceStatus]. Ported from `ClearanceStatus+UI.swift`'s `color`
 * (`.green`/`.yellow`/`.orange`/`.red`) — now [MeshExtendedColors][com.meshcoretwo.android.ui
 * .theme.MeshExtendedColors]'s success/caution/warning/danger tokens instead of flat hex.
 */
val ClearanceStatus.color: Color
    @Composable get() {
        val extended = LocalMeshExtendedColors.current
        return when (this) {
            ClearanceStatus.CLEAR -> extended.success
            ClearanceStatus.MARGINAL -> extended.caution
            ClearanceStatus.PARTIAL_OBSTRUCTION -> extended.warning
            ClearanceStatus.BLOCKED -> extended.danger
        }
    }

/**
 * Status icon, standing in for `ClearanceStatus+UI.swift`'s SF Symbol `iconName`.
 */
val ClearanceStatus.iconRes: Int
    @DrawableRes get() = when (this) {
        ClearanceStatus.CLEAR -> R.drawable.ic_check
        ClearanceStatus.MARGINAL, ClearanceStatus.PARTIAL_OBSTRUCTION -> R.drawable.ic_warning
        ClearanceStatus.BLOCKED -> R.drawable.ic_close
    }

@get:StringRes
val ClearanceStatus.labelRes: Int
    get() = when (this) {
        ClearanceStatus.CLEAR -> R.string.los_clear
        ClearanceStatus.MARGINAL -> R.string.los_marginal
        ClearanceStatus.PARTIAL_OBSTRUCTION -> R.string.los_partial
        ClearanceStatus.BLOCKED -> R.string.los_blocked
    }

/** Ported from `ClearanceStatus+UI.swift`'s static `blockedSubtitle`. */
@get:StringRes
val CLEARANCE_BLOCKED_SUBTITLE_RES: Int
    get() = R.string.los_blocked_subtitle

/**
 * Formatting utilities for Line of Sight results display. Ported from `LOSFormatters.swift`, with
 * one deliberate simplification: this port has no locale measurement-system plumbing anywhere yet
 * (no screen in this app switches between metric/imperial — see [HeightEditorGrid]'s doc), so
 * distances/elevations are metric-only here instead of following the device locale like the Swift
 * `Measurement` APIs do.
 */
object LOSFormatters {
    /** "+ 8.4 dB" for a non-negligible diffraction loss, or null when it's under 0.1 dB. */
    fun formatDiffractionLoss(lossDb: Double): String? {
        if (kotlin.math.abs(lossDb) < 0.1) return null
        return "+ ${"%.1f".format(Locale.ROOT, lossDb)} dB"
    }

    /** "126.6 dB". */
    fun formatPathLoss(lossDb: Double): String = "${"%.1f".format(Locale.ROOT, lossDb)} dB"

    /** Clearance percentage clamped to 0-100 for display. */
    fun formatClearancePercent(percent: Double): Int = percent.coerceIn(0.0, 100.0).toInt()

    /** "12.4 km" or "850 m" — metric-only, see class doc. */
    fun formatDistance(meters: Double): String =
        if (meters >= 1000) "${"%.1f".format(Locale.ROOT, meters / 1000)} km" else "${meters.toInt()} m"

    /** "245 m" for an elevation/height value. */
    fun formatElevation(meters: Double): String = "${Math.round(meters)} m"

    /** "906 MHz" or "915.5 MHz". */
    fun formatFrequency(mhz: Double): String =
        if (mhz % 1.0 == 0.0) "${mhz.toInt()} MHz" else "${"%.1f".format(Locale.ROOT, mhz)} MHz"

    /** "k=1.33". */
    fun formatKFactor(k: Double): String = "k=${"%.2f".format(Locale.ROOT, k)}"

    /** "906 MHz, k=1.33, 60% 1st Fresnel threshold" — the assumptions footnote under expanded results. */
    fun formatAssumptions(frequencyMHz: Double, k: Double): String =
        "${formatFrequency(frequencyMHz)}, ${formatKFactor(k)}, 60% 1st Fresnel threshold"

    /** "37.774900, -122.419400" — ported from `CLLocationCoordinate2D+Formatting.swift`'s `formattedString`. */
    fun formatCoordinate(coordinate: GeoCoordinate): String =
        "${"%.6f".format(Locale.ROOT, coordinate.latitude)}, ${"%.6f".format(Locale.ROOT, coordinate.longitude)}"
}
