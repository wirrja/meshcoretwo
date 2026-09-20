// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.services.connection.ConnectionManager

/**
 * Full-screen trace-results display: hop-by-hop breakdown, RTT/distance summary, save-path action,
 * and the copyable round-trip path string. Ported from `TraceResultsSheet.swift` +
 * `TraceResultsSectionView.swift`, assembling the satellite components from the previous slice
 * ([TraceResultHopRow], [BatchRTTRow], [ComparisonRow], [TotalDistanceRow], [SavePathRow]). A
 * full-screen composable rather than a sheet — the same convention as [DistanceInfoScreen]/
 * [com.meshcoretwo.android.pathediting.AddHopPickerScreen], no nested-modal library in this port.
 *
 * [result] is the trace snapshot that triggered showing this screen (fixed for its lifetime,
 * matching Swift's `.sheet(item:)`); batch-mode aggregates (per-hop stats, RTT, progress) instead
 * read live off [viewModel]'s state, since they keep updating while a batch trace is still running
 * underneath this screen. [TracePathListScreen] owns calling [TracePathViewModel.cancelBatchTrace]
 * on dismiss if a batch is still in progress, mirroring `TracePathView.swift`'s `.sheet(onDismiss:)`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracePathResultsScreen(result: TraceResult, viewModel: TracePathViewModel, connectionManager: ConnectionManager, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showingDistanceInfo by remember { mutableStateOf(false) }
    var showingSavedPathDetail by remember { mutableStateOf(false) }

    if (showingDistanceInfo) {
        DistanceInfoScreen(state = state, onDismiss = { showingDistanceInfo = false })
        return
    }

    val activeSavedPath = state.activeSavedPath
    if (showingSavedPathDetail && activeSavedPath != null) {
        SavedPathDetailScreen(savedPath = activeSavedPath, connectionManager = connectionManager, onDismiss = { showingSavedPathDetail = false })
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.trace_results)) },
                actions = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_dismiss)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (result.success) {
                itemsIndexed(result.hops) { index, hop ->
                    TraceResultHopRow(
                        hop = hop,
                        batchStats = if (state.batchEnabled) viewModel.hopStats(index) else null,
                        latestSNR = if (state.batchEnabled) viewModel.latestHopSNR(index) else null,
                        isBatchInProgress = state.isBatchInProgress,
                    )
                }

                if (state.batchEnabled && (state.isBatchInProgress || state.isBatchComplete)) {
                    item {
                        val successPercent = if (state.batchSize > 0) (state.successCount * 100) / state.batchSize else 0
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.isBatchComplete) {
                                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = LocalMeshExtendedColors.current.success)
                                Text(
                                    "${state.successCount} of ${state.batchSize} successful ($successPercent%)",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    stringResource(R.string.trace_running_n_dots, state.currentTraceIndex, state.batchSize),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item {
                    val savedPath = state.activeSavedPath
                    val previous = state.previousRun
                    when {
                        state.batchEnabled && state.successCount > 0 -> BatchRTTRow(state)
                        state.isRunningSavedPath && savedPath != null && previous != null ->
                            ComparisonRow(
                                currentMs = result.durationMs,
                                previousRun = previous,
                                recentRTTs = savedPath.recentRTTs,
                                runCount = savedPath.runCount,
                                onViewRuns = { showingSavedPathDetail = true },
                            )
                        else -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.trace_round_trip), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${result.durationMs} ms")
                        }
                    }
                }

                item {
                    TotalDistanceRow(
                        distanceMeters = state.totalPathDistance,
                        isDistanceUsingFallback = state.isDistanceUsingFallback,
                        onShowDistanceInfo = { showingDistanceInfo = true },
                    )
                }

                if (!state.isRunningSavedPath) {
                    item {
                        SavePathRow(canSavePath = state.canSavePath, generateName = viewModel::generatePathName, onSave = viewModel::savePath)
                    }
                }
            } else if (result.errorMessage != null) {
                item {
                    val warningColor = LocalMeshExtendedColors.current.warning
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = warningColor)
                        Text(result.errorMessage, color = warningColor)
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.trace_round_trip_path), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        result.tracedPathString,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { clipboard.setText(AnnotatedString(result.tracedPathString)) }) { Text(stringResource(R.string.common_copy)) }
                }
            }
        }
    }
}
