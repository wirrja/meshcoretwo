// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.ConnectingState
import com.meshcoretwo.android.ui.components.EmptyState
import com.meshcoretwo.android.ui.components.FilterChipRow
import com.meshcoretwo.android.ui.components.PresenceAvatar
import com.meshcoretwo.android.ui.components.SearchPillField
import com.meshcoretwo.android.ui.formatRelativeTimestamp
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.location.LocationProviderError
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.rf.GeoCoordinate
import com.meshcoretwo.services.rf.RFCalculator

/**
 * "Discover" list — nodes heard via advertisement that aren't contacts yet. Ported from
 * `DiscoveryView.swift`/`DiscoveryViewModel.swift`, pushed from the Contacts tab like every other
 * modal-turned-screen in this port (see [com.meshcoretwo.android.pathediting.AddHopPickerScreen]).
 *
 * Trimmed for this slice: filtering/sorting/search/segment/add/delete/clear-all/distance-sort all
 * ported; not yet ported are Swift's `@AppStorage`-persisted sort order (resets each time this
 * screen opens — no user-facing persistence layer for UI prefs exists in this port yet) and its
 * live `contactsVersion`/`servicesVersion` reload subscription ([DiscoveryViewModel]'s doc explains
 * why — same simplification [ContactsListViewModel] already made).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(connectionManager: ConnectionManager, locationProvider: LocationProvider, onBack: () -> Unit) {
    val viewModel: DiscoveryViewModel = viewModel(factory = DiscoveryViewModel.Factory(connectionManager))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addingNodeID by viewModel.addingNodeID.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var searchText by remember { mutableStateOf("") }
    var segment by remember { mutableStateOf(DiscoverSegment.ALL) }
    var sortOrder by remember { mutableStateOf(NodeSortOrder.LAST_HEARD) }
    var userLocation by remember { mutableStateOf<LocationFix?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var nodeToDelete by remember { mutableStateOf<DiscoveredNodeDto?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(sortOrder) {
        if ((sortOrder == NodeSortOrder.DISTANCE || sortOrder == NodeSortOrder.HOPS) && userLocation == null) {
            userLocation = try {
                locationProvider.requestCurrentLocation()
            } catch (error: LocationProviderError) {
                null
            }
        }
    }
    val context = LocalContext.current
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it.resolve(context))
            viewModel.clearError()
        }
    }

    nodeToDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { nodeToDelete = null },
            title = { Text(stringResource(R.string.discover_remove_title)) },
            text = { Text(stringResource(R.string.discover_remove_body, node.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteNode(node); nodeToDelete = null }) {
                    Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { nodeToDelete = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.discover_clear_title)) },
            text = { Text(stringResource(R.string.discover_clear_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAll(); showClearConfirmation = false }) {
                    Text(stringResource(R.string.common_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.contacts_discover)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) { Icon(painterResource(R.drawable.ic_swap_vert), contentDescription = stringResource(R.string.common_sort)) }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            NodeSortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(order.labelRes)) },
                                    leadingIcon = { if (order == sortOrder) Icon(painterResource(R.drawable.ic_check), contentDescription = null) },
                                    onClick = { sortOrder = order; showSortMenu = false },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.common_more)) }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.discover_clear_all)) },
                                onClick = { showMoreMenu = false; showClearConfirmation = true },
                                enabled = (state as? DiscoveryUiState.Ready)?.nodes?.isNotEmpty() == true,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val current = state) {
            is DiscoveryUiState.Connecting -> ConnectingState(modifier = Modifier.padding(padding))
            is DiscoveryUiState.Ready -> {
                Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                    SearchPillField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        placeholder = stringResource(R.string.common_search),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (searchText.isBlank()) {
                        FilterChipRow(
                            items = DiscoverSegment.entries,
                            selected = segment,
                            onSelect = { segment = it },
                            label = { stringResource(it.labelRes) },
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }

                    val visibleNodes = DiscoveryFiltering.visibleNodes(current.nodes, searchText, segment, sortOrder, userLocation)
                    if (visibleNodes.isEmpty()) {
                        EmptyDiscoveryContent(isSearching = searchText.isNotBlank(), modifier = Modifier.fillMaxSize())
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(visibleNodes, key = { it.id }) { node ->
                                Column(modifier = Modifier.animateItem()) {
                                    DiscoveryNodeRow(
                                        node = node,
                                        isAdded = current.addedPublicKeyHexes.contains(node.publicKey.hexString),
                                        isAdding = addingNodeID == node.id,
                                        userLocation = userLocation,
                                        onAdd = { viewModel.addNode(node) },
                                        onDelete = { nodeToDelete = node },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDiscoveryContent(isSearching: Boolean, modifier: Modifier = Modifier) {
    EmptyState(
        icon = if (isSearching) R.drawable.ic_search else R.drawable.ic_cell_tower,
        title = stringResource(if (isSearching) R.string.path_no_matches else R.string.discover_empty_title),
        description = if (isSearching) {
            stringResource(R.string.discover_no_match_desc)
        } else {
            stringResource(R.string.discover_empty_desc)
        },
        modifier = modifier,
    )
}

@Composable
private fun DiscoveryNodeRow(
    node: DiscoveredNodeDto,
    isAdded: Boolean,
    isAdding: Boolean,
    userLocation: LocationFix?,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember(node.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceAvatar(name = node.name, lastHeard = node.lastHeard, category = AvatarCategory.fromContactType(node.nodeType))
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(node.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                node.publicKey.hexString.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(routeLabel(node, userLocation), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatRelativeTimestamp(node.lastHeard), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isAdded) {
                Text(stringResource(R.string.common_added), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            } else {
                // Flat text button, not a filled pill: the pill was squeezed by the timestamp column and
                // broke long labels ("Добавить") onto two lines.
                TextButton(onClick = onAdd, enabled = !isAdding, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (isAdding) "…" else stringResource(R.string.common_add), maxLines = 1, softWrap = false)
                }
            }
        }
        Box {
            IconButton(onClick = { showMenu = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.common_more)) }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.common_remove)) }, onClick = { showMenu = false; onDelete() })
            }
        }
    }
}

/** Hop-count-plus-distance summary line, mirroring `DiscoveryNodeRow`'s label row on iOS in one shorter line. */
@Composable
private fun routeLabel(node: DiscoveredNodeDto, userLocation: LocationFix?): String {
    val hopPart = if (!node.isFloodRouted && node.pathHopCount == 0) {
        "Direct"
    } else {
        node.displayedHopCount?.let { "$it hop${if (it == 1) "" else "s"}" } ?: "Flood"
    }
    val distancePart = distanceLabel(node, userLocation)
    return if (distancePart != null) "$hopPart · $distancePart" else hopPart
}

/** `"1.2 km away"`-style label, or null when either side lacks a location. */
@Composable
private fun distanceLabel(node: DiscoveredNodeDto, userLocation: LocationFix?): String? {
    if (userLocation == null || !node.hasLocation) return null
    val meters = RFCalculator.distance(
        GeoCoordinate(userLocation.latitude, userLocation.longitude),
        GeoCoordinate(node.latitude, node.longitude),
    )
    return if (meters >= 1_000) stringResource(R.string.distance_km_away, "%.1f".format(meters / 1_000)) else stringResource(R.string.distance_m_away, meters.toInt())
}
