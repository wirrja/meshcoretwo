// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.services.connection.ConnectionManager
import java.util.UUID

/**
 * One neighbor's SNR over time, as recorded in a repeater's neighbor snapshots. Ported from
 * `NeighborSNRChartView.swift`, pushed by tapping a row in [RepeaterStatusScreen]'s Neighbors
 * section as Swift's `NodeStatusRoute.neighborChart` is.
 *
 * Swift hands this view the same `fetchSnapshots` closure as the other history drill-downs, so it
 * reuses [NodeStatusHistoryViewModel] like [TelemetryHistoryScreen] does. The neighbor arrives as
 * the uppercase hex of its public-key prefix ([neighborPrefixHex]) so the route can carry it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeighborSnrChartScreen(
    connectionManager: ConnectionManager,
    sessionId: UUID,
    neighborPrefixHex: String,
    name: String,
    onBack: () -> Unit,
) {
    val viewModel: NodeStatusHistoryViewModel = viewModel(factory = NodeStatusHistoryViewModel.Factory(connectionManager, sessionId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chart = remember(uiState.snapshots, uiState.timeRange, neighborPrefixHex, name) {
        neighborSnrChart(uiState.timeRange.filter(uiState.snapshots), neighborPrefixHex, name)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
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
            item {
                if (uiState.isLoading) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    ChartCard { MetricChart(chart) }
                }
            }
        }
    }
}
