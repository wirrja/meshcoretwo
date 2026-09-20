// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.EmptyState
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.ContactDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared full-screen Add-Hop picker, meant to be pushed from both the (still unported) contact
 * path editor and the trace path builder (`TracePathListView`, next sub-slice). Finds a node fast
 * via name substring or hex prefix; a comma in the search field switches to bulk code entry.
 * Ported from `AddHopPickerView`.
 *
 * Two deliberate simplifications vs. Swift:
 * - **No `AddHopIntent` parameter.** Swift models it as a single-case enum "so the item binding
 *   has a concrete `Identifiable`/`Hashable` type" for `navigationDestination(item:)` — a Compose
 *   `NavHost` route needs no such binding type, and the enum's own doc says only `.append` exists
 *   today, so there is nothing left for a Kotlin port to abstract over.
 * - **No `presentsOwnDismiss`/iPad-on-Mac split-view branch.** That entire case
 *   (`addHopPicker(for:source:inDetailColumn:)`) is an iPad-only affordance with no Android
 *   equivalent; [onDismiss] always renders as a back action in the top bar, matching every other
 *   pushed screen in this port.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddHopPickerScreen(hopPickerSource: HopPickerSource, onDismiss: () -> Unit) {
    var searchText by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AddHopFilter.ALL) }
    val sessionRecentKeys = remember { hopPickerSource.recentPublicKeys }
    val clipboard = LocalClipboardManager.current
    val isBulkMode = searchText.contains(",")

    LaunchedEffect(searchText) {
        if (searchText.contains(",")) hopPickerSource.adoptHashSize(searchText)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.path_add_hop)) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    TextButton(onClick = {
                        val pasted = clipboard.getText()?.text?.trim().orEmpty()
                        if (pasted.isNotEmpty()) searchText = pasted
                    }) { Text(stringResource(R.string.common_paste)) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = bannerText(hopPickerSource),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text(stringResource(R.string.path_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (!hopPickerSource.isPathFull) {
                AddHopSegmentPicker(selection = filter, onSelectionChange = { filter = it })
            }

            when {
                hopPickerSource.isPathFull -> MaxHopsReachedView(hopPickerSource.hopLimit ?: 0)
                isBulkMode -> BulkAddContent(hopPickerSource, searchText, onAdded = { searchText = "" })
                else -> ResultsContent(hopPickerSource, filter, searchText, sessionRecentKeys)
            }
        }
    }
}

@Composable
private fun bannerText(source: HopPickerSource): String =
    if (source.isPathFull) stringResource(R.string.path_max_hops_reached) else stringResource(R.string.path_position, source.currentHopCount + 1)

@Composable
private fun MaxHopsReachedView(hopLimit: Int) {
    EmptyState(
        icon = R.drawable.ic_check_circle,
        title = stringResource(R.string.path_max_hops_reached),
        description = stringResource(R.string.path_max_hops_desc, hopLimit),
    )
}

// MARK: - Results

private data class PickerResults(
    val recent: List<ContactDto> = emptyList(),
    val favorites: List<ContactDto> = emptyList(),
    val contacts: List<ContactDto> = emptyList(),
    val rooms: List<ContactDto> = emptyList(),
) {
    val isEmpty: Boolean get() = recent.isEmpty() && favorites.isEmpty() && contacts.isEmpty() && rooms.isEmpty()
}

/**
 * Builds all four sections. Cross-section dedup (a node already shown in Recent/Favorites doesn't
 * repeat in Contacts) only applies under [AddHopFilter.ALL], where every section is visible at
 * once — a single-section filter shows nothing else, so excluding a key there would hide a node
 * that has no other section to appear in. Ported from `AddHopPickerView.buildResults()`.
 */
private fun buildResults(
    source: HopPickerSource,
    filter: AddHopFilter,
    query: String,
    sessionRecentKeys: List<ByteArray>,
): PickerResults {
    val isUnfiltered = filter == AddHopFilter.ALL
    val recentHexKeys = if (isUnfiltered) sessionRecentKeys.map { it.hexString }.toSet() else emptySet()

    val recent = if (filter == AddHopFilter.ALL || filter == AddHopFilter.RECENT) {
        val resolved = sessionRecentKeys.mapNotNull { key -> source.availableRepeaters.firstOrNull { it.publicKey.contentEquals(key) } }
        HopNodeMatching.filtered(resolved, query)
    } else emptyList()

    val favorites = if (filter == AddHopFilter.ALL || filter == AddHopFilter.FAVORITES) {
        val nodes = source.availableRepeaters
            .filter { it.isFavorite && it.publicKey.hexString !in recentHexKeys }
            .sortedBy { it.displayName.lowercase() }
        HopNodeMatching.filtered(nodes, query)
    } else emptyList()

    val contacts = if (isUnfiltered) {
        val nodes = source.availableRepeaters
            .filter { !it.isFavorite && it.publicKey.hexString !in recentHexKeys }
            .sortedBy { it.displayName.lowercase() }
        HopNodeMatching.filtered(nodes, query)
    } else emptyList()

    val rooms = if (isUnfiltered) {
        val nodes = source.availableRooms
            .filter { it.publicKey.hexString !in recentHexKeys }
            .sortedBy { it.displayName.lowercase() }
        HopNodeMatching.filtered(nodes, query)
    } else emptyList()

    return PickerResults(recent, favorites, contacts, rooms)
}

@Composable
private fun ResultsContent(source: HopPickerSource, filter: AddHopFilter, query: String, sessionRecentKeys: List<ByteArray>) {
    val results = buildResults(source, filter, query, sessionRecentKeys)
    if (results.isEmpty) {
        EmptyResultsView(filter, query, source)
        return
    }
    val recentTitle = stringResource(R.string.common_recent)
    val favoritesTitle = stringResource(R.string.common_favorites)
    val contactsTitle = stringResource(R.string.common_contacts)
    val roomsTitle = stringResource(R.string.common_rooms)
    LazyColumn {
        repeaterSection(recentTitle, results.recent, source)
        repeaterSection(favoritesTitle, results.favorites, source)
        repeaterSection(contactsTitle, results.contacts, source)
        repeaterSection(roomsTitle, results.rooms, source)
    }
}

private fun LazyListScope.repeaterSection(title: String, nodes: List<ContactDto>, source: HopPickerSource) {
    if (nodes.isEmpty()) return
    item {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    items(nodes, key = { it.publicKey.hexString }) { node -> PickerRowView(node, source, modifier = Modifier.animateItem()) }
}

@Composable
private fun EmptyResultsView(filter: AddHopFilter, query: String, source: HopPickerSource) {
    val (icon, title, description) = when {
        query.isNotEmpty() -> Triple(R.drawable.ic_search, stringResource(R.string.path_no_matches), stringResource(R.string.path_no_matches_desc, query))
        filter == AddHopFilter.FAVORITES -> Triple(R.drawable.ic_signal_cellular_off, stringResource(R.string.path_no_favorites), stringResource(R.string.path_no_favorites_desc))
        filter == AddHopFilter.RECENT -> Triple(R.drawable.ic_signal_cellular_off, stringResource(R.string.path_no_recent), stringResource(R.string.path_no_recent_desc))
        else -> Triple(R.drawable.ic_signal_cellular_off, stringResource(R.string.path_no_repeaters), stringResource(R.string.path_no_repeaters_desc))
    }
    EmptyState(icon = icon, title = title, description = description)
}

/** One row in the results list. Ported from the private `PickerRowView` in `AddHopPickerView.swift`. */
@Composable
private fun PickerRowView(node: ContactDto, source: HopPickerSource, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var showSuccess by remember(node.publicKey.hexString) { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !source.isPathFull) {
                source.appendHop(node)
                showSuccess = true
                scope.launch {
                    delay(1_500)
                    showSuccess = false
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .defaultMinSize(minHeight = PathEditMetrics.tapTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PathEditMetrics.rowContentSpacing),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PathEditMetrics.badgeSpacing), verticalAlignment = Alignment.CenterVertically) {
                Text(node.displayName, style = MaterialTheme.typography.bodyLarge)
                if (node.isFavorite) {
                    Icon(
                        painterResource(R.drawable.ic_star),
                        contentDescription = stringResource(R.string.common_favorite),
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFFFC107),
                    )
                }
                if (node.type == ContactType.ROOM) NodeKindBadge(stringResource(R.string.common_room), Color(0xFFEF6C00))
            }
            Text(
                node.publicKey.hexString.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedContent(targetState = showSuccess, label = "hopAddState") { success ->
            if (success) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.common_added), tint = LocalMeshExtendedColors.current.success)
            } else {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.common_add), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// MARK: - Bulk add

@Composable
private fun BulkAddContent(source: HopPickerSource, searchText: String, onAdded: () -> Unit) {
    val classifications = source.classifyCodes(searchText)
    val addableCodes = classifications.filter { it.willBeAdded }.map { it.code }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(classifications, key = { it.code }) { classification -> BulkCodeRow(classification) }
        }
        Button(
            onClick = {
                val result = source.addCodes(searchText)
                if (result.added.isNotEmpty()) onAdded()
            },
            enabled = addableCodes.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(if (addableCodes.isEmpty()) stringResource(R.string.path_enter_codes) else stringResource(R.string.path_add_codes, addableCodes.joinToString(", ")))
        }
    }
}

/** One parsed code in the bulk-add preview, showing the per-code outcome. Ported from the private `BulkCodeRow`. */
@Composable
private fun BulkCodeRow(classification: HopCodeClassification) {
    val (text, glyph, color) = when (classification.status) {
        is HopCodeStatus.WillAdd -> Triple(stringResource(R.string.path_will_add, classification.code), null, null)
        HopCodeStatus.AlreadyInPath -> Triple(stringResource(R.string.path_already_in, classification.code), R.drawable.ic_check, MaterialTheme.colorScheme.onSurfaceVariant)
        HopCodeStatus.NotFound -> Triple(stringResource(R.string.path_not_found, classification.code), R.drawable.ic_help, LocalMeshExtendedColors.current.warning)
        HopCodeStatus.InvalidFormat -> Triple(stringResource(R.string.path_invalid_format, classification.code), R.drawable.ic_warning, MaterialTheme.colorScheme.error)
        HopCodeStatus.PathFull -> Triple(stringResource(R.string.path_full, classification.code), R.drawable.ic_block, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = PathEditMetrics.tapTarget).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(PathEditMetrics.rowContentSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (glyph != null && color != null) Icon(painterResource(glyph), contentDescription = null, tint = color)
    }
}
