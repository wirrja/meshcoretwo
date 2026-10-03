// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import android.content.SharedPreferences
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.pathediting.AddHopPickerScreen
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.location.LocationProvider
import kotlinx.coroutines.launch

/** How the path is built. Ported from `TracePathViewMode`. */
enum class TracePathViewMode { LIST, MAP }

private const val KEY_VIEW_MODE = "trace_path_view_mode"

private fun loadViewMode(prefs: SharedPreferences): TracePathViewMode =
    TracePathViewMode.entries.firstOrNull { it.name == prefs.getString(KEY_VIEW_MODE, null) } ?: TracePathViewMode.LIST

/**
 * The Trace Path tool. Ported from `TracePathView.swift`: a List/Map switch in place of the title
 * (remembered across visits, like Swift's `@AppStorage`), both modes driving one
 * [TracePathViewModel], plus what both modes open — the hop picker, the results screen, saved
 * paths (the bookmark action) and the clear-path confirmation. Swift's sheets become full-screen
 * swaps here, as in the rest of the port.
 *
 * A successful trace opens the results by itself only in list mode, as in Swift; the map grades
 * its lines by SNR instead and opens the results from its own button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracePathScreen(
    connectionManager: ConnectionManager,
    locationProvider: LocationProvider,
    prefs: SharedPreferences,
    onBack: () -> Unit,
) {
    val viewModel: TracePathViewModel = viewModel(factory = TracePathViewModel.Factory(connectionManager, prefs))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var viewMode by remember { mutableStateOf(loadViewMode(prefs)) }
    var showAddHopPicker by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showSavedPaths by remember { mutableStateOf(false) }
    var presentedResult by remember { mutableStateOf<TraceResult?>(null) }
    // Kept here rather than in the map so that it survives switching modes.
    var userLocation by remember { mutableStateOf<LocationFix?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    // Ported from `TracePathView.swift`'s `.onChange(of: viewModel.resultID)`.
    LaunchedEffect(state.resultId) {
        if (state.resultId == null || viewMode != TracePathViewMode.LIST) return@LaunchedEffect
        val result = state.result
        if (result != null && result.success) presentedResult = result
    }

    if (showAddHopPicker) {
        AddHopPickerScreen(hopPickerSource = viewModel, onDismiss = { showAddHopPicker = false })
        return
    }

    presentedResult?.let { result ->
        TracePathResultsScreen(
            result = result,
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
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                title = {
                    ViewModePicker(viewMode) { mode ->
                        viewMode = mode
                        prefs.edit { putString(KEY_VIEW_MODE, mode.name) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = { IconButton(onClick = { showSavedPaths = true }) { Icon(painterResource(R.drawable.ic_bookmark), contentDescription = stringResource(R.string.trace_saved_paths_cd)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (viewMode) {
            TracePathViewMode.LIST -> TracePathListContent(
                state = state,
                viewModel = viewModel,
                showHashSizeOverride = connectionManager.connectedDeviceRecord?.supportsTraceHashSizeOverride == true,
                onAddHop = { showAddHopPicker = true },
                onClearPath = { showClearConfirmation = true },
                onRunTrace = {
                    scope.launch { if (state.batchEnabled) viewModel.runBatchTrace() else viewModel.runTrace() }
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            TracePathViewMode.MAP -> TracePathMapContent(
                state = state,
                viewModel = viewModel,
                locationProvider = locationProvider,
                prefs = prefs,
                userLocation = userLocation,
                onUserLocation = { fix ->
                    userLocation = fix
                    viewModel.setCurrentLocation(fix)
                },
                snackbarHostState = snackbarHostState,
                onClearPath = { showClearConfirmation = true },
                onRunTrace = {
                    // Swift's map always runs a single trace; batches stay a list-mode option.
                    viewModel.setBatchEnabled(false)
                    scope.launch { viewModel.runTrace() }
                },
                onShowResults = { presentedResult = it },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun ViewModePicker(selected: TracePathViewMode, onSelect: (TracePathViewMode) -> Unit) {
    val options = listOf(
        TracePathViewMode.LIST to stringResource(R.string.trace_mode_list),
        TracePathViewMode.MAP to stringResource(R.string.trace_mode_map),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.width(IntrinsicSize.Max)) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                // No check mark: it would make the two halves differ in width; the fill shows the choice.
                icon = {},
            ) {
                Text(label)
            }
        }
    }
}
