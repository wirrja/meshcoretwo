// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.meshcoretwo.services.rendering.SNRQuality

/**
 * Color for a [SNRQuality] indicator. Ported from `SNRQuality+Color.swift`'s `color`: excellent/good
 * share the theme's `success` extended color, fair uses `caution`, poor uses `danger`, unknown falls
 * back to `onSurfaceVariant` (iOS: `.secondary`). Hoisted here once a third consumer (repeater
 * neighbor rows, Phase 6 slice 7) needed the same mapping `RxLogScreen`/`TraceResultHopRow` had each
 * defined privately since Phase 6 slice 2 — the same "hoist once a second consumer appears" pattern
 * [SNRQuality] itself documents.
 */
@Composable
fun snrQualityColor(quality: SNRQuality): Color {
    val extended = LocalMeshExtendedColors.current
    return when (quality) {
        SNRQuality.EXCELLENT, SNRQuality.GOOD -> extended.success
        SNRQuality.FAIR -> extended.caution
        SNRQuality.POOR -> extended.danger
        SNRQuality.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Bar-glyph string for a [SNRQuality] indicator (`▂▄▆█`/`▂▄▆_`/`▂▄__`/`▂___`/`----`) — the Compose
 * text-glyph stand-in this port uses in place of iOS's `Image(systemName: "cellularbars",
 * variableValue:)`, established by `RxLogScreen`/`TraceResultHopRow` and reused as-is rather than
 * inventing a second convention.
 */
fun snrQualityGlyph(quality: SNRQuality): String = when (quality) {
    SNRQuality.EXCELLENT -> "▂▄▆█"
    SNRQuality.GOOD -> "▂▄▆_"
    SNRQuality.FAIR -> "▂▄__"
    SNRQuality.POOR -> "▂___"
    SNRQuality.UNKNOWN -> "----"
}
