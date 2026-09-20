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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.remotenode.validCoordinate
import java.util.UUID

/**
 * Sensor-telemetry history for one remote-node session: each stored telemetry channel's readings as
 * [MetricChart]s over a selectable [HistoryTimeRange]. Ported from `TelemetryHistoryView.swift`,
 * reached from the telemetry section of [RoomStatusScreen]/[RepeaterStatusScreen] the same way
 * Swift's `NodeTelemetrySection` links to it.
 *
 * Swift's view takes the same `fetchSnapshots` closure and `ocvArray` that `NodeStatusHistoryView`
 * does, so this reuses [NodeStatusHistoryViewModel] (session, snapshots, battery curve, time range)
 * rather than a second view model loading the same things.
 *
 * Swift shows nothing below the range picker when no telemetry was captured; this shows the
 * overview's "captured when you view the section" note instead of a blank screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryHistoryScreen(
    connectionManager: ConnectionManager,
    sessionId: UUID,
    onOpenLocationMap: (initialSelectionId: UUID?) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: NodeStatusHistoryViewModel = viewModel(factory = NodeStatusHistoryViewModel.Factory(connectionManager, sessionId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filtered = remember(uiState.snapshots, uiState.timeRange) { uiState.timeRange.filter(uiState.snapshots) }
    val groups = remember(filtered, uiState.ocvValues) {
        telemetryChannelGroups(filtered, uiState.ocvValues)
    }
    val locationPreview = remember(filtered) { LocationPathMapBuilder.preview(filtered, showsFullPath = false) }
    val locationReports = remember(filtered) { filtered.filter { it.validCoordinate != null }.asReversed() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.telemetry_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HistoryTimeRangePicker(selection = uiState.timeRange, onSelect = viewModel::selectTimeRange) }

            when {
                uiState.isLoading -> item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                groups.isEmpty() -> item {
                    Text(
                        stringResource(R.string.telemetry_not_captured, stringResource(R.string.telemetry_title)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                groups.size > 1 -> items(groups, key = { it.channel }) { group ->
                    ChartCard { TelemetryChannelSection(group, showHeader = true) }
                }
                else -> items(groups.single().charts) { chart -> ChartCard { MetricChart(chart) } }
            }

            locationHistorySection(locationPreview, locationReports, onOpenMap = onOpenLocationMap)
        }
    }
}

/**
 * One channel's sensor charts, under a "Channel N" header when the node reports more than one
 * channel. Ported from the per-channel `Section` both `TelemetryHistoryView` and
 * `TelemetryHistoryOverviewView` build.
 */
@Composable
internal fun TelemetryChannelSection(group: TelemetryChannelGroup, showHeader: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showHeader) {
            Text(stringResource(R.string.telemetry_channel, group.channel), style = MaterialTheme.typography.titleMedium)
        }
        group.charts.forEach { chart -> MetricChart(chart) }
    }
}
