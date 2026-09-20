// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.DetailRow
import com.meshcoretwo.android.ui.components.EmptyState
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.android.ui.theme.snrQualityColor
import com.meshcoretwo.android.ui.theme.snrQualityGlyph
import com.meshcoretwo.protocol.PayloadType
import com.meshcoretwo.protocol.RegionMatchResult
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.persistence.DecryptStatus
import com.meshcoretwo.services.persistence.RxLogDto
import com.meshcoretwo.services.rxlog.RegionScopeSemantics
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ENTRY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * The RX Log tool screen — a port of `RxLogView.swift`, trimmed of its liquid-glass chrome and
 * pulse animation (plain Material3 equivalents, same simplification every other Tools/Settings
 * screen in this port already made). See [RxLogViewModel]'s doc for what's behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RxLogScreen(connectionManager: ConnectionManager, onBack: () -> Unit) {
    val viewModel: RxLogViewModel = viewModel(factory = RxLogViewModel.Factory(connectionManager))
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val groupCounts by viewModel.groupCounts.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val nodeNames by viewModel.nodeNames.collectAsStateWithLifecycle()
    val routeFilter by viewModel.routeFilter.collectAsStateWithLifecycle()
    val decryptFilter by viewModel.decryptFilter.collectAsStateWithLifecycle()

    var groupDuplicates by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val localPrefix = connectionManager.connectedDeviceRecord?.publicKeyPrefix
    val filtered = entries.filterEntries(routeFilter, decryptFilter).let { if (groupDuplicates) it.deduplicatedByHash() else it }

    // Newest packet first: a key-anchored LazyColumn keeps the previously-first row in view, so a live
    // insert would push itself off the top. Follow the head while the reader is parked at (or one row
    // below) it; leave the position alone once they've scrolled down to read older entries.
    val listState = rememberLazyListState()
    val newestId = filtered.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rx_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    TextButton(onClick = { showClearConfirm = true }, enabled = entries.isNotEmpty()) { Text(stringResource(R.string.common_clear)) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            StatusHeader(isConnected = isConnected, count = entries.size)
            FilterRow(
                label = stringResource(R.string.rx_route),
                options = RxLogRouteFilter.entries,
                selected = routeFilter,
                labelOf = { stringResource(it.labelRes) },
                onSelect = viewModel::setRouteFilter,
            )
            FilterRow(
                label = stringResource(R.string.rx_decrypt),
                options = RxLogDecryptFilter.entries,
                selected = decryptFilter,
                labelOf = { stringResource(it.labelRes) },
                onSelect = viewModel::setDecryptFilter,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.rx_group_dupes), style = MaterialTheme.typography.bodySmall)
                Switch(checked = groupDuplicates, onCheckedChange = { groupDuplicates = it })
            }
            HorizontalDivider()

            if (!isConnected) {
                EmptyState(
                    icon = R.drawable.ic_signal_cellular_off,
                    title = stringResource(R.string.nd_not_connected),
                    description = stringResource(R.string.rx_connect_to_see),
                )
            } else if (filtered.isEmpty()) {
                EmptyState(
                    icon = R.drawable.ic_cell_tower,
                    title = stringResource(R.string.nd_listening),
                    description = stringResource(R.string.rx_every_packet),
                )
            } else {
                LazyColumn(state = listState) {
                    items(filtered, key = { it.id }) { entry ->
                        Column(modifier = Modifier.animateItem()) {
                            RxLogRow(
                                entry = entry,
                                groupCount = if (groupDuplicates) groupCounts[entry.packetHash] ?: 1 else 1,
                                localPublicKeyPrefix = localPrefix,
                                nodeNames = nodeNames,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.rx_delete_all)) },
            text = { Text(stringResource(R.string.rx_cannot_undo)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearLog(); showClearConfirm = false }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun StatusHeader(isConnected: Boolean, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (isConnected) R.string.rx_live else R.string.rx_offline),
            color = if (isConnected) LocalMeshExtendedColors.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(stringResource(R.string.rx_packets, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> FilterRow(label: String, options: List<T>, selected: T, labelOf: @Composable (T) -> String, onSelect: (T) -> Unit) {
    // Label on its own line, chips in one horizontally scrollable strip: every group starts at the same
    // left edge whatever the translation length (a FlowRow wrapped chips under the label at random).
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(labelOf(option), maxLines = 1) })
            }
        }
    }
}

@Composable
private fun RxLogRow(entry: RxLogDto, groupCount: Int, localPublicKeyPrefix: ByteArray?, nodeNames: Map<String, String>) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val extended = LocalMeshExtendedColors.current

    Column(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Line 1: route type, time, SNR
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.routeTypeSimple,
                style = MaterialTheme.typography.labelMedium,
                color = if (entry.isFlood) extended.success else extended.info,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(ENTRY_TIME_FORMAT.format(entry.receivedAt), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.weight(1f))
            if (entry.snr != null) {
                Text(snrQualityGlyph(entry.snrQuality), style = MaterialTheme.typography.bodySmall, color = snrQualityColor(entry.snrQuality))
            }
        }

        // Line 2: path + from/to for direct text messages
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                entry.pathDisplayString(localPublicKeyPrefix),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.isDirectTextMessage && entry.senderPrefix != null && entry.recipientPrefix != null) {
                Text(
                    " · ${resolveHashLabel(entry.senderPrefix!!, nodeNames, localPublicKeyPrefix)} → " +
                        resolveHashLabel(entry.recipientPrefix!!, nodeNames, localPublicKeyPrefix),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Line 3: message preview or payload info, SNR, dup count
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val preview = entry.decodedText?.let { "\"$it\"" } ?: run {
                val versionSuffix = if (entry.payloadVersion.toInt() > 0) " v${entry.payloadVersion}" else ""
                "${entry.payloadType.displayName}$versionSuffix · ${entry.rawPayload.size} bytes"
            }
            Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            entry.snrDisplayString?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            if (groupCount > 1) {
                Spacer(modifier = Modifier.size(4.dp))
                Text("×$groupCount", style = MaterialTheme.typography.bodySmall, color = extended.warning)
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.size(8.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    entry.rssi?.let { DetailRow("RSSI", "$it dBm", dense = true) }
                    entry.snr?.let { DetailRow("SNR", "%.1f dB".format(it), dense = true) }
                    DetailRow(stringResource(R.string.rx_f_type), entry.payloadType.displayName, dense = true)
                    DetailRow(stringResource(R.string.rx_f_size), "${entry.rawPayload.size} bytes", dense = true)
                    if (entry.payloadType == PayloadType.TRACE) {
                        val idParts = entry.traceRouteIdParts()
                        if (idParts.isNotEmpty()) DetailRow(stringResource(R.string.rx_f_trace_route), idParts.joinToString(" → "), dense = true)
                    } else {
                        DetailRow(stringResource(R.string.rx_f_path), entry.pathDetailString(localPublicKeyPrefix), dense = true)
                    }
                    DetailRow("Hash", entry.packetHash, dense = true)
                    if (entry.isDirectTextMessage && entry.senderPrefix != null && entry.recipientPrefix != null) {
                        DetailRow(stringResource(R.string.rx_f_from), resolveHashLabel(entry.senderPrefix!!, nodeNames, localPublicKeyPrefix), dense = true)
                        DetailRow(stringResource(R.string.chat_path_to), resolveHashLabel(entry.recipientPrefix!!, nodeNames, localPublicKeyPrefix), dense = true)
                    }
                    if (entry.decryptStatus == DecryptStatus.SUCCESS) {
                        entry.channelName?.let { DetailRow(stringResource(R.string.rx_f_channel), it, dense = true) }
                        entry.decodedText?.let { DetailRow(stringResource(R.string.rx_f_text), it, dense = true) }
                        if (entry.transportCode?.isNotEmpty() == true) {
                            val regionValue = when (val region = RegionScopeSemantics.coalesce(entry.regionScope, entry.regionScopeMatches)) {
                                is RegionMatchResult.None -> stringResource(R.string.common_unknown)
                                is RegionMatchResult.Unique -> region.name
                                is RegionMatchResult.Ambiguous -> region.names.joinToString(", ")
                            }
                            DetailRow(stringResource(R.string.rx_f_region), regionValue, dense = true)
                        }
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.rx_raw_payload), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(entry.rawPayload.toDisplayHex())) }) { Text(stringResource(R.string.common_copy)) }
                    }
                    SelectionContainer {
                        Text(
                            entry.rawPayload.toDisplayHex(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

