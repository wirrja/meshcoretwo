// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.ui.components.listCard
import androidx.compose.ui.graphics.Color
import com.meshcoretwo.android.ui.components.RefreshOnResume
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.CompactSearchTopBar
import com.meshcoretwo.android.ui.components.ConfirmDialog
import com.meshcoretwo.android.ui.components.ConnectingState
import com.meshcoretwo.android.ui.components.EmptyState
import com.meshcoretwo.android.ui.components.FilterChipRow
import com.meshcoretwo.android.ui.components.HeaderIconButton
import com.meshcoretwo.android.ui.components.PresenceAvatar
import com.meshcoretwo.android.ui.components.RouteChip
import com.meshcoretwo.android.ui.formatRelativeTimestamp
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.location.LocationProviderError
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.rf.GeoCoordinate
import com.meshcoretwo.services.rf.RFCalculator

private enum class ContactSegment(@StringRes val labelRes: Int) {
    FAVORITES(R.string.common_favorites),
    CONTACTS(R.string.common_contacts),
    REPEATERS(R.string.map_filter_repeaters),
    ROOMS(R.string.common_rooms),
}

/**
 * Contacts list — every known node (chat contacts, repeaters, room servers), segmented the same
 * way as `ContactsListView.swift`/`ContactsViewModel.swift`'s `NodeSegment` tabs. Deliberately
 * broader than the chat list's contact filter (`type != .repeater && !isBlocked`): this is an
 * "all nodes" browser, so blocked contacts still appear (iOS doesn't hide them here either) and
 * repeaters/rooms get their own tabs instead of being excluded.
 *
 * Sort order ([NodeSortOrder], shared with [DiscoveryScreen]'s own sort menu) and the fetched
 * [LocationFix] both reset each time this screen recomposes fresh rather than persisting —
 * `@AppStorage`'s Android equivalent doesn't exist for UI prefs yet, same simplification
 * [DiscoveryScreen] already documents for its identical menu.
 *
 * [onOpenDiscovery] (the "Discover" toolbar action, pushing
 * [com.meshcoretwo.android.contacts.DiscoveryScreen]) covers advert-driven add-contact.
 * [onOpenAddContact] (ported from `AddContactSheet.swift`, folded with `ScanContactQRView.swift`
 * into [com.meshcoretwo.android.contacts.AddContactScreen]) and [onOpenShareMyContact] (ported
 * from `ContactQRShareSheet.swift` for the connected device's own identity, via
 * [com.meshcoretwo.android.contacts.ContactQRShareScreen]) close the "QR/advert-шаринг" deferral —
 * see PLAN.md's Phase 5 contacts slice. Not ported: iOS's duplicate "Share My Contact" entry point
 * on a `DeviceInfoView`-equivalent screen (this port has no standalone Device Info screen, only
 * inline Settings sections) — the Contacts list's own entry point already covers the feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsListScreen(
    connectionManager: ConnectionManager,
    locationProvider: LocationProvider,
    onOpenContact: (ContactDto) -> Unit,
    onOpenDiscovery: () -> Unit,
    onOpenBlockedContacts: () -> Unit,
    onOpenAddContact: () -> Unit,
    onOpenShareMyContact: () -> Unit,
) {
    val viewModel: ContactsListViewModel = viewModel(factory = ContactsListViewModel.Factory(connectionManager))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RefreshOnResume(viewModel::refresh)
    var segment by remember { mutableStateOf(ContactSegment.FAVORITES) }
    var query by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(NodeSortOrder.LAST_HEARD) }
    var userLocation by remember { mutableStateOf<LocationFix?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var menuContact by remember { mutableStateOf<ContactDto?>(null) }
    var blockCandidate by remember { mutableStateOf<ContactDto?>(null) }
    var deleteCandidate by remember { mutableStateOf<ContactDto?>(null) }

    LaunchedEffect(sortOrder) {
        if ((sortOrder == NodeSortOrder.DISTANCE || sortOrder == NodeSortOrder.HOPS) && userLocation == null) {
            userLocation = try {
                locationProvider.requestCurrentLocation()
            } catch (error: LocationProviderError) {
                null
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CompactSearchTopBar(
                title = stringResource(R.string.common_contacts),
                connectionManager = connectionManager,
                actions = {
                    Box {
                        HeaderIconButton(onClick = { showSortMenu = true }) { Icon(painterResource(R.drawable.ic_swap_vert), contentDescription = stringResource(R.string.common_sort)) }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            NodeSortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(order.labelRes)) },
                                    leadingIcon = { if (order == sortOrder) Icon(painterResource(R.drawable.ic_check), contentDescription = null) },
                                    onClick = { sortOrder = order; showSortMenu = false },
                                )
                            }
                        }
                    }
                    HeaderIconButton(onClick = onOpenAddContact) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.contacts_add_cd)) }
                    Box {
                        HeaderIconButton(onClick = { showMoreMenu = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.common_more)) }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.contacts_discover)) }, onClick = { showMoreMenu = false; onOpenDiscovery() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.contacts_share_mine)) }, onClick = { showMoreMenu = false; onOpenShareMyContact() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.contacts_blocked_menu)) }, onClick = { showMoreMenu = false; onOpenBlockedContacts() })
                        }
                    }
                },
                searchQuery = query,
                onSearchQueryChange = { query = it },
                searchPlaceholder = stringResource(R.string.contacts_search),
                filters = if (query.isBlank()) {
                    { FilterChipRow(items = ContactSegment.entries, selected = segment, onSelect = { segment = it }, label = { stringResource(it.labelRes) }) }
                } else {
                    null
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            is ContactsListUiState.Connecting -> ConnectingState(modifier = Modifier.padding(padding))
            is ContactsListUiState.Ready -> {
                val filtered = filterContacts(current.contacts, segment, query)
                val sorted = sortContacts(filtered, sortOrder, userLocation, current.inboundHopByKey)
                if (sorted.isEmpty()) {
                    EmptyContactsContent(
                        segment = segment,
                        isSearching = query.isNotBlank(),
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    )
                } else {
                    LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                        items(sorted, key = { it.id }) { contact ->
                            Box(modifier = Modifier.animateItem()) {
                                ContactRow(
                                    asCard = true,
                                    contact = contact,
                                    userLocation = userLocation,
                                    inboundHopCount = current.inboundHopByKey[contact.publicKey.hexString],
                                    onClick = { onOpenContact(contact) },
                                    onLongClick = { menuContact = contact },
                                )
                                DropdownMenu(expanded = menuContact?.id == contact.id, onDismissRequest = { menuContact = null }) {
                                    if (contact.type == ContactType.CHAT) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(if (contact.isBlocked) R.string.common_unblock else R.string.common_block)) },
                                            onClick = {
                                                menuContact = null
                                                if (contact.isBlocked) viewModel.setBlocked(contact, false) else blockCandidate = contact
                                            },
                                        )
                                    }
                                    if (viewModel.canDelete(contact)) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chats_delete_from_contacts), color = MaterialTheme.colorScheme.error) },
                                            onClick = { menuContact = null; deleteCandidate = contact },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    blockCandidate?.let { contact ->
        ConfirmDialog(
            title = stringResource(R.string.chats_block_title, contact.displayName),
            body = stringResource(R.string.chats_block_body),
            confirmLabel = stringResource(R.string.common_block),
            onConfirm = { viewModel.setBlocked(contact, true) },
            onDismiss = { blockCandidate = null },
        )
    }
    deleteCandidate?.let { contact ->
        ConfirmDialog(
            title = stringResource(R.string.chats_delete_title, contact.displayName),
            body = stringResource(R.string.chats_delete_body),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = { viewModel.delete(contact) },
            onDismiss = { deleteCandidate = null },
        )
    }
}

/**
 * Ported from `ContactsViewModel.filteredContacts`: a non-blank [query] searches every contact
 * type by name or hex public-key prefix, bypassing the segment entirely; otherwise the segment
 * alone decides membership (favorites/contacts/repeaters/rooms).
 */
private fun filterContacts(contacts: List<ContactDto>, segment: ContactSegment, query: String): List<ContactDto> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isNotEmpty()) {
        return contacts.filter {
            it.displayName.contains(trimmedQuery, ignoreCase = true) || it.publicKeyPrefixHex().contains(trimmedQuery, ignoreCase = true)
        }
    }
    return when (segment) {
        ContactSegment.FAVORITES -> contacts.filter { it.isFavorite }
        ContactSegment.CONTACTS -> contacts.filter { it.type == ContactType.CHAT }
        ContactSegment.REPEATERS -> contacts.filter { it.type == ContactType.REPEATER }
        ContactSegment.ROOMS -> contacts.filter { it.type == ContactType.ROOM }
    }
}

/**
 * Ported from `ContactListActions.filteredContacts`/`ContactsViewModel.sorted(_:by:userLocation:)`:
 * [NodeSortOrder.DISTANCE] falls back to [NodeSortOrder.LAST_HEARD] when [userLocation] is
 * unavailable, matching iOS's own fallback (rather than degrading to a name-only compare, which is
 * what [orderedByDistanceThenName] alone would do).
 */
private fun sortContacts(
    contacts: List<ContactDto>,
    sortOrder: NodeSortOrder,
    userLocation: LocationFix?,
    inboundHopByKey: Map<String, Int>,
): List<ContactDto> {
    val effectiveOrder = if (sortOrder == NodeSortOrder.DISTANCE && userLocation == null) NodeSortOrder.LAST_HEARD else sortOrder
    return when (effectiveOrder) {
        NodeSortOrder.LAST_HEARD -> contacts.sortedByDescending { it.recencyTimestamp }
        NodeSortOrder.NAME -> contacts.sortedBy { it.displayName.lowercase() }
        NodeSortOrder.DISTANCE -> contacts.sortedWith { a, b -> orderedByDistanceThenName(a, b, userLocation) }
        NodeSortOrder.HOPS -> contacts.sortedWith { a, b -> orderedByHopsThenDistance(a, b, userLocation, inboundHopByKey) }
    }
}

/** Located contacts first, then nearest to [userLocation], then by name. */
private fun orderedByDistanceThenName(lhs: ContactDto, rhs: ContactDto, userLocation: LocationFix?): Int {
    if (userLocation != null) {
        if (lhs.hasLocation != rhs.hasLocation) return if (lhs.hasLocation) -1 else 1
        if (lhs.hasLocation) {
            val lhsDistance = RFCalculator.distance(GeoCoordinate(userLocation.latitude, userLocation.longitude), GeoCoordinate(lhs.latitude, lhs.longitude))
            val rhsDistance = RFCalculator.distance(GeoCoordinate(userLocation.latitude, userLocation.longitude), GeoCoordinate(rhs.latitude, rhs.longitude))
            if (lhsDistance != rhsDistance) return lhsDistance.compareTo(rhsDistance)
        }
    }
    return lhs.displayName.compareTo(rhs.displayName, ignoreCase = true)
}

/** Fewer hops first (unknown hops sort last), ties broken by [orderedByDistanceThenName]. */
private fun orderedByHopsThenDistance(lhs: ContactDto, rhs: ContactDto, userLocation: LocationFix?, inboundHopByKey: Map<String, Int>): Int {
    val lhsHops = lhs.displayedHopCount(inboundHopByKey[lhs.publicKey.hexString])
    val rhsHops = rhs.displayedHopCount(inboundHopByKey[rhs.publicKey.hexString])
    if ((lhsHops == null) != (rhsHops == null)) return if (lhsHops != null) -1 else 1
    if (lhsHops != null && rhsHops != null && lhsHops != rhsHops) return lhsHops.compareTo(rhsHops)
    return orderedByDistanceThenName(lhs, rhs, userLocation)
}

/** `"1.2 km away"`-style label, or null when either side lacks a location — mirrors `DiscoveryScreen`'s own. */
@Composable
private fun distanceLabel(contact: ContactDto, userLocation: LocationFix?): String? {
    if (userLocation == null || !contact.hasLocation) return null
    val meters = RFCalculator.distance(GeoCoordinate(userLocation.latitude, userLocation.longitude), GeoCoordinate(contact.latitude, contact.longitude))
    return if (meters >= 1_000) stringResource(R.string.distance_km_away, "%.1f".format(meters / 1_000)) else stringResource(R.string.distance_m_away, meters.toInt())
}

@Composable
private fun EmptyContactsContent(segment: ContactSegment, isSearching: Boolean, modifier: Modifier = Modifier) {
    val (icon, title, description) = when {
        isSearching -> Triple(R.drawable.ic_search, stringResource(R.string.path_no_matches), stringResource(R.string.contacts_no_match_desc))
        segment == ContactSegment.FAVORITES -> Triple(R.drawable.ic_star_border, stringResource(R.string.path_no_favorites), stringResource(R.string.contacts_no_favorites_desc))
        segment == ContactSegment.CONTACTS -> Triple(R.drawable.ic_person, stringResource(R.string.contacts_none_here), stringResource(R.string.contacts_none_desc))
        segment == ContactSegment.REPEATERS -> Triple(R.drawable.ic_cell_tower, stringResource(R.string.path_no_repeaters), stringResource(R.string.contacts_no_repeaters_desc))
        else -> Triple(R.drawable.ic_meeting_room, stringResource(R.string.contacts_no_rooms), stringResource(R.string.contacts_no_rooms_desc))
    }
    EmptyState(icon = icon, title = title, description = description, modifier = modifier)
}

/**
 * `internal`, not `private`: [BlockedContactsScreen] reuses this same row for its own filtered list
 * (no [onLongClick] there — the long-press menu is a contacts-list feature).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContactRow(
    contact: ContactDto,
    userLocation: LocationFix?,
    inboundHopCount: Int? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    asCard: Boolean = false,
) {
    Row(
        modifier = (if (asCard) modifier.listCard(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 14.dp, vertical = 14.dp)
        else modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceAvatar(
            name = contact.displayName,
            lastHeard = contact.lastHeardTimestamp.toInstantOrNull(),
            category = AvatarCategory.fromContactType(contact.type),
            size = if (asCard) 52.dp else 44.dp,
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            // `Arrangement.SpaceBetween`, not a trailing `Spacer(Modifier.weight(1f))`: with two
            // same-weight flexible children, the name and that spacer used to split the row's
            // leftover width 50/50 regardless of whether the name actually needed it, so a
            // longer/localized name truncated to ellipsis at half the room it could've used,
            // leaving a dead gap before the timestamp — the "name reads as squeezed/cut off"
            // look this fixes. The name+badges group is now the row's only flex participant.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (contact.isBlocked) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            painterResource(R.drawable.ic_block),
                            contentDescription = stringResource(R.string.common_blocked),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (contact.isFavorite) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            painterResource(R.drawable.ic_star),
                            contentDescription = stringResource(R.string.common_favorite),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                contact.lastHeardTimestamp.toInstantOrNull()?.let { date ->
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatRelativeTimestamp(date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.size(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.publicKeyPrefixHex(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(6.dp))
                RouteChip(label = contact.routeLabel(inboundHopCount))
                if (contact.hasLocation) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painterResource(R.drawable.ic_location_on),
                        contentDescription = stringResource(R.string.contacts_has_location),
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    distanceLabel(contact, userLocation)?.let { distance ->
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = distance,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
