// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.EmptyState
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.persistence.TracePathDto
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Full-screen list of saved trace paths. Ported from `SavedPathsSheet.swift` as a pushed screen
 * rather than a `.sheet` — the same convention as every other modal replacement in this port (see
 * [com.meshcoretwo.android.pathediting.AddHopPickerScreen]). The `.contextMenu` (long-press)
 * rename/delete affordance becomes an explicit "⋯" menu button instead — this port's only other
 * per-row menu ([LineOfSightComponents.kt]'s share menu) is already button-triggered, not
 * gesture-triggered, so there's no established long-press pattern to reuse here either.
 *
 * [onSelect] and [onDelete] mirror `SavedPathsSheet`'s two callbacks exactly: the caller decides
 * what a selection/deletion means (loading the path into the builder, clearing an active
 * reference) instead of this screen owning that logic, matching Swift's split between
 * `SavedPathsSheet` and `TracePathView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPathsScreen(connectionManager: ConnectionManager, onSelect: (TracePathDto) -> Unit, onDelete: (UUID) -> Unit, onDismiss: () -> Unit) {
    val viewModel: SavedPathsViewModel = viewModel(factory = SavedPathsViewModel.Factory(connectionManager))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var pathToDelete by remember { mutableStateOf<TracePathDto?>(null) }
    var pathToRename by remember { mutableStateOf<TracePathDto?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadSavedPaths() }
    val context = LocalContext.current
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it.resolve(context)) }
    }

    pathToDelete?.let { path ->
        AlertDialog(
            onDismissRequest = { pathToDelete = null },
            title = { Text(stringResource(R.string.saved_delete_title)) },
            text = { Text(stringResource(R.string.saved_delete_body, path.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (viewModel.deletePath(path)) onDelete(path.id)
                        pathToDelete = null
                    }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pathToDelete = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    pathToRename?.let { path ->
        AlertDialog(
            onDismissRequest = { pathToRename = null },
            title = { Text(stringResource(R.string.saved_rename_title)) },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text(stringResource(R.string.contacts_name)) }) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { viewModel.renamePath(path, renameText) }
                        pathToRename = null
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { pathToRename = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.saved_title)) },
                actions = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.savedPaths.isEmpty() && !state.isLoading) {
                EmptyState(
                    icon = R.drawable.ic_bookmark,
                    title = stringResource(R.string.saved_none),
                    description = stringResource(R.string.saved_none_desc),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.savedPaths, key = { it.id }) { path ->
                        var showMenu by remember(path.id) { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.animateItem().fillMaxWidth()
                                .clickable { onSelect(path); onDismiss() }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.weight(1f)) { SavedPathRow(path) }
                            Box {
                                IconButton(onClick = { showMenu = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.common_more)) }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.common_rename)) },
                                        onClick = {
                                            showMenu = false
                                            renameText = path.name
                                            pathToRename = path
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.common_delete)) },
                                        onClick = {
                                            showMenu = false
                                            pathToDelete = path
                                        },
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
