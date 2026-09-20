// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.ui.graphics.Color
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.CompactSearchTopBar
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.services.connection.ConnectionManager

/**
 * The Tools tab root — a port of `ToolsView.swift`'s list, PLAN.md's Phase 5 item 7. All the
 * diagnostic tools are wired up: RX Log, Node Discovery (`NodeDiscoveryView.swift`, see [NodeDiscoveryScreen]), Line of Sight (`LineOfSightView.swift`, see
 * [LineOfSightScreen]'s class doc for its own 9/10/11/12/13 sub-slice breakdown), and Trace Path
 * (`TracePathListView.swift`, built across slices 14-21 but only reachable here as of slice 22 —
 * [TracePathListScreen] itself still only covers the list view mode, not `TracePathView.swift`'s
 * list/map switcher). The list shape (rather than routing the tab straight to one screen) matches
 * iOS's `ToolSelection.allCases`.
 *
 * Phase 11 slice 1 replaced the collapsing `LargeTopAppBar` + icon+description [SectionCard]s
 * (added PLAN.md's Phase 6 slice 5) with a plain `TopAppBar` + [SettingsListRow] per tool — the
 * same "grouped, not boxed" container Phase 10 gave Settings, applied here since this screen is
 * structurally identical (a flat list of navigable rows, no form fields). The icons still
 * approximate `ToolSelection.systemImage` (`eye`, `waveform.badge.magnifyingglass`, `point.3
 * .connected.trianglepath.dotted`) with the closest Material Symbols equivalents (`ic_visibility`,
 * `ic_graphic_eq`, `ic_route`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    connectionManager: ConnectionManager,
    onOpenRxLog: () -> Unit,
    onOpenLineOfSight: () -> Unit,
    onOpenTracePath: () -> Unit,
    onOpenNodeDiscovery: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { CompactSearchTopBar(title = stringResource(R.string.tools_title), connectionManager = connectionManager) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp)) {
            ToolRow(
                icon = R.drawable.ic_graphic_eq,
                title = stringResource(R.string.rx_title),
                description = stringResource(R.string.tools_rxlog_desc),
                onClick = onOpenRxLog,
            )
            ToolRow(
                icon = R.drawable.ic_visibility,
                title = stringResource(R.string.tools_los),
                description = stringResource(R.string.tools_los_desc),
                onClick = onOpenLineOfSight,
            )
            ToolRow(
                icon = R.drawable.ic_route,
                title = stringResource(R.string.tools_trace),
                description = stringResource(R.string.tools_trace_desc),
                onClick = onOpenTracePath,
            )
            ToolRow(
                icon = R.drawable.ic_cell_tower,
                title = stringResource(R.string.tools_discovery),
                description = stringResource(R.string.tools_discovery_desc),
                onClick = onOpenNodeDiscovery,
            )
        }
    }
}

@Composable
private fun ToolRow(@DrawableRes icon: Int, title: String, description: String, onClick: () -> Unit) {
    SettingsListRow(title = title, value = description, singleLineValue = false, icon = icon, onClick = onClick)
}
