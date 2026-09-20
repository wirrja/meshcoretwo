// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R

/**
 * The run-trace button and its running-state indicator. Ported from `RunTraceSectionView.swift`,
 * minus the `showJumpToPath`/scroll-to-bottom affordance (an artifact of `List`'s `.id("bottom")`
 * anchor and a "jump to run trace" accessibility action neither of which this port's plain
 * `LazyColumn` needs — the button is always the last item, already reachable by scrolling).
 */
@Composable
fun RunTraceSection(state: TracePathUiState, canRun: Boolean, onRunTrace: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
        if (state.isRunning) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    if (state.batchEnabled) stringResource(R.string.trace_running_n, state.currentTraceIndex, state.batchSize) else stringResource(R.string.trace_running),
                )
            }
        } else {
            Button(onClick = onRunTrace, enabled = canRun, modifier = Modifier.widthIn(min = 160.dp)) {
                Text(stringResource(R.string.trace_run))
            }
        }
    }
}
