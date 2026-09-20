// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.pathediting.NeighborNameResolver
import com.meshcoretwo.android.ui.theme.snrQualityColor
import com.meshcoretwo.android.ui.theme.snrQualityGlyph
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.RegionMatchResult
import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageRepeatDto
import com.meshcoretwo.services.rendering.NodeNameMatchKind
import com.meshcoretwo.services.rendering.NodeNameResolution
import com.meshcoretwo.android.ui.i18n.labelRes
import com.meshcoretwo.services.rendering.SNRQuality
import com.meshcoretwo.services.rxlog.RegionScopeSemantics
import java.time.Duration
import java.time.Instant
import com.meshcoretwo.android.ui.i18n.DatePatterns
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The data behind the expandable Repeat Details/View Path row, loaded once on expand-eligible
 * open. [repeats] stays `null` until [ConversationViewModel.fetchMessageDetails] resolves — used
 * only when [MessageActionAvailability.canShowRepeatDetails] is true; unused (always `emptyList`
 * from the caller) when only [MessageActionAvailability.canViewPath] applies, since path
 * resolution reads [contacts]/[discoveredNodes] only. Ported from the three concurrently-loaded
 * `@State` properties `MessageActionsSheet.swift`'s `.task` sets.
 */
data class MessageDetailData(
    val repeats: List<MessageRepeatDto>?,
    val contacts: List<ContactDto>,
    val discoveredNodes: List<DiscoveredNodeDto>,
)

/** `internal`, not `private`: [RoomMessageActionsSheet] reuses the same format for its own details footer. */
internal val detailDateFormat: DateTimeFormatter get() = DatePatterns.dateTimeMediumSeconds()

/**
 * Always-visible send/receive metadata footer, plus — when available — a "Path map" button and the
 * expandable "Repeat Details"/"View Path" row above it. Ported from `ActionsDetailsSection.swift`.
 *
 * [onFetchDetails] is the one-shot loader, same callback shape as [ConversationViewModel
 * .fetchReactionDetails] — called only when [availability] actually needs it (matches Swift's
 * `.task` gate), not on every message. [selfName] is this radio's own node name, shown as the
 * path's receiver — falls back to "You" when disconnected, matching
 * `appState.connectedDevice?.nodeName ?? L10n...you`. [onViewPathMap] pushes
 * [com.meshcoretwo.android.chat.MessagePathMapScreen] (`MessagePathMapView.swift`'s `.sheet`
 * translated to a nav push, same precedent as `ContactDetailView`'s `.sheet($showFullMap)` →
 * `ContactLocationHistoryMapScreen`) — shown above the expandable row exactly like Swift's
 * `pathMapButton`, which is a sibling of `ActionsExpandableDetailRow`, not nested inside it.
 */
@Composable
fun MessageDetailsSection(
    message: MessageDto,
    availability: MessageActionAvailability,
    selfName: String?,
    onFetchDetails: (MessageDto, (MessageDetailData) -> Unit) -> Unit,
    onCopyPath: (String) -> Unit,
    onViewPathMap: () -> Unit,
) {
    var isExpanded by remember(message.id) { mutableStateOf(false) }
    var detailData by remember(message.id) { mutableStateOf<MessageDetailData?>(null) }
    val showExpandableRow = availability.showsPathDetail
    val hasExtraPaths = message.hasExtraIncomingPaths

    LaunchedEffect(message.id) {
        if (showExpandableRow) onFetchDetails(message) { detailData = it }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (availability.canViewPath) {
            MessageActionRow(icon = R.drawable.ic_map, label = stringResource(R.string.chat_path_map), onClick = onViewPathMap)
        }
        if (showExpandableRow) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (availability.canShowRepeatDetails) R.drawable.ic_cell_tower else R.drawable.ic_route),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 16.dp),
                )
                Text(
                    stringResource(
                        when {
                            availability.canShowRepeatDetails -> R.string.chat_repeat_details
                            hasExtraPaths -> R.string.chat_path_details
                            else -> R.string.chat_view_path
                        },
                    ),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.rotate(if (isExpanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (availability.canShowRepeatDetails) {
                        RepeatDetailsList(detailData?.repeats, detailData?.contacts ?: emptyList(), detailData?.discoveredNodes ?: emptyList())
                    } else {
                        MessagePathList(message, detailData?.contacts ?: emptyList(), detailData?.discoveredNodes ?: emptyList(), selfName ?: stringResource(R.string.common_you), onCopyPath)
                        if (hasExtraPaths) {
                            ExtraArrivalsList(message, detailData?.repeats, onCopyPath)
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        }

        Text(
            stringResource(R.string.common_details),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            if (message.isOutgoing) OutgoingDetailRows(message) else IncomingDetailRows(message)
        }
    }
}

@Composable
private fun OutgoingDetailRows(message: MessageDto) {
    DetailRow(stringResource(R.string.chat_sent_at, detailDateFormat.format(Instant.ofEpochSecond(message.timestamp.toLong()))))
    message.roundTripTime?.let { rtt -> DetailRow(stringResource(R.string.chat_round_trip, rtt)) }
    if (message.heardRepeats > 0) {
        DetailRow(pluralStringResource(R.plurals.chat_heard_by, message.heardRepeats, message.heardRepeats))
    }
}

@Composable
private fun IncomingDetailRows(message: MessageDto) {
    val hopsText = if (message.isDirectRouted) "Direct" else if (message.hopCount == 1) "1 hop" else "${message.hopCount} hops"
    DetailRow(hopsText)
    message.pathHashSizeIfKnown?.let { DetailRow(stringResource(R.string.chat_path_hash, it)) }
    if (message.routeType == RouteType.TC_FLOOD) {
        DetailRow(
            when (val match = RegionScopeSemantics.coalesce(message.regionScope, message.regionScopeMatches)) {
                is RegionMatchResult.None -> stringResource(R.string.chat_region_unknown)
                is RegionMatchResult.Unique -> stringResource(R.string.chat_region_unique, match.name)
                is RegionMatchResult.Ambiguous -> stringResource(R.string.chat_region_ambiguous, match.names.joinToString(", "))
            },
        )
    }
    val sent = stringResource(R.string.chat_sent_at, detailDateFormat.format(Instant.ofEpochSecond(message.timestamp.toLong())))
    DetailRow(if (message.timestampCorrected) stringResource(R.string.chat_sent_adjusted, sent) else sent)
    if (message.timestampCorrected) DetailRow(stringResource(R.string.chat_original_send_time, detailDateFormat.format(message.wireSentInstant)))
    DetailRow(stringResource(R.string.chat_received_at, detailDateFormat.format(message.createdAt)))
    message.snr?.let { snr -> DetailRow(stringResource(R.string.chat_snr_quality, "%.1f".format(snr), stringResource(SNRQuality.of(snr).labelRes()))) }
}

/** `internal`, not `private`: [RoomMessageActionsSheet] reuses this same row for its own details footer. */
@Composable
internal fun DetailRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
}

/** Expanded "Repeat Details" content. Ported from `RepeatDetailsContent.swift`. */
@Composable
private fun RepeatDetailsList(repeats: List<MessageRepeatDto>?, contacts: List<ContactDto>, discoveredNodes: List<DiscoveredNodeDto>) {
    when {
        repeats == null -> Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        repeats.isEmpty() -> Text(
            stringResource(R.string.chat_no_repeats),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        else -> {
            val repeaters = remember(contacts) { contacts.filter { it.type == ContactType.REPEATER } }
            val discoveredRepeaters = remember(discoveredNodes) { discoveredNodes.filter { it.nodeType == ContactType.REPEATER } }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (repeat in repeats) {
                    RepeatRow(repeat, repeaters, discoveredRepeaters)
                }
            }
        }
    }
}

/** One heard-repeat entry: repeater identity, hop count, SNR/RSSI. Ported from `RepeatRowView.swift`. */
@Composable
private fun RepeatRow(repeat: MessageRepeatDto, repeaters: List<ContactDto>, discoveredRepeaters: List<DiscoveredNodeDto>) {
    val unknownRepeater = stringResource(R.string.chat_unknown_repeater)
    val resolution = remember(repeat, repeaters, discoveredRepeaters, unknownRepeater) {
        repeat.repeaterHash?.let { hash -> NeighborNameResolver.resolve(hash, repeaters, discoveredRepeaters, userLocation = null) }
            ?: NodeNameResolution(displayName = unknownRepeater, matchKind = NodeNameMatchKind.UNRESOLVED)
    }
    val quality = SNRQuality.of(repeat.snr)

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(repeat.repeaterHashFormatted, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(resolution.displayName)
                if (resolution.isFallback) {
                    Text("(?)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                if (repeat.hopCount == 1) "1 hop" else "${repeat.hopCount} hops",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(snrQualityGlyph(quality), color = snrQualityColor(quality))
            Text(
                "SNR " + (repeat.snr?.let { "%.1f dB".format(it) } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "RSSI " + (repeat.rssi?.let { "$it dBm" } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Expanded "View Path" content: sender, intermediate hops, receiver, then a copy-path row. Ported from `MessagePathContent.swift`. */
@Composable
private fun MessagePathList(message: MessageDto, contacts: List<ContactDto>, discoveredNodes: List<DiscoveredNodeDto>, receiverName: String, onCopyPath: (String) -> Unit) {
    if (message.pathNodes == null) {
        Text(
            stringResource(R.string.chat_path_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    val unknownName = stringResource(R.string.common_unknown)
    val senderResolution = remember(message, contacts, unknownName) { message.senderResolution(contacts, unknownName) }
    val resolvedHops = remember(message, contacts, discoveredNodes) {
        NeighborNameResolver.resolvePath(message.pathHops, contacts, discoveredNodes, userLocation = null)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PathHopRow(label = stringResource(R.string.chat_path_from), name = senderResolution.displayName, idHex = message.senderNodeIDHex(), isFallback = senderResolution.isFallback)
        for (hop in resolvedHops) {
            PathHopRow(label = "Hop", name = hop.resolution.displayName, idHex = hop.hex, isFallback = hop.resolution.isFallback)
        }
        PathHopRow(label = stringResource(R.string.chat_path_to), name = receiverName, idHex = null, isFallback = false, snr = message.snr)

        if (resolvedHops.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable {
                    onCopyPath(resolvedHops.joinToString(",") { it.hex })
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_content_copy), contentDescription = stringResource(R.string.chat_copy_path), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    resolvedHops.joinToString(" → ") { it.hex },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The other flood copies of an incoming message: when each arrived relative to the first, how many
 * hops, over which path, and how strong. Tapping the path copies it, same as the first arrival's.
 * Ported from the extra-arrival rows of `MessagePathDetailBlock.swift`, as a plain list — the
 * iOS capsule/preview-map presentation isn't ported.
 */
@Composable
private fun ExtraArrivalsList(message: MessageDto, repeats: List<MessageRepeatDto>?, onCopyPath: (String) -> Unit) {
    if (repeats == null) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (repeats.isEmpty()) return
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (extra in repeats.sortedBy { it.receivedAt }) {
            ExtraArrivalRow(message, extra, onCopyPath)
        }
    }
}

@Composable
private fun ExtraArrivalRow(message: MessageDto, extra: MessageRepeatDto, onCopyPath: (String) -> Unit) {
    val hops = extra.pathHops
    val quality = SNRQuality.of(extra.snr)
    Column(modifier = Modifier.fillMaxWidth().then(if (hops.isNotEmpty()) Modifier.clickable { onCopyPath(hops.joinToString(",") { it.hex }) } else Modifier)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.chat_path_arrival_offset, formatArrivalOffset(Duration.between(message.createdAt, extra.receivedAt))),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(snrQualityGlyph(quality), color = snrQualityColor(quality))
                Text(
                    listOfNotNull(extra.snr?.let { "SNR %.1f dB".format(it) }, extra.rssi?.let { "RSSI $it dBm" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            if (extra.arrivalHopCount == 0) "Direct" else if (extra.arrivalHopCount == 1) "1 hop" else "${extra.arrivalHopCount} hops",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hops.isNotEmpty()) {
            Text(
                hops.joinToString(" → ") { it.hex },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PathHopRow(label: String, name: String, idHex: String?, isFallback: Boolean, snr: Double? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                idHex?.let { Text(it, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(name)
                if (isFallback) Text("(?)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (snr != null) {
            val quality = SNRQuality.of(snr)
            Text(snrQualityGlyph(quality), color = snrQualityColor(quality))
        }
    }
}
