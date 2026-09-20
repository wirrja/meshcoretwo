// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.DetailRow
import com.meshcoretwo.android.ui.components.ExpandableSectionCard
import com.meshcoretwo.android.ui.components.InitialsAvatar
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.android.ui.theme.snrQualityColor
import com.meshcoretwo.android.ui.theme.snrQualityGlyph
import com.meshcoretwo.protocol.Neighbour
import com.meshcoretwo.protocol.OwnerInfoResponse
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.persistence.NeighborSnapshotEntry
import com.meshcoretwo.services.persistence.NodeLocationFix
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.remotenode.OCVPreset
import com.meshcoretwo.services.rendering.NodeNameResolution
import com.meshcoretwo.services.rendering.SNRQuality
import java.util.UUID

/**
 * Repeater status/telemetry/neighbors — a trimmed port of `RepeaterStatusView`/
 * `RepeaterStatusContent.swift`. See [RepeaterStatusViewModel]'s class doc for what's ported vs.
 * deferred (name resolution, the neighbor SNR map, the battery curve, the route-path section, the
 * status/neighbor deltas, both history drill-downs, the telemetry "View on Map" link and the
 * per-neighbor SNR chart are now wired).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeaterStatusScreen(
    connectionManager: ConnectionManager,
    sessionId: UUID,
    onBack: () -> Unit,
    onViewNeighborsOnMap: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTelemetryHistory: () -> Unit,
    onViewLocationOnMap: (fix: NodeLocationFix, name: String?) -> Unit,
    onOpenNeighborChart: (name: String, prefix: ByteArray) -> Unit,
    onOpenCLI: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: RepeaterStatusViewModel = viewModel(factory = RepeaterStatusViewModel.Factory(connectionManager, sessionId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.status_repeater_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    NodeAdminTopBarActions(isAdmin = uiState.session?.isAdmin == true, onOpenSettings = onOpenSettings, onOpenCLI = onOpenCLI)
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RepeaterStatusHeader(session = uiState.session) }

            item {
                ExpandableSectionCard(
                    title = stringResource(R.string.status_section),
                    expanded = uiState.statusExpanded,
                    isLoading = uiState.isLoadingStatus,
                    onToggle = viewModel::toggleStatusExpanded,
                    onReload = viewModel::reloadStatus,
                ) {
                    val status = uiState.status
                    when {
                        uiState.isLoadingStatus && status == null -> SectionLoadingRow()
                        uiState.statusError != null && status == null -> ErrorText(uiState.statusError!!.asString())
                        status != null -> {
                            StatusRows(status, uiState.previousStatusSnapshot)
                            TextButton(onClick = onOpenHistory) { Text(stringResource(R.string.history_title)) }
                        }
                    }
                }
            }

            item {
                ExpandableSectionCard(
                    title = stringResource(R.string.telemetry_title),
                    expanded = uiState.telemetryExpanded,
                    isLoading = uiState.isLoadingTelemetry,
                    onToggle = viewModel::toggleTelemetryExpanded,
                    onReload = viewModel::reloadTelemetry,
                ) {
                    when {
                        uiState.isLoadingTelemetry && uiState.telemetry == null -> SectionLoadingRow()
                        uiState.telemetryError != null && uiState.telemetry == null -> ErrorText(uiState.telemetryError!!.asString())
                        uiState.telemetry != null -> {
                            if (uiState.cachedDataPoints.isEmpty()) {
                                Text(stringResource(R.string.status_no_sensor_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                TelemetryRows(uiState.cachedDataPoints, uiState.ocvValues)
                            }
                            uiState.currentLocationFix?.let { fix ->
                                TextButton(onClick = { onViewLocationOnMap(fix, uiState.session?.name) }) { Text(stringResource(R.string.status_view_on_map)) }
                            }
                            TextButton(onClick = onOpenTelemetryHistory) { Text(stringResource(R.string.history_title)) }
                        }
                    }
                }
            }

            item {
                ExpandableSectionCard(
                    title = stringResource(R.string.telemetry_neighbors),
                    expanded = uiState.neighborsExpanded,
                    isLoading = uiState.isLoadingNeighbors && !uiState.isDiscovering,
                    onToggle = viewModel::toggleNeighborsExpanded,
                    onReload = viewModel::reloadNeighbors,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            uiState.isLoadingNeighbors && !uiState.isDiscovering && !uiState.neighborsLoaded -> SectionLoadingRow()
                            uiState.neighborsError != null && !uiState.isDiscovering && !uiState.neighborsLoaded ->
                                ErrorText(uiState.neighborsError!!.asString())
                            uiState.neighborsLoaded && uiState.neighbors.isEmpty() && !uiState.isDiscovering ->
                                Text(stringResource(R.string.status_no_neighbors), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            uiState.neighbors.isNotEmpty() -> {
                                TextButton(onClick = onViewNeighborsOnMap) {
                                    Icon(painterResource(R.drawable.ic_map), contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(stringResource(R.string.status_view_on_map))
                                }
                                NeighborRows(
                                    neighbors = uiState.neighbors,
                                    resolveName = viewModel::resolveNeighborName,
                                    previousNeighborSnapshot = uiState.previousNeighborSnapshot,
                                    seenNeighborPrefixes = uiState.seenNeighborPrefixes,
                                    onOpenChart = onOpenNeighborChart,
                                )
                            }
                        }
                        val disappeared = RepeaterStatusViewModel.disappearedNeighbors(uiState.neighbors, uiState.previousNeighborSnapshot)
                        if (disappeared.isNotEmpty()) {
                            DisappearedNeighborRows(disappeared, viewModel::resolveNeighborName)
                        }
                        if (uiState.session?.isAdmin == true) {
                            DiscoverNeighborsButton(
                                isDiscovering = uiState.isDiscovering,
                                secondsRemaining = uiState.discoverySecondsRemaining,
                                onToggle = viewModel::toggleDiscovery,
                            )
                        }
                    }
                }
            }

            item {
                ExpandableSectionCard(
                    title = stringResource(R.string.status_owner_info),
                    expanded = uiState.ownerInfoExpanded,
                    isLoading = uiState.isLoadingOwnerInfo,
                    onToggle = viewModel::toggleOwnerInfoExpanded,
                    onReload = viewModel::reloadOwnerInfo,
                ) {
                    when {
                        uiState.isLoadingOwnerInfo && uiState.ownerInfo == null -> SectionLoadingRow()
                        uiState.ownerInfoError != null && uiState.ownerInfo == null -> ErrorText(uiState.ownerInfoError!!.asString())
                        uiState.ownerInfo != null -> OwnerInfoRows(uiState.ownerInfo!!)
                    }
                }
            }

            item {
                ExpandableSectionCard(
                    title = stringResource(R.string.status_battery_curve),
                    expanded = uiState.isBatteryCurveExpanded,
                    isLoading = false,
                    onToggle = viewModel::toggleBatteryCurveExpanded,
                ) {
                    BatteryCurveSectionContent(
                        availablePresets = OCVPreset.nodePresets,
                        selectedPreset = uiState.selectedOCVPreset,
                        voltageValues = uiState.ocvValues,
                        onSelectPreset = { preset -> viewModel.saveOCVSettings(preset, preset.ocvArray) },
                        onCommitCustomValues = { values -> viewModel.saveOCVSettings(OCVPreset.CUSTOM, values) },
                        error = uiState.ocvError,
                    )
                }
            }

            uiState.routePathContact?.let { routePathContact ->
                item {
                    NodeRoutePathSection(
                        contact = routePathContact,
                        contacts = uiState.contacts,
                        discoveredNodes = uiState.discoveredNodes,
                        userLocation = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun RepeaterStatusHeader(session: RemoteNodeSessionDto?) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        InitialsAvatar(name = session?.name ?: "", size = 60.dp, category = AvatarCategory.REPEATER)
        Spacer(modifier = Modifier.size(8.dp))
        Text(session?.name ?: "", style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun StatusRows(status: StatusResponse, previousStatusSnapshot: NodeStatusSnapshotDto?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NodeMetricRow(
            label = stringResource(R.string.status_battery),
            value = NodeStatusDisplay.batteryDisplay(status),
            delta = NodeStatusDeltas.batteryDeltaMillivolts(status, previousStatusSnapshot)?.let { it / 1000.0 },
            higherIsBetter = true,
            unit = " V",
            fractionDigits = 3,
        )
        DetailRow(stringResource(R.string.stat_uptime), NodeStatusDisplay.uptimeDisplay(status))
        DetailRow("Airtime", NodeStatusDisplay.airtimeDisplay(status))
        DetailRow("Airtime %", NodeStatusDisplay.airtimePercentDisplay(status))
        NodeMetricRow(
            label = "Last RSSI",
            value = NodeStatusDisplay.lastRSSIDisplay(status),
            delta = NodeStatusDeltas.rssiDelta(status, previousStatusSnapshot)?.toDouble(),
            higherIsBetter = true,
            unit = " dBm",
            fractionDigits = 0,
        )
        NodeMetricRow(
            label = "Last SNR",
            value = NodeStatusDisplay.lastSNRDisplay(status),
            delta = NodeStatusDeltas.snrDelta(status, previousStatusSnapshot),
            higherIsBetter = true,
            unit = " dB",
            fractionDigits = 1,
        )
        NodeMetricRow(
            label = "Noise Floor",
            value = NodeStatusDisplay.noiseFloorDisplay(status),
            delta = NodeStatusDeltas.noiseFloorDelta(status, previousStatusSnapshot)?.toDouble(),
            higherIsBetter = false,
            unit = " dBm",
            fractionDigits = 0,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(stringResource(R.string.common_packets), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DetailRow(stringResource(R.string.stat_sent), status.packetsSent.toString())
        DetailRow(stringResource(R.string.stat_received), status.packetsReceived.toString())
        DetailRow(stringResource(R.string.stat_sent_direct), status.sentDirect.toString())
        DetailRow(stringResource(R.string.stat_sent_flood), status.sentFlood.toString())
        DetailRow(stringResource(R.string.stat_received_direct), status.receivedDirect.toString())
        DetailRow(stringResource(R.string.stat_received_flood), status.receivedFlood.toString())
        DetailRow(stringResource(R.string.stat_duplicates), NodeStatusDisplay.duplicatesDisplay(status))
        RepeaterStatusViewModel.receiveErrorsDisplay(status)?.let { DetailRow(stringResource(R.string.stat_receive_errors), it) }
        NodeStatusDeltas.previousSnapshotTimestamp(previousStatusSnapshot)?.let { baseline ->
            Text(baseline.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NeighborRows(
    neighbors: List<Neighbour>,
    resolveName: (ByteArray) -> NodeNameResolution?,
    previousNeighborSnapshot: NodeStatusSnapshotDto?,
    seenNeighborPrefixes: Set<String>,
    onOpenChart: (name: String, prefix: ByteArray) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        neighbors.forEach { neighbor ->
            val resolution = resolveName(neighbor.publicKeyPrefix)
            val unknownName = stringResource(R.string.common_unknown)
            val isNew = RepeaterStatusViewModel.isNewNeighbor(neighbor.publicKeyPrefix, previousNeighborSnapshot, seenNeighborPrefixes)
            // Swift titles the chart "Unknown" (not the hex prefix the row shows) when the name doesn't resolve.
            Column(modifier = Modifier.fillMaxWidth().clickable { onOpenChart(resolution?.displayName ?: unknownName, neighbor.publicKeyPrefix) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        resolution?.displayName ?: RepeaterStatusViewModel.neighborKeyDisplay(neighbor.publicKeyPrefix),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = if (resolution?.displayName == null) FontFamily.Monospace else null,
                    )
                    if (isNew) {
                        Text(
                            " " + stringResource(R.string.status_new),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (resolution?.isFallback == true) {
                        Text(
                            " (?)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        RepeaterStatusViewModel.lastSeenDisplay(neighbor.secondsAgo),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val quality = SNRQuality.of(neighbor.snr)
                        Text(snrQualityGlyph(quality), style = MaterialTheme.typography.bodySmall, color = snrQualityColor(quality))
                        Text(
                            RepeaterStatusViewModel.neighborSNRDisplay(neighbor.snr),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RepeaterStatusViewModel.neighborSnrDelta(neighbor, previousNeighborSnapshot)?.let { delta ->
                            StatusDeltaLabel(delta = delta, higherIsBetter = true, unit = " dB", fractionDigits = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Neighbors from the baseline reading that dropped out of the current list. Muted/tertiary styling, no reload/expand affordance. */
@Composable
private fun DisappearedNeighborRows(disappeared: List<NeighborSnapshotEntry>, resolveName: (ByteArray) -> NodeNameResolution?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        disappeared.forEach { neighbor ->
            val resolution = resolveName(neighbor.publicKeyPrefix)
            val unknownName = stringResource(R.string.common_unknown)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            resolution?.displayName ?: RepeaterStatusViewModel.neighborKeyDisplay(neighbor.publicKeyPrefix),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = if (resolution?.displayName == null) FontFamily.Monospace else null,
                        )
                        if (resolution?.isFallback == true) {
                            Text(
                                " (?)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(stringResource(R.string.status_not_seen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    RepeaterStatusViewModel.neighborSNRDisplay(neighbor.snr),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiscoverNeighborsButton(
    isDiscovering: Boolean,
    secondsRemaining: Int,
    onToggle: () -> Unit,
) {
    TextButton(onClick = onToggle) {
        if (isDiscovering) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.status_discovering, secondsRemaining))
        } else {
            Text(stringResource(R.string.status_discover_neighbors))
        }
    }
}

@Composable
private fun OwnerInfoRows(ownerInfo: OwnerInfoResponse) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(ownerInfo.ownerInfo.ifEmpty { stringResource(R.string.status_no_owner_info) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ownerInfo.firmwareVersion.isNotEmpty()) {
            DetailRow("Firmware", ownerInfo.firmwareVersion)
        }
    }
}

