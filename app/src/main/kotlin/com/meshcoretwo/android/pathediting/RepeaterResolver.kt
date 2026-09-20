// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.RepeaterResolvable
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactPathHop
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.rendering.NodeNameMatchKind
import com.meshcoretwo.services.rendering.NodeNameResolution
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A node resolved from a hash-byte prefix, paired with how confident that match is. Ported from Swift's `ResolvedNode<T>`. */
data class ResolvedNode<T : RepeaterResolvable>(val node: T, val matchKind: NodeNameMatchKind)

/**
 * Resolves repeater hash-prefix collisions by proximity and recency. Ported from
 * `RepeaterResolver`. The `bestMatch` overloads are the ones [com.meshcoretwo.android.tools.TracePathViewModel]
 * calls (`resolveNode(for:)` there discards match confidence); [resolve] additionally reports
 * Swift's `NodeNameMatchKind` match-confidence, needed by [NeighborNameResolver]. The `resolve(for
 * hop: PathHop...)` overload stays unported — nothing resolves a path hop with confidence yet
 * (that's the still-deferred route-path display, `NodeRoutePathSection.swift`).
 *
 * Takes [LocationFix] (already used by [com.meshcoretwo.services.location.LocationProvider])
 * rather than `android.location.Location`, and computes distance with a plain-math haversine
 * formula rather than `Location.distanceBetween` — both purely so this stays a framework-free
 * `object` that plain JUnit can exercise directly (every `android.*` method throws
 * "not mocked" under a bare JVM unit test without Robolectric). Haversine's spherical-earth
 * approximation (vs. `CLLocation`'s/`Location`'s WGS84 ellipsoid) is within ~0.5% at any distance
 * relevant here — this only ever ranks candidates against each other, never displays a distance.
 */
object RepeaterResolver {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Prefix length at or above which a match is trusted as exact regardless of collisions. */
    const val EXACT_PREFIX_LENGTH = 6

    /** Matches using a [PathHop]: exact public-key match first, then hash-bytes fallback. */
    fun <T : RepeaterResolvable> bestMatch(hop: PathHop, nodes: List<T>, userLocation: LocationFix?): T? {
        hop.publicKey?.let { key -> nodes.firstOrNull { it.publicKey.contentEquals(key) } }?.let { return it }
        return bestMatch(hop.hashBytes, nodes, userLocation)
    }

    /**
     * Matches using a hash-byte prefix (1-4 bytes). Among every node whose public key starts with
     * [hashBytes], prefers (in order): closer to [userLocation], more recently advertised, more
     * recently modified/heard, then alphabetically by [RepeaterResolvable.resolvableName].
     */
    fun <T : RepeaterResolvable> bestMatch(hashBytes: ByteArray, nodes: List<T>, userLocation: LocationFix?): T? =
        resolve(hashBytes, nodes, userLocation)?.node

    /** Same match as [bestMatch], plus the confidence ([NodeNameMatchKind]) that match deserves. */
    fun <T : RepeaterResolvable> resolve(hashBytes: ByteArray, nodes: List<T>, userLocation: LocationFix?): ResolvedNode<T>? {
        if (hashBytes.isEmpty()) return null
        val prefixLen = hashBytes.size
        val candidates = nodes.filter { node ->
            node.publicKey.size >= prefixLen && node.publicKey.copyOfRange(0, prefixLen).contentEquals(hashBytes)
        }
        if (candidates.isEmpty()) return null

        val sorted = candidates.sortedWith { a, b ->
            val distanceA = distanceTo(userLocation, a)
            val distanceB = distanceTo(userLocation, b)
            when {
                // A node with a known distance always outranks one without, regardless of value.
                distanceA != null && distanceB != null && distanceA != distanceB -> distanceA.compareTo(distanceB)
                distanceA != null && distanceB == null -> -1
                distanceA == null && distanceB != null -> 1
                a.lastAdvertTimestamp != b.lastAdvertTimestamp -> b.lastAdvertTimestamp.compareTo(a.lastAdvertTimestamp)
                a.recencyValue != b.recencyValue -> b.recencyValue.compareTo(a.recencyValue)
                // Swift uses locale-aware `localizedStandardCompare`; this port has no natural-sort
                // infra elsewhere either, so a plain case-insensitive compare stands in.
                else -> a.resolvableName.compareTo(b.resolvableName, ignoreCase = true)
            }
        }
        val node = sorted.first()
        val matchingPublicKeys = candidates.map { it.publicKey.toList() }.toSet()
        val matchKind = if (prefixLen >= EXACT_PREFIX_LENGTH || matchingPublicKeys.size == 1) {
            NodeNameMatchKind.EXACT
        } else {
            NodeNameMatchKind.FALLBACK
        }
        return ResolvedNode(node, matchKind)
    }

    /** Great-circle distance in meters between [userLocation] and [node], or `null` if either lacks a location. */
    private fun distanceTo(userLocation: LocationFix?, node: RepeaterResolvable): Double? {
        if (userLocation == null || !node.hasLocation) return null
        return haversineMeters(userLocation.latitude, userLocation.longitude, node.latitude, node.longitude)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
}

/**
 * A neighbor resolution that also carries the resolved node's location, for the SNR map
 * ([com.meshcoretwo.android.contacts.NeighborSnrMapBuilder]) to place it. Ported from Swift's
 * `ResolvedNeighbor`.
 */
data class ResolvedNeighbor(
    val displayName: String,
    val matchKind: NodeNameMatchKind,
    val latitude: Double?,
    val longitude: Double?,
)

/**
 * Resolves a neighbor's public-key prefix to a display name, checked against contacts first and
 * discovered nodes second. Ported from `NeighborNameResolver.swift`, same source file as Swift's
 * `RepeaterResolver` — trimmed to the pieces this port needs: [resolve] and [fallbackName] for
 * [RepeaterStatusScreen][com.meshcoretwo.android.contacts.RepeaterStatusScreen],
 * [resolveLocated] for [com.meshcoretwo.android.contacts.NeighborSnrMapBuilder], and [resolvePath]
 * for [com.meshcoretwo.android.contacts.NodeRoutePathSection]/`NodeAuthScreen`'s route display, and
 * [resolveName] for [com.meshcoretwo.android.contacts.TelemetryHistoryOverviewViewModel]'s neighbor
 * charts. Not ported: `keyDisplayByteCount` (unused while nothing calls it).
 */
object NeighborNameResolver {
    const val MAXIMUM_KEY_DISPLAY_BYTE_COUNT = 3

    /** Just the display name of [resolve]'s match, or `null` when nothing matches. Ported from `resolveName(for:...)`. */
    fun resolveName(
        prefix: ByteArray,
        contacts: List<ContactDto>,
        discoveredNodes: List<DiscoveredNodeDto>,
        userLocation: LocationFix?,
    ): String? = resolve(prefix, contacts, discoveredNodes, userLocation)?.displayName

    /**
     * Resolves [prefix] against [contacts], then [discoveredNodes], refining the raw match
     * confidence: a fallback match is only reported as such when more than one node — across
     * *both* sources — actually shares [prefix], matching Swift's `matchKind(for:...)`.
     */
    fun resolve(
        prefix: ByteArray,
        contacts: List<ContactDto>,
        discoveredNodes: List<DiscoveredNodeDto>,
        userLocation: LocationFix?,
    ): NodeNameResolution? {
        val resolved = resolveLocated(prefix, contacts, discoveredNodes, userLocation) ?: return null
        return NodeNameResolution(displayName = resolved.displayName, matchKind = resolved.matchKind)
    }

    /** Same match as [resolve], plus the resolved node's coordinate (`null` when it has none). */
    fun resolveLocated(
        prefix: ByteArray,
        contacts: List<ContactDto>,
        discoveredNodes: List<DiscoveredNodeDto>,
        userLocation: LocationFix?,
    ): ResolvedNeighbor? {
        val contactMatch = RepeaterResolver.resolve(prefix, contacts, userLocation)
        if (contactMatch != null) return located(contactMatch.node, contactMatch.matchKind, prefix, contacts, discoveredNodes)

        val discoveredMatch = RepeaterResolver.resolve(prefix, discoveredNodes, userLocation)
        if (discoveredMatch != null) return located(discoveredMatch.node, discoveredMatch.matchKind, prefix, contacts, discoveredNodes)

        return null
    }

    private fun located(
        node: RepeaterResolvable,
        resolvedMatchKind: NodeNameMatchKind,
        prefix: ByteArray,
        contacts: List<ContactDto>,
        discoveredNodes: List<DiscoveredNodeDto>,
    ) = ResolvedNeighbor(
        displayName = node.resolvableName,
        matchKind = refineMatchKind(prefix, resolvedMatchKind, contacts, discoveredNodes),
        latitude = if (node.hasLocation) node.latitude else null,
        longitude = if (node.hasLocation) node.longitude else null,
    )

    private fun refineMatchKind(
        prefix: ByteArray,
        resolvedMatchKind: NodeNameMatchKind,
        contacts: List<ContactDto>,
        discoveredNodes: List<DiscoveredNodeDto>,
    ): NodeNameMatchKind {
        if (resolvedMatchKind == NodeNameMatchKind.UNRESOLVED || prefix.size >= RepeaterResolver.EXACT_PREFIX_LENGTH) {
            return resolvedMatchKind
        }
        val matchingKeys = (contacts.asSequence().map { it.publicKey } + discoveredNodes.asSequence().map { it.publicKey })
            .filter { it.size >= prefix.size && it.copyOfRange(0, prefix.size).contentEquals(prefix) }
            .map { it.toList() }
            .toSet()
        return if (matchingKeys.size > 1) NodeNameMatchKind.FALLBACK else NodeNameMatchKind.EXACT
    }

    /** Uppercase hex of the leading [byteCount] bytes of [prefix], clamped to [MAXIMUM_KEY_DISPLAY_BYTE_COUNT]. */
    fun fallbackName(prefix: ByteArray, byteCount: Int): String {
        val n = byteCount.coerceIn(0, MAXIMUM_KEY_DISPLAY_BYTE_COUNT).coerceAtMost(prefix.size)
        return prefix.copyOfRange(0, n).joinToString("") { "%02X".format(it) }
    }

    /** Placeholder shown for a path hop no repeater matches. Ported from Swift's `pathHopUnknown` localized string. */
    const val PATH_HOP_UNKNOWN = "<unknown>"

    /**
     * Resolves every hop of a node's stored path ([ContactDto.pathHops]) to a repeater name,
     * falling back to [PATH_HOP_UNKNOWN] when no repeater matches a hop. Only repeaters can relay,
     * so [contacts]/[discoveredNodes] are narrowed to repeaters before matching, matching the login
     * screen's path display. Ported from `NeighborNameResolver.resolvePath`.
     */
    fun resolvePath(
        hops: List<ContactPathHop>,
        contacts: List<ContactDto>,
        discoveredNodes: List<DiscoveredNodeDto>,
        userLocation: LocationFix?,
    ): List<ResolvedPathHop> {
        val repeaters = contacts.filter { it.type == ContactType.REPEATER }
        val discoveredRepeaters = discoveredNodes.filter { it.nodeType == ContactType.REPEATER }

        return hops.mapIndexed { index, hop ->
            val resolution = resolve(hop.data, repeaters, discoveredRepeaters, userLocation)
                ?: NodeNameResolution(displayName = PATH_HOP_UNKNOWN, matchKind = NodeNameMatchKind.UNRESOLVED)
            ResolvedPathHop(id = index, hex = hop.hex, resolution = resolution)
        }
    }
}

/**
 * A single routing hop (hash bytes shown as hex) resolved to a repeater name, ready to display.
 * Ported from `ResolvedPathHop` (`RepeaterResolver.swift`).
 */
data class ResolvedPathHop(val id: Int, val hex: String, val resolution: NodeNameResolution)
