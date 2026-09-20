// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.services.persistence.TracePathDto

/**
 * One row in the saved-paths list: name, run-count/last-run subtitle, health dot, and a recent-RTT
 * sparkline. Ported from `SavedPathRow.swift`. The rename/delete `.contextMenu` it doesn't own in
 * Swift either (attached by `SavedPathsSheet`, not this view) stays outside this composable too —
 * see [SavedPathsScreen].
 */
@Composable
fun SavedPathRow(path: TracePathDto) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(path.name, style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                subtitleText(path),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            HealthDot(path.successRate)
            if (path.recentRTTs.isNotEmpty()) {
                MiniSparkline(values = path.recentRTTs, modifier = Modifier.size(width = 50.dp, height = 16.dp))
            }
        }
    }
}

/** Ported from `SavedPathRow.swift`'s `subtitleText`. */
@Composable
private fun subtitleText(path: TracePathDto): String {
    val runText = if (path.runCount == 1) stringResource(R.string.saved_run_count_one) else stringResource(R.string.saved_runs, path.runCount)
    val lastRun = path.lastRunDate?.let { date ->
        stringResource(R.string.saved_last, DateUtils.getRelativeTimeSpanString(date.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString())
    }
    return listOfNotNull(runText, lastRun).joinToString(" · ")
}

/** Ported from `SavedPathRow.swift`'s `healthDot`. Same 90/50 thresholds and color triad as [TraceResultHopRow]'s SNR coloring. */
@Composable
private fun HealthDot(successRate: Int) {
    val extended = LocalMeshExtendedColors.current
    val color = when {
        successRate >= 90 -> extended.success
        successRate >= 50 -> extended.caution
        else -> extended.danger
    }
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
}
