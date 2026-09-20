// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.EmptyState
import com.meshcoretwo.android.ui.components.FilterChipRow
import com.meshcoretwo.android.ui.components.InitialsAvatar
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.NODE_SCAN_DURATION_MS
import com.meshcoretwo.services.connection.NodeScanFilter

@get:StringRes
private val NodeScanFilter.labelRes: Int
    get() = when (this) {
        NodeScanFilter.REPEATERS -> R.string.map_filter_repeaters
        NodeScanFilter.SENSORS -> R.string.nd_sensors
    }

@get:StringRes
private val NodeScanFilter.lowercaseRes: Int
    get() = when (this) {
        NodeScanFilter.REPEATERS -> R.string.nd_repeaters_lc
        NodeScanFilter.SENSORS -> R.string.nd_sensors_lc
    }

@get:StringRes
private val NodeScanFilter.singularRes: Int
    get() = when (this) {
        NodeScanFilter.REPEATERS -> R.string.map_type_repeater
        NodeScanFilter.SENSORS -> R.string.nd_sensor_singular
    }

/**
 * Tools → Node Discovery: actively asks the radio which repeaters (or sensors) it can hear
 * directly — zero hops — and shows each one's signal quality. Ported from `NodeDiscoveryView.swift`
 * and `NodeDiscoveryRowView.swift`, minus haptics and liquid-glass chrome. See
 * [NodeDiscoveryViewModel] for the behavior behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDiscoveryScreen(connectionManager: ConnectionManager, onBack: () -> Unit) {
    val viewModel: NodeDiscoveryViewModel = viewModel(factory = NodeDiscoveryViewModel.Factory(connectionManager))
    val results by viewModel.results.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val addedKeys by viewModel.addedKeys.collectAsStateWithLifecycle()
    val addingKey by viewModel.addingKey.collectAsStateWithLifecycle()

    var showSortMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it.resolve(context))
            viewModel.dismissError()
        }
    }

    val visible = results.filter { it.filter == filter }.let { rows ->
        when (sortOrder) {
            NodeDiscoverySortOrder.SIGNAL -> rows.sortedByDescending { it.snr }
            NodeDiscoverySortOrder.NAME -> rows.sortedBy { it.name.lowercase() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_discovery)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) { Icon(painterResource(R.drawable.ic_swap_vert), contentDescription = stringResource(R.string.common_sort)) }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            NodeDiscoverySortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(order.labelRes)) },
                                    leadingIcon = { if (order == sortOrder) Icon(painterResource(R.drawable.ic_check), contentDescription = null) },
                                    onClick = { viewModel.setSortOrder(order); showSortMenu = false },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (isConnected) {
                ScanBar(filter = filter, isScanning = isScanning, onScan = viewModel::scan, onStop = viewModel::stopScan)
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isConnected) {
                EmptyState(
                    icon = R.drawable.ic_signal_cellular_off,
                    title = stringResource(R.string.nd_not_connected),
                    description = stringResource(R.string.nd_connect_to_scan, stringResource(filter.lowercaseRes)),
                )
            } else {
                FilterChipRow(
                    items = NodeScanFilter.entries,
                    selected = filter,
                    onSelect = { if (!isScanning) viewModel.setFilter(it) },
                    label = { stringResource(it.labelRes) },
                )
                when {
                    visible.isNotEmpty() -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(visible, key = { it.filter.name + it.publicKeyHex }) { result ->
                            NodeResultRow(
                                result = result,
                                isAdded = result.publicKeyHex in addedKeys,
                                isAdding = addingKey == result.publicKeyHex,
                                onAdd = { viewModel.addNode(result) },
                            )
                        }
                    }
                    isScanning -> EmptyState(
                        icon = R.drawable.ic_cell_tower,
                        title = stringResource(R.string.nd_listening),
                        description = stringResource(R.string.nd_waiting, (NODE_SCAN_DURATION_MS / 1000).toInt(), stringResource(filter.lowercaseRes)),
                    )
                    else -> EmptyState(
                        icon = R.drawable.ic_search,
                        title = stringResource(R.string.nd_scan_title, stringResource(filter.lowercaseRes)),
                        description = stringResource(R.string.nd_scan_desc, stringResource(filter.lowercaseRes)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanBar(filter: NodeScanFilter, isScanning: Boolean, onScan: () -> Unit, onStop: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        if (isScanning) {
            OutlinedButton(onClick = onStop) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.common_stop))
            }
        } else {
            Button(onClick = onScan) { Text(stringResource(R.string.nd_scan_title, stringResource(filter.lowercaseRes))) }
        }
    }
}

@Composable
private fun NodeResultRow(result: NodeDiscoveryResult, isAdded: Boolean, isAdding: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (result.filter == NodeScanFilter.REPEATERS) {
            InitialsAvatar(name = result.name, size = 40.dp, category = AvatarCategory.REPEATER)
        } else {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_sensors), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(result.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                result.publicKeyHex.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${stringResource(result.filter.singularRes)} · SNR ↓ %.1f ↑ %.1f dB · RSSI ${result.rssi} dBm".format(result.snr, result.snrIn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (result.canAdd) {
            Spacer(modifier = Modifier.width(8.dp))
            if (isAdded) {
                Text(stringResource(R.string.common_added), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            } else {
                TextButton(onClick = onAdd, enabled = !isAdding, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.common_add), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
