// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import com.meshcoretwo.android.map.MapFilterState
import com.meshcoretwo.android.map.SnrLink
import com.meshcoretwo.android.pathediting.PathHop
import com.meshcoretwo.android.pathediting.RepeaterResolver
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.RepeaterResolvable
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.rendering.SNRQuality
import com.meshcoretwo.services.rf.GeoCoordinate
import com.meshcoretwo.services.rf.RFCalculator
import java.util.UUID

/** A repeater or room the user can tap on the trace map: a contact, or a discovered repeater. */
data class TracePathMapPin(
    val id: UUID,
    val node: RepeaterResolvable,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    /** 1-based hop number while the node is on the path. */
    val hopIndex: Int?,
    val isLastHop: Boolean,
) {
    val inPath: Boolean get() = hopIndex != null
}

/**
 * One drawn path segment, ending at outbound hop [pathIndex] (0-based). [quality] stays `null`
 * until a trace succeeded; then it grades the hop's received SNR.
 */
data class TracePathMapLine(
    val id: String,
    val fromLatitude: Double,
    val fromLongitude: Double,
    val toLatitude: Double,
    val toLongitude: Double,
    val pathIndex: Int,
    val quality: SNRQuality?,
)

/** Midpoint distance/SNR label for a traced [TracePathMapLine]. */
data class TracePathMapBadge(val id: String, val latitude: Double, val longitude: Double, val text: String)

data class PlottedTracePath(
    val pins: List<TracePathMapPin>,
    val lines: List<TracePathMapLine>,
    val badges: List<TracePathMapBadge>,
    /** The user's position (where the drawn path starts) followed by every located hop, for camera fitting. */
    val pathCoordinates: List<GeoCoordinate>,
) {
    val pinCoordinates: List<GeoCoordinate> get() = pins.map { GeoCoordinate(it.latitude, it.longitude) }
}

/** What tapping a pin does to the path. Ported from `TracePathMapViewModel.PathPinTapResult`. */
sealed interface TracePathPinAction {
    data class Add(val node: RepeaterResolvable) : TracePathPinAction

    /** Only the last hop can be taken back on the map; the list edits the rest. */
    data object RemoveLast : TracePathPinAction

    data object RejectMiddleHop : TracePathPinAction
}

/**
 * Builds the pins, path lines and SNR badges of the trace path map from the trace view model's
 * state. Ported from `TracePathMapViewModel.swift`'s path-state/overlay logic as a pure function
 * (same approach as [com.meshcoretwo.android.contacts.NeighborSnrMapBuilder]): the screen re-runs
 * it whenever the state, the user's location or the filter changes instead of keeping
 * `pathState`/`mapLines`/`badgePoints` in sync by hand. Camera regions and pin sprites stay on
 * the screen.
 */
object TracePathMapBuilder {
    private class LocatedNode(val id: UUID, val latitude: Double, val longitude: Double)

    fun build(state: TracePathUiState, userLocation: LocationFix?, filter: MapFilterState): PlottedTracePath {
        val contacts = state.availableNodes
        val discovered = state.discoveredRepeaters
        val path = state.outboundPath

        val located = path.map { hop -> locate(hop, contacts, discovered, userLocation) }
        // A node listed twice keeps its last position, as in Swift.
        val pathLookup = mutableMapOf<UUID, Pair<Int, Boolean>>()
        located.forEachIndexed { index, node ->
            if (node != null) pathLookup[node.id] = (index + 1) to (index == path.lastIndex)
        }

        val pins = mutableListOf<TracePathMapPin>()
        for (contact in visibleContacts(contacts, pathLookup.keys, filter)) {
            val info = pathLookup[contact.id]
            pins += TracePathMapPin(contact.id, contact, contact.latitude, contact.longitude, contact.displayName, info?.first, info?.second == true)
        }
        val contactKeys = contacts.map { it.publicKey.asList() }.toSet()
        for (node in visibleDiscovered(discovered, contactKeys, pathLookup.keys, filter)) {
            val info = pathLookup[node.id]
            pins += TracePathMapPin(node.id, node, node.latitude, node.longitude, node.name, info?.first, info?.second == true)
        }

        val pathCoordinates = mutableListOf<GeoCoordinate>()
        val lines = mutableListOf<TracePathMapLine>()
        var previous = userLocation?.let { GeoCoordinate(it.latitude, it.longitude) }
        previous?.let(pathCoordinates::add)
        located.forEachIndexed { index, node ->
            if (node == null) return@forEachIndexed
            val coordinate = GeoCoordinate(node.latitude, node.longitude)
            previous?.let { from ->
                lines += TracePathMapLine("trace-$index", from.latitude, from.longitude, coordinate.latitude, coordinate.longitude, index, null)
            }
            pathCoordinates += coordinate
            previous = coordinate
        }

        val (gradedLines, badges) = applyResult(lines, state.result)
        return PlottedTracePath(pins, gradedLines, badges, pathCoordinates)
    }

    /**
     * What a tap on pin [pinId] does, or `null` when it isn't a pin. Tapping the last hop removes
     * it, a node off the path is appended, a middle hop is left alone.
     */
    fun tapAction(content: PlottedTracePath, pinId: UUID): TracePathPinAction? {
        val pin = content.pins.firstOrNull { it.id == pinId } ?: return null
        return when {
            pin.isLastHop -> TracePathPinAction.RemoveLast
            !pin.inPath -> TracePathPinAction.Add(pin.node)
            else -> TracePathPinAction.RejectMiddleHop
        }
    }

    /** Grades each line by the SNR its hop reported in a successful [result], with a midpoint badge. */
    private fun applyResult(lines: List<TracePathMapLine>, result: TraceResult?): Pair<List<TracePathMapLine>, List<TracePathMapBadge>> {
        if (result == null || !result.success) return lines to emptyList()
        val badges = mutableListOf<TracePathMapBadge>()
        val graded = lines.map { line ->
            // result.hops[0] is this device, so outbound hop i is hops[i + 1].
            val hop = result.hops.getOrNull(line.pathIndex + 1) ?: return@map line
            val distance = RFCalculator.distance(
                GeoCoordinate(line.fromLatitude, line.fromLongitude),
                GeoCoordinate(line.toLatitude, line.toLongitude),
            )
            val (latitude, longitude) = SnrLink.midpoint(line.fromLatitude, line.fromLongitude, line.toLatitude, line.toLongitude)
            badges += TracePathMapBadge("badge-${line.pathIndex + 1}", latitude, longitude, SnrLink.badgeText(distance, hop.snr))
            line.copy(quality = SNRQuality.of(hop.snr))
        }
        return graded to badges
    }

    /** Located contacts; under Favorites only favorites and path members. Ported from `visibleContactPins`. */
    private fun visibleContacts(contacts: List<ContactDto>, pathMemberIds: Set<UUID>, filter: MapFilterState): List<ContactDto> {
        val located = contacts.filter { it.isValidFix() }
        return if (filter.favoritesOnly) located.filter { it.isFavorite || it.id in pathMemberIds } else located
    }

    /**
     * Located discovered repeaters that aren't contacts, plus discovered path members, which stay
     * visible whatever the filter says. Ported from `visibleDiscoveredPins`.
     */
    private fun visibleDiscovered(
        discovered: List<DiscoveredNodeDto>,
        contactKeys: Set<List<Byte>>,
        pathMemberIds: Set<UUID>,
        filter: MapFilterState,
    ): List<DiscoveredNodeDto> {
        val pathMembers = discovered.filter { it.id in pathMemberIds && it.isValidFix() }
        if (!filter.effectiveShowDiscovered) return pathMembers
        val others = discovered.filter {
            it.nodeType == ContactType.REPEATER && it.isValidFix() && it.publicKey.asList() !in contactKeys && it.id !in pathMemberIds
        }
        return others + pathMembers
    }

    /**
     * The located node a hop stands for. A full key that matches a known node is final, even when
     * that node has no location: falling through to the hash prefix could pin the hop on a
     * different node that happens to share it. Otherwise contacts win over discovered nodes.
     * Ported from `findLocatedPathNode(for:)`.
     */
    private fun locate(hop: PathHop, contacts: List<ContactDto>, discovered: List<DiscoveredNodeDto>, userLocation: LocationFix?): LocatedNode? {
        hop.publicKey?.let { key ->
            val exactContact = contacts.firstOrNull { it.publicKey.contentEquals(key) }
            val exactDiscovered = discovered.firstOrNull { it.publicKey.contentEquals(key) }
            if (exactContact != null || exactDiscovered != null) {
                if (exactContact != null && exactContact.isValidFix()) return exactContact.located()
                if (exactDiscovered != null && exactDiscovered.isValidFix()) return exactDiscovered.located()
                return null
            }
        }
        RepeaterResolver.bestMatch(hop.hashBytes, contacts, userLocation)?.takeIf { it.isValidFix() }?.let { return it.located() }
        RepeaterResolver.bestMatch(hop.hashBytes, discovered, userLocation)?.takeIf { it.isValidFix() }?.let { return it.located() }
        return null
    }

    private fun ContactDto.located() = LocatedNode(id, latitude, longitude)

    private fun DiscoveredNodeDto.located() = LocatedNode(id, latitude, longitude)

    /** `hasLocation` only rules out (0, 0); MapLibre also needs the coordinate in range. */
    private fun RepeaterResolvable.isValidFix(): Boolean = hasLocation && latitude in -90.0..90.0 && longitude in -180.0..180.0
}
