// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.pathediting.NeighborNameResolver
import com.meshcoretwo.android.pathediting.ResolvedPathHop
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto

/**
 * Read-only display of the route used to reach a node: a collapsed summary (`A3 → 7F → 42`, or
 * `Direct`) that expands into the resolved repeater name for each hop. Ported from
 * `NodeRoutePathSection.swift`/`NodePathSummaryLabel`/`NodePathHopRow` — a dumb leaf with no view
 * model of its own, since [NeighborNameResolver.resolvePath] resolves synchronously from
 * [contacts]/[discoveredNodes] the caller already holds. Used at the bottom of
 * [RepeaterStatusScreen] (only when the session's own contact is known — see
 * [RepeaterStatusViewModel]'s `routePathContact`) and on [NodeAuthScreen] (fed by
 * `NodeAuthViewModel`'s async-loaded `routeContacts`/`routeDiscoveredNodes`, mirroring
 * `NodeAuthPathViewModel`). Room has no equivalent call site — same reason `RoomStatusScreen` has
 * no neighbors section: rooms have no relaying path to inspect.
 *
 * The Swift login sheet's own `PathSection` bundles a flood-routing toggle with this display. Here
 * the toggle stays in [NodeAuthScreen] and comes in through [content], rendered under the route in
 * the same group; [showStoredRoute] hides the stored route while that toggle is on, as Swift does.
 * [RepeaterStatusScreen] passes neither.
 */
@Composable
fun NodeRoutePathSection(
    contact: ContactDto,
    contacts: List<ContactDto>,
    discoveredNodes: List<DiscoveredNodeDto>,
    userLocation: LocationFix?,
    showStoredRoute: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.contacts_path), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.size(8.dp))

        if (contact.isFloodRouted) {
            Text(stringResource(R.string.route_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (showStoredRoute) {
            val hops = remember(contact, contacts, discoveredNodes, userLocation) {
                NeighborNameResolver.resolvePath(contact.pathHops, contacts, discoveredNodes, userLocation)
            }
            if (hops.isEmpty()) {
                NodePathSummaryLabel(contact)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NodePathSummaryLabel(contact)
                    Icon(
                        painterResource(if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (isExpanded) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        hops.forEach { hop -> NodePathHopRow(hop) }
                    }
                }
            }
        }
        content()
    }
}

/** The collapsed one-line route summary (`A3 → 7F → 42`, or `Direct`). Ported from `NodePathSummaryLabel`. */
@Composable
fun NodePathSummaryLabel(contact: ContactDto) {
    val text = if (contact.pathHopCount == 0) "Direct" else contact.pathString
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
}

/** A single expanded hop: hash hex plus the resolved repeater name. Ported from `NodePathHopRow`. */
@Composable
private fun NodePathHopRow(hop: ResolvedPathHop) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            hop.hex,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(" ${hop.resolution.displayName}", style = MaterialTheme.typography.bodyMedium)
        if (hop.resolution.isFallback) {
            Text(" (?)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
