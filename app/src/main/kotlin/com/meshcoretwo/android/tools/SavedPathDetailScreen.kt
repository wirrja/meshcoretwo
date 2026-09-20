// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.persistence.TracePathDto
import com.meshcoretwo.services.persistence.TracePathRunDto
import java.time.ZoneId
import com.meshcoretwo.android.ui.i18n.DatePatterns
import java.time.format.DateTimeFormatter

private val runDateFormat: DateTimeFormatter get() = DatePatterns.dateTimeMedium()

/**
 * Full-screen saved-path detail: hop chips, a round-trip chart + summary stats, and per-run
 * history. Ported from `SavedPathDetailView.swift`. Only reachable today from [ComparisonRow]'s
 * "View N runs" link — [SavedPathsScreen] itself never navigates here, matching Swift, where
 * selecting a row there only loads the path into the builder.
 *
 * Swift's `Charts`-framework line chart becomes a Compose [Canvas] line-plot, the same
 * `MiniSparkline`-style port used elsewhere in this port instead of adding a chart dependency.
 * `SavedPathDetailView.swift`'s private `RunDetailView` becomes [RunDetailScreen], a full-screen
 * push instead of `navigationDestination(for:)` — the same overlay-by-nullable-state pattern
 * [TracePathResultsScreen] uses for [DistanceInfoScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPathDetailScreen(savedPath: TracePathDto, connectionManager: ConnectionManager, onDismiss: () -> Unit) {
    val viewModel: SavedPathDetailViewModel = viewModel(
        factory = SavedPathDetailViewModel.Factory(connectionManager, savedPath),
        key = savedPath.id.toString(),
    )
    val path by viewModel.savedPath.collectAsStateWithLifecycle()
    var selectedRun by remember { mutableStateOf<TracePathRunDto?>(null) }

    LaunchedEffect(path.id) { viewModel.refresh() }

    selectedRun?.let { run ->
        RunDetailScreen(run = run, onDismiss = { selectedRun = null })
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(path.name) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Text(
                    stringResource(R.string.contacts_path),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                PathChips(pathBytes = path.pathBytes, hashSize = path.hashSize)
            }
            item {
                Text(
                    stringResource(R.string.saved_performance),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (path.successfulRuns.size >= 2) {
                    RoundTripChart(runs = path.successfulRuns, modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp))
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatColumn(stringResource(R.string.saved_avg), path.averageRoundTripMs?.let { "$it ms" } ?: "-")
                    StatColumn(stringResource(R.string.saved_best), path.bestRoundTripMs?.let { "$it ms" } ?: "-")
                    StatColumn(stringResource(R.string.saved_success), "${path.successRate}%")
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.saved_history),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            itemsIndexed(path.sortedRuns) { _, run ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { selectedRun = run }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(runDateFormat.format(run.date), style = MaterialTheme.typography.bodyMedium)
                        if (run.success) {
                            Text("${run.roundTripMs} ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!run.success) {
                        val dangerColor = LocalMeshExtendedColors.current.danger
                        Text(
                            stringResource(R.string.saved_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = dangerColor,
                            modifier = Modifier
                                .background(dangerColor.copy(alpha = 0.15f), RoundedCornerShape(50))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Hop-hash chips, ported from `SavedPathDetailView.swift`'s private `PathChipsView`. */
@Composable
private fun PathChips(pathBytes: ByteArray, hashSize: Int) {
    val hexHops = remember(pathBytes, hashSize) {
        pathBytes.toList().chunked(hashSize).map { it.toByteArray().hexString.uppercase() }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hexHops.forEachIndexed { index, hex ->
            if (index > 0) Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(
                hex,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Round-trip-over-time line chart, ported from `SavedPathDetailView.swift`'s SwiftUI `Charts` block onto a Compose [Canvas], the same technique [MiniSparkline] uses. */
@Composable
private fun RoundTripChart(runs: List<TracePathRunDto>, modifier: Modifier = Modifier) {
    val strokeColor = MaterialTheme.colorScheme.primary
    val chronological = remember(runs) { runs.sortedBy { it.date } }
    Canvas(modifier = modifier) {
        val values = chronological.map { it.roundTripMs }
        val minVal = values.min()
        val maxVal = values.max()
        val range = (maxVal - minVal).toFloat()
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        var previous: Offset? = null
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = if (range > 0f) size.height - ((value - minVal) / range * size.height) else size.height / 2
            val point = Offset(x, y)
            previous?.let { drawLine(strokeColor, it, point, strokeWidth = 2.dp.toPx()) }
            drawCircle(strokeColor, radius = 3.dp.toPx(), center = point)
            previous = point
        }
    }
}

/** Per-hop SNR breakdown for one run. Ported from `SavedPathDetailView.swift`'s private `RunDetailView`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunDetailScreen(run: TracePathRunDto, onDismiss: () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.saved_run_details)) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    LabeledRow(stringResource(R.string.saved_date), runDateFormat.format(run.date))
                    LabeledRow(stringResource(R.string.trace_round_trip), "${run.roundTripMs} ms")
                    LabeledRow(stringResource(R.string.saved_status), if (run.success) stringResource(R.string.saved_success) else stringResource(R.string.saved_failed))
                }
            }
            if (run.success && run.hopsSNR.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.saved_per_hop_snr),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(run.hopsSNR) { index, snr -> LabeledRow("Hop ${index + 1}", "%.2f dB".format(snr)) }
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
