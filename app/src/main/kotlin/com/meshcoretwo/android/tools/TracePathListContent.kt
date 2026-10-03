// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R

/**
 * List mode of [TracePathScreen]: the hop list, path options and the run button. Ported from
 * `TracePathListView.swift` plus its `PathActionsSectionView`/`RunTraceSectionView` section
 * satellites, folded into one file since (unlike the Add-Hop picker's satellites) none of these is
 * reused anywhere else.
 *
 * Deliberate simplifications vs. Swift:
 * - **No drag-to-reorder.** See [TracePathHopRow]'s doc — move-up/move-down buttons instead.
 * - **No location wiring here.** Only the map mode fetches a fix (see [TracePathMapContent]);
 *   until it has, hash-collision node resolution just degrades to "first match".
 */
@Composable
fun TracePathListContent(
    state: TracePathUiState,
    viewModel: TracePathViewModel,
    showHashSizeOverride: Boolean,
    onAddHop: () -> Unit,
    onClearPath: () -> Unit,
    onRunTrace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.outboundPath.isEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_signal_cellular_off),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.trace_no_hops), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.trace_add_hop_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                Button(onClick = onAddHop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.trace_add_hop_btn)) }
            }
        } else {
            item { Text(stringResource(R.string.trace_round_trip_path), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(state.outboundPath, key = { index, hop -> "${index}_${hop.hashHex}" }) { index, hop ->
                TracePathHopRow(
                    hop = hop,
                    hopNumber = index + 1,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.outboundPath.lastIndex,
                    onMoveUp = { viewModel.moveHop(index, index - 1) },
                    onMoveDown = { viewModel.moveHop(index, index + 1) },
                    onDelete = { viewModel.removeRepeater(index) },
                    modifier = Modifier.animateItem(),
                )
            }
            item {
                Button(onClick = onAddHop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.trace_add_hop_btn)) }
            }
            item {
                PathActionsSection(
                    state = state,
                    showHashSizeOverride = showHashSizeOverride,
                    onAutoReturnChange = viewModel::setAutoReturnPath,
                    onBatchEnabledChange = viewModel::setBatchEnabled,
                    onBatchSizeChange = viewModel::setBatchSize,
                    onHashModeChange = viewModel::setTraceHashMode,
                    onCopyPath = {
                        clipboard.setText(AnnotatedString(state.fullPathString))
                    },
                    onClearPath = onClearPath,
                )
            }
        }

        item {
            RunTraceSection(state = state, canRun = state.canRunTraceWhenConnected, onRunTrace = onRunTrace)
        }
    }
}
