// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.DetailRow
import com.meshcoretwo.android.ui.components.ExpandableSectionCard
import com.meshcoretwo.android.ui.components.FavoriteToggleButton
import com.meshcoretwo.android.ui.components.InitialsAvatar
import com.meshcoretwo.android.ui.components.SettingsGroupLabel
import com.meshcoretwo.android.ui.i18n.label
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.persistence.NodeLocationFix
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RoomPermissionLevel
import com.meshcoretwo.services.remotenode.OCVPreset
import java.util.UUID

/**
 * Room server status/telemetry — a trimmed port of `RoomStatusView`/`RoomStatusContent.swift`.
 * See [RoomStatusViewModel]'s class doc for what's ported vs. deferred (status deltas, both history
 * drill-downs and the telemetry "View on Map" link are now wired).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomStatusScreen(
    connectionManager: ConnectionManager,
    sessionId: UUID,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTelemetryHistory: () -> Unit,
    onViewLocationOnMap: (fix: NodeLocationFix, name: String?) -> Unit,
    onOpenCLI: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: RoomStatusViewModel = viewModel(factory = RoomStatusViewModel.Factory(connectionManager, sessionId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.status_room_title)) },
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
            item { RoomStatusHeader(session = uiState.session) }

            uiState.session?.let { session ->
                item {
                    RoomPreferencesSection(
                        session = session,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onSetNotificationLevel = viewModel::setNotificationLevel,
                    )
                }
            }

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
        }
    }
}

@Composable
private fun RoomStatusHeader(session: RemoteNodeSessionDto?) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        InitialsAvatar(name = session?.name ?: "", size = 60.dp, category = AvatarCategory.ROOM)
        Spacer(modifier = Modifier.size(8.dp))
        Text(session?.name ?: "", style = MaterialTheme.typography.headlineSmall)
        if (session?.permissionLevel == RoomPermissionLevel.GUEST) {
            Text(stringResource(R.string.status_guest_mode), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Favorite toggle + notification-level picker for the room's chat-list row — the session-level
 * counterpart of `ContactDetailScreen`/`ChannelInfoScreen`'s own favorite/notification sections.
 * [NotificationLevel.ROOM_LEVELS] (muted/all only — rooms have no mention-tracking infrastructure)
 * gates the chip choices, unlike [ChannelInfoScreen]'s full [NotificationLevel.entries].
 */
@Composable
private fun RoomPreferencesSection(
    session: RemoteNodeSessionDto,
    onToggleFavorite: () -> Unit,
    onSetNotificationLevel: (NotificationLevel) -> Unit,
) {
    Column {
        FavoriteToggleButton(isFavorite = session.isFavorite, onToggle = onToggleFavorite)
        Spacer(modifier = Modifier.size(12.dp))
        SettingsGroupLabel(stringResource(R.string.common_notifications))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotificationLevel.ROOM_LEVELS.forEach { level ->
                FilterChip(
                    selected = session.notificationLevel == level,
                    onClick = { onSetNotificationLevel(level) },
                    label = { Text(level.label()) },
                )
            }
        }
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
        Spacer(modifier = Modifier.size(4.dp))
        DetailRow(stringResource(R.string.stat_posts_received), RoomStatusViewModel.postsReceivedDisplay(status))
        DetailRow(stringResource(R.string.stat_posts_pushed), RoomStatusViewModel.postsPushedDisplay(status))
        NodeStatusDeltas.previousSnapshotTimestamp(previousStatusSnapshot)?.let { baseline ->
            Text(baseline.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
