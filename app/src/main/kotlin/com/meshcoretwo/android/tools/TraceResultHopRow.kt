// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.snrQualityColor
import com.meshcoretwo.android.ui.theme.snrQualityGlyph
import com.meshcoretwo.services.rendering.SNRQuality

/**
 * One hop in a completed trace's result list. Ported from `TraceResultHopRow.swift`. The SwiftUI
 * `Image(systemName: "cellularbars", variableValue:)` signal-strength icon becomes a bar-glyph
 * string colored by [SNRQuality] ([snrQualityGlyph]/[snrQualityColor], shared with `RxLogScreen`
 * and, since Phase 6 slice 7, `RepeaterStatusScreen`'s neighbor rows).
 */
@Composable
fun TraceResultHopRow(hop: TraceHop, batchStats: Triple<Double, Double, Double>?, latestSNR: Double?, isBatchInProgress: Boolean) {
    val displaySNR = when {
        isBatchInProgress -> latestSNR ?: hop.snr
        batchStats != null -> batchStats.first
        else -> hop.snr
    }
    val quality = SNRQuality.of(displaySNR)

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            when {
                hop.isStartNode -> {
                    Text(hop.resolvedName ?: TracePathStrings.MY_DEVICE)
                    Text(stringResource(R.string.trace_started), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                hop.isEndNode -> {
                    Text(hop.resolvedName ?: TracePathStrings.MY_DEVICE, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.trace_received), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                hop.hashDisplayString != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(hop.hashDisplayString!!, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        hop.resolvedName?.let { Text(it) }
                    }
                    Text(stringResource(R.string.trace_repeated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (!hop.isStartNode) {
                val snrText = if (batchStats != null) {
                    stringResource(R.string.trace_avg_snr, "%.1f".format(batchStats.first), "%.1f".format(batchStats.second), "%.1f".format(batchStats.third))
                } else {
                    "SNR: %.2f dB".format(hop.snr)
                }
                Text(snrText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (!hop.isStartNode) {
            Text(snrQualityGlyph(quality), color = snrQualityColor(quality))
        }
    }
}
