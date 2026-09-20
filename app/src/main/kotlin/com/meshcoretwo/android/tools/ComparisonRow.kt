// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.DatePatterns
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.services.persistence.TracePathRunDto
import java.time.ZoneId
import java.time.format.DateTimeFormatter


/**
 * Compares the just-completed trace's RTT against the active saved path's most recent run. Ported
 * from `ComparisonRowView.swift`. Only rendered when [TracePathUiState.isRunningSavedPath] is true,
 * which requires loading a saved path first, via [SavedPathsScreen].
 *
 * The sparkline's `NavigationLink` to `SavedPathDetailView` becomes [onViewRuns], a plain callback
 * — [TracePathResultsScreen] owns showing [SavedPathDetailScreen], the same split it already uses
 * for [DistanceInfoScreen].
 */
@Composable
fun ComparisonRow(currentMs: Int, previousRun: TracePathRunDto, recentRTTs: List<Int>, runCount: Int, onViewRuns: () -> Unit) {
    val diff = currentMs - previousRun.roundTripMs
    val percentChange = if (previousRun.roundTripMs > 0) diff.toDouble() / previousRun.roundTripMs * 100 else 0.0
    val extended = LocalMeshExtendedColors.current

    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.trace_round_trip), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("$currentMs ms")
            if (diff != 0) {
                Text(if (diff > 0) "▲" else "▼", color = if (diff > 0) extended.danger else extended.success)
                Text("${"%.0f".format(kotlin.math.abs(percentChange))}%", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            stringResource(R.string.trace_vs_prev, previousRun.roundTripMs, DatePatterns.dateMedium().format(previousRun.date)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (recentRTTs.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                MiniSparkline(values = recentRTTs, modifier = Modifier.width(60.dp).height(20.dp))
                TextButton(onClick = onViewRuns) { Text(stringResource(R.string.trace_view_runs, runCount), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
