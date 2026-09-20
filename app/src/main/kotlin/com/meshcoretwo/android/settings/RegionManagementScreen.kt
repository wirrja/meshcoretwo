// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.meshcoretwo.android.ui.components.ConnectingState
import com.meshcoretwo.android.ui.components.EmptyState
import com.meshcoretwo.android.ui.components.SearchPillField
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.settings.isPrivateRegion

/**
 * "Manage Regions" — add/remove/discover a device's known flood-routing regions. Ported from
 * `RegionManagementView.swift`, reached from Settings' `DefaultFloodScopeSection`. Swift's
 * `.searchable()`-when-`count >= 15` gate and swipe-to-delete are ported as an always-visible
 * search field once the list crosses that threshold and a per-row delete [IconButton]
 * respectively — this port has no swipe-to-dismiss precedent anywhere else yet (see
 * [RegionRow]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionManagementScreen(connectionManager: ConnectionManager, onBack: () -> Unit) {
    val viewModel: RegionManagementViewModel = viewModel(factory = RegionManagementViewModel.Factory(connectionManager))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isDiscovering by viewModel.isDiscovering.collectAsStateWithLifecycle()
    val discoveryMessage by viewModel.discoveryMessage.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var searchText by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    LaunchedEffect(errorMessage) {
        errorMessage?.let { snackbarHostState.showSnackbar(it.resolve(context)) }
    }

    if (showAddDialog) {
        AddRegionDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name -> viewModel.addRegion(name) },
            onAdded = { showAddDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nodeadmin_regions)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val current = state) {
            is RegionManagementUiState.Connecting -> ConnectingState(modifier = Modifier.padding(padding))
            is RegionManagementUiState.Ready -> {
                val sortedRegions = remember(current.regions, searchText) {
                    val sorted = current.regions.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                    if (searchText.isBlank()) sorted else sorted.filter { it.contains(searchText, ignoreCase = true) }
                }
                Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                    if (current.regions.size >= 15) {
                        SearchPillField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            placeholder = stringResource(R.string.common_search),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (current.regions.isEmpty()) {
                        EmptyState(
                            icon = R.drawable.ic_cell_tower,
                            title = stringResource(R.string.regions_none),
                            description = stringResource(R.string.regions_none_desc),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            items(sortedRegions, key = { it }) { region ->
                                RegionRow(name = region, onDelete = { viewModel.removeRegion(region) })
                                HorizontalDivider()
                            }
                        }
                    }
                    DiscoveryActionsSection(
                        isDiscovering = isDiscovering,
                        discoveryMessage = discoveryMessage,
                        onDiscoverTapped = viewModel::runDiscovery,
                        onAddManuallyTapped = { showAddDialog = true },
                    )
                }
            }
        }
    }
}


@Composable
private fun RegionRow(name: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name)
            if (name.isPrivateRegion) {
                Text(
                    stringResource(R.string.common_private),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_remove), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DiscoveryActionsSection(
    isDiscovering: Boolean,
    discoveryMessage: UiText?,
    onDiscoverTapped: () -> Unit,
    onAddManuallyTapped: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        if (isDiscovering) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.regions_discovering), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (discoveryMessage != null) {
            Text(
                discoveryMessage.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        OutlinedButton(onClick = onDiscoverTapped, enabled = !isDiscovering, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_cell_tower), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.regions_discover_nearby))
        }
        TextButton(onClick = onAddManuallyTapped, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.regions_add_manually))
        }
    }
}

/**
 * Ported from `RegionManagementView`'s `.alert` with an inline `TextField` — see
 * `RegionNameValidator` for the rules the confirm button gates on. [onConfirm] returns a
 * validation message on failure (re-shown inline, alert stays open) or `null` on success, at
 * which point [onAdded] closes the dialog — split from [onConfirm] because
 * [RegionManagementViewModel.addRegion] dispatches the add itself rather than returning a value
 * the caller applies.
 */
@Composable
private fun AddRegionDialog(onDismiss: () -> Unit, onConfirm: (String) -> UiText?, onAdded: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<UiText?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nodeadmin_add_region)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorText = null },
                    placeholder = { Text(stringResource(R.string.nodeadmin_region_name)) },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = errorText?.let { { Text(it.asString()) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val error = onConfirm(name)
                if (error == null) {
                    onAdded()
                } else if (error != UiText.Plain("")) {
                    errorText = error
                }
            }) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
