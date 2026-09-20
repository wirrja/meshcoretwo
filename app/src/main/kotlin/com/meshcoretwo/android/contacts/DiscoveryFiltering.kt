// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.annotation.StringRes
import com.meshcoretwo.android.R
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.rf.GeoCoordinate
import com.meshcoretwo.services.rf.RFCalculator

/** Segment for the Discover list's filter row. Ported from `DiscoverSegment` (`DiscoveryViewModel.swift`). */
enum class DiscoverSegment(@StringRes val labelRes: Int) {
    ALL(R.string.common_all),
    CONTACTS(R.string.common_contacts),
    REPEATERS(R.string.map_filter_repeaters),
    ROOMS(R.string.common_rooms),
}

/** Ported from `NodeSortOrder` (`ContactsViewModel.swift`) — only the Discover list uses it in this port so far. */
enum class NodeSortOrder(@StringRes val labelRes: Int) {
    LAST_HEARD(R.string.sort_last_heard),
    NAME(R.string.sort_name),
    DISTANCE(R.string.sort_distance),
    HOPS(R.string.sort_hops),
}

/**
 * Pure filter/sort logic backing [DiscoveryScreen], factored out of the view/view model so it's
 * plain-JUnit testable without Robolectric — same split as
 * [com.meshcoretwo.android.pathediting.RepeaterResolver]. Ported from `DiscoveryViewModel.swift`'s
 * `filteredNodes`/`sorted(_:by:userLocation:)`.
 */
object DiscoveryFiltering {
    /**
     * A non-blank [searchText] searches every node's name/hex-key prefix, bypassing [segment]
     * entirely; otherwise [segment] alone decides membership, then the result is sorted by
     * [sortOrder].
     */
    fun visibleNodes(
        nodes: List<DiscoveredNodeDto>,
        searchText: String,
        segment: DiscoverSegment,
        sortOrder: NodeSortOrder,
        userLocation: LocationFix?,
    ): List<DiscoveredNodeDto> {
        val trimmed = searchText.trim()
        val filtered = if (trimmed.isEmpty()) {
            when (segment) {
                DiscoverSegment.ALL -> nodes
                DiscoverSegment.CONTACTS -> nodes.filter { it.nodeType == ContactType.CHAT }
                DiscoverSegment.REPEATERS -> nodes.filter { it.nodeType == ContactType.REPEATER }
                DiscoverSegment.ROOMS -> nodes.filter { it.nodeType == ContactType.ROOM }
            }
        } else {
            nodes.filter { node -> node.name.contains(trimmed, ignoreCase = true) || node.publicKey.hexString.startsWith(trimmed, ignoreCase = true) }
        }
        return sorted(filtered, sortOrder, userLocation)
    }

    private fun sorted(nodes: List<DiscoveredNodeDto>, order: NodeSortOrder, userLocation: LocationFix?): List<DiscoveredNodeDto> =
        when (order) {
            NodeSortOrder.LAST_HEARD -> nodes.sortedByDescending { it.lastHeard }
            NodeSortOrder.NAME -> nodes.sortedBy { it.name.lowercase() }
            NodeSortOrder.DISTANCE -> nodes.sortedWith(compareBy({ distanceMeters(it, userLocation) }, { it.name.lowercase() }))
            // A null hop count (flood-routed, never heard via advert) sorts to the bottom — same
            // trick as an unlocated node under DISTANCE: Int.MAX_VALUE/infinity both sort last.
            NodeSortOrder.HOPS -> nodes.sortedWith(
                compareBy({ it.displayedHopCount ?: Int.MAX_VALUE }, { distanceMeters(it, userLocation) }, { it.name.lowercase() }),
            )
        }

    /** Great-circle distance in meters, or [Double.POSITIVE_INFINITY] when either side lacks a location. */
    private fun distanceMeters(node: DiscoveredNodeDto, userLocation: LocationFix?): Double {
        if (userLocation == null || !node.hasLocation) return Double.POSITIVE_INFINITY
        return RFCalculator.distance(
            GeoCoordinate(userLocation.latitude, userLocation.longitude),
            GeoCoordinate(node.latitude, node.longitude),
        )
    }
}
