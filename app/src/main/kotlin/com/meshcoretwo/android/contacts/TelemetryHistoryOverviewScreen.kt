// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.ExpandableSectionCard
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.remotenode.validCoordinate
import java.util.UUID

/**
 * Every stored history chart for one contact — radio metrics, sensor telemetry by channel and
 * per-neighbor SNR — in collapsible sections over a selectable [HistoryTimeRange]. Ported from
 * `TelemetryHistoryOverviewView.swift`, reached from [ContactDetailScreen]'s "Telemetry History"
 * action as Swift's `ContactRoute.TelemetryHistory` is. See [TelemetryHistoryOverviewViewModel] for
 * loading and the offline caveat.
 *
 * Not ported: Swift's `ContentUnavailableView` icon on the empty state (the port has no icons yet,
 * PLAN.md Phase 6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryHistoryOverviewScreen(
    connectionManager: ConnectionManager,
    contactId: UUID,
    onOpenLocationMap: (initialSelectionId: UUID?) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: TelemetryHistoryOverviewViewModel = viewModel(factory = TelemetryHistoryOverviewViewModel.Factory(connectionManager, contactId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Each builder walks the whole filtered history, so rebuild only when its inputs change.
    val filtered = remember(uiState.snapshots, uiState.timeRange) { uiState.timeRange.filter(uiState.snapshots) }
    val radioCharts = remember(filtered, uiState.ocvValues) { radioMetricCharts(filtered, uiState.ocvValues) }
    val channelGroups = remember(filtered, uiState.ocvValues) { telemetryChannelGroups(filtered, uiState.ocvValues) }
    val neighborCharts = remember(filtered, uiState.contacts, uiState.discoveredNodes) {
        neighborSnrCharts(filtered, viewModel::resolveNeighborName)
    }
    val locationPreview = remember(filtered) { LocationPathMapBuilder.preview(filtered, showsFullPath = true) }
    val locationReports = remember(filtered) { filtered.filter { it.validCoordinate != null }.asReversed() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_telemetry_history)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                uiState.isLoading -> item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                !uiState.hasSnapshots -> item { OverviewEmptyState() }
                else -> {
                    item { HistoryTimeRangePicker(selection = uiState.timeRange, onSelect = viewModel::selectTimeRange) }

                    if (TelemetryHistoryOverviewViewModel.hasRadioData(filtered)) {
                        item {
                            ExpandableSectionCard(
                                title = stringResource(R.string.telemetry_radio),
                                expanded = uiState.radioExpanded,
                                isLoading = false,
                                onToggle = viewModel::toggleRadioExpanded,
                            ) { RadioChartsContent(radioCharts) }
                        }
                    }

                    item {
                        if (TelemetryHistoryOverviewViewModel.hasTelemetryData(filtered)) {
                            ExpandableSectionCard(
                                title = stringResource(R.string.telemetry_sensors),
                                expanded = uiState.sensorsExpanded,
                                isLoading = false,
                                onToggle = viewModel::toggleSensorsExpanded,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    channelGroups.forEach { group -> TelemetryChannelSection(group, showHeader = channelGroups.size > 1) }
                                }
                            }
                        } else {
                            SectionNotCapturedCard(stringResource(R.string.telemetry_sensors))
                        }
                    }

                    locationHistorySection(locationPreview, locationReports, onOpenMap = onOpenLocationMap)

                    if (uiState.showNeighbors) {
                        item {
                            if (TelemetryHistoryOverviewViewModel.hasNeighborData(filtered)) {
                                ExpandableSectionCard(
                                    title = stringResource(R.string.telemetry_neighbors),
                                    expanded = uiState.neighborsExpanded,
                                    isLoading = false,
                                    onToggle = viewModel::toggleNeighborsExpanded,
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        neighborCharts.forEach { chart -> MetricChart(chart) }
                                    }
                                }
                            } else {
                                SectionNotCapturedCard(stringResource(R.string.telemetry_neighbors))
                            }
                        }
                    }

                    item {
                        Text(
                            stringResource(R.string.history_retention),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The radio charts in [NodeStatusHistoryScreen]'s order, stacked inside one section instead of a card each. */
@Composable
private fun RadioChartsContent(charts: RadioMetricCharts) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        charts.radio.forEach { chart -> MetricChart(chart) }
        if (charts.packets.isNotEmpty()) {
            Text(stringResource(R.string.common_packets), style = MaterialTheme.typography.titleMedium)
            charts.packets.forEach { chart -> MetricChart(chart) }
        }
        charts.posts.forEach { chart -> MetricChart(chart) }
    }
}

@Composable
private fun SectionNotCapturedCard(section: String) {
    Text(
        stringResource(R.string.telemetry_not_captured, section),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OverviewEmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_show_chart),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.contacts_telemetry_history), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.history_connect_once), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
