// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.pathediting.AddHopPickerScreen
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import kotlinx.coroutines.launch

/**
 * List-based trace-path builder screen — the "list" half of `TracePathView.swift`'s view-mode
 * switch (the map half is a later slice, see PLAN.md). Ported from `TracePathListView.swift` plus
 * its `PathActionsSectionView`/`RunTraceSectionView` section satellites, folded into one file since
 * (unlike the Add-Hop picker's satellites) none of these is reused anywhere else.
 *
 * Deliberate simplifications vs. Swift:
 * - **No drag-to-reorder.** See [TracePathHopRow]'s doc — move-up/move-down buttons instead.
 * - **Saved-paths entry point moved here from `TracePathView.swift`.** Swift's "bookmark" toolbar
 *   button lives on the outer view-mode-switching screen; since that screen has no Android
 *   equivalent yet (see the class doc above), the same button and its [SavedPathsScreen] live on
 *   this screen instead — the only place in the port a user can reach it from today.
 * - **No location wiring.** Unlike `AppState.bestAvailableLocation`, nothing calls
 *   [TracePathViewModel.setCurrentLocation] yet — the future map slice will, the same way
 *   `LineOfSightScreen` drives its own on-demand location fetch. Hash-collision node resolution
 *   just degrades to "first match" without it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracePathListScreen(connectionManager: ConnectionManager, prefs: SharedPreferences, onBack: () -> Unit) {
    val viewModel: TracePathViewModel = viewModel(factory = TracePathViewModel.Factory(connectionManager, prefs))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showAddHopPicker by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showSavedPaths by remember { mutableStateOf(false) }
    var presentedResult by remember { mutableStateOf<TraceResult?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val device = connectionManager.connectedDeviceRecord
    val canRun = state.canRunTraceWhenConnected

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    // Auto-show the results screen when a trace completes successfully. Ported from
    // `TracePathView.swift`'s `.onChange(of: viewModel.resultID)`.
    LaunchedEffect(state.resultId) {
        if (state.resultId == null) return@LaunchedEffect
        val result = state.result
        if (result != null && result.success) presentedResult = result
    }

    if (showAddHopPicker) {
        AddHopPickerScreen(hopPickerSource = viewModel, onDismiss = { showAddHopPicker = false })
        return
    }

    if (presentedResult != null) {
        TracePathResultsScreen(
            result = presentedResult!!,
            viewModel = viewModel,
            connectionManager = connectionManager,
            onDismiss = {
                if (state.isBatchInProgress) viewModel.cancelBatchTrace()
                presentedResult = null
            },
        )
        return
    }

    if (showSavedPaths) {
        SavedPathsScreen(
            connectionManager = connectionManager,
            onSelect = { path -> viewModel.loadSavedPath(path) },
            onDelete = { id -> viewModel.handleSavedPathDeleted(id) },
            onDismiss = { showSavedPaths = false },
        )
        return
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.common_clear_path)) },
            text = { Text(stringResource(R.string.trace_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearPath(); showClearConfirmation = false }) {
                    Text(stringResource(R.string.common_clear_path), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_trace)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = { IconButton(onClick = { showSavedPaths = true }) { Icon(painterResource(R.drawable.ic_bookmark), contentDescription = stringResource(R.string.trace_saved_paths_cd)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
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
                    Button(onClick = { showAddHopPicker = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.trace_add_hop_btn)) }
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
                    Button(onClick = { showAddHopPicker = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.trace_add_hop_btn)) }
                }
                item {
                    PathActionsSection(
                        state = state,
                        showHashSizeOverride = device?.supportsTraceHashSizeOverride == true,
                        onAutoReturnChange = viewModel::setAutoReturnPath,
                        onBatchEnabledChange = viewModel::setBatchEnabled,
                        onBatchSizeChange = viewModel::setBatchSize,
                        onHashModeChange = viewModel::setTraceHashMode,
                        onCopyPath = {
                            clipboard.setText(AnnotatedString(state.fullPathString))
                        },
                        onClearPath = { showClearConfirmation = true },
                    )
                }
            }

            item {
                RunTraceSection(
                    state = state,
                    canRun = canRun,
                    onRunTrace = {
                        scope.launch {
                            if (state.batchEnabled) viewModel.runBatchTrace() else viewModel.runTrace()
                        }
                    },
                )
            }
        }
    }
}
