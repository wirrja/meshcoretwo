// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material3.HorizontalDivider
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
import com.meshcoretwo.services.connection.ConnectionManager
import java.util.UUID

/**
 * Status-history drill-down: the radio metrics a node's stored snapshots have accumulated, drawn as
 * [MetricChart]s over a selectable [HistoryTimeRange]. Ported from `NodeStatusHistoryView.swift`,
 * reached from the status section of [RoomStatusScreen]/[RepeaterStatusScreen] the same way Swift's
 * `NodeStatusSection` links to it. See [NodeStatusHistoryViewModel] for what's ported vs. deferred.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeStatusHistoryScreen(
    connectionManager: ConnectionManager,
    sessionId: UUID,
    onBack: () -> Unit,
) {
    val viewModel: NodeStatusHistoryViewModel = viewModel(factory = NodeStatusHistoryViewModel.Factory(connectionManager, sessionId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Rebuilt only when the inputs change — the whole snapshot history runs through the builder.
    val charts = remember(uiState.snapshots, uiState.timeRange, uiState.ocvValues) {
        radioMetricCharts(uiState.timeRange.filter(uiState.snapshots), uiState.ocvValues)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.history_title)) },
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

            if (uiState.isLoading) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            } else if (charts.isEmpty) {
                item {
                    Text(
                        stringResource(R.string.history_connect_once),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(charts.radio) { chart -> ChartCard { MetricChart(chart) } }

            if (charts.packets.isNotEmpty()) {
                item {
                    ChartCard {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.common_packets), style = MaterialTheme.typography.titleMedium)
                            charts.packets.forEach { chart -> MetricChart(chart) }
                        }
                    }
                }
            }

            items(charts.posts) { chart -> ChartCard { MetricChart(chart) } }

            if (!uiState.isLoading && !charts.isEmpty) {
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

/** One chart (or chart group), flat and divider-separated rather than boxed — matches every other
 * Phase 10/11 grouped list. */
@Composable
internal fun ChartCard(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        content()
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
