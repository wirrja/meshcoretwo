// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.map.MapFilterState
import com.meshcoretwo.android.pathediting.NeighborNameResolver
import com.meshcoretwo.android.tools.LOSFormatters
import com.meshcoretwo.protocol.Neighbour
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.rendering.NodeNameMatchKind
import com.meshcoretwo.services.rendering.SNRQuality
import com.meshcoretwo.services.rf.GeoCoordinate
import com.meshcoretwo.services.rf.RFCalculator
import java.util.Locale
import kotlin.math.abs

/** A repeater or a located neighbor, plotted on the SNR map. */
enum class SnrMapPointRole { CENTER, NEIGHBOR }

data class SnrMapPoint(val id: String, val latitude: Double, val longitude: Double, val label: String, val role: SnrMapPointRole)

/** A link between the center repeater and one of its located neighbors, colored by [quality]. */
data class SnrMapLine(val id: String, val fromLatitude: Double, val fromLongitude: Double, val toLatitude: Double, val toLongitude: Double, val quality: SNRQuality)

/** Midpoint distance/SNR label for a [SnrMapLine]. */
data class SnrMapBadge(val id: String, val latitude: Double, val longitude: Double, val text: String)

/**
 * A neighbor that could not be placed reliably (ambiguous match, no location, an invalid
 * coordinate, or unresolved), carried with its resolved name and confidence for the no-location list.
 */
data class UnplottableNeighbor(val neighbor: Neighbour, val displayName: String, val matchKind: NodeNameMatchKind)

data class PlottedNeighbors(
    val points: List<SnrMapPoint>,
    val lines: List<SnrMapLine>,
    val badges: List<SnrMapBadge>,
    val unplottable: List<UnplottableNeighbor>,
)

/**
 * Builds the pins/lines/badges for a repeater's neighbor SNR map from data already in hand.
 * Ported from `NeighborSNRMapBuilder.swift`, trimmed to what this port's simpler MapLibre layer
 * needs: a pure function returning plain lat/lon data (mirroring
 * [com.meshcoretwo.android.map.MapPoint]) rather than MapKit `MKCoordinateRegion`/`CLLocationCoordinate2D`
 * types — the screen fits the camera itself from the plotted coordinates the same way
 * [com.meshcoretwo.android.tools.LineOfSightScreen]/`MapScreen.kt`'s controllers already do, so no
 * region is computed here. Also not ported: `stableID`'s SHA-256-derived `UUID` namespacing —
 * that exists only so MapKit's incremental annotation diffing keeps stable identities across
 * rebuilds; this port's GeoJSON source is replaced wholesale on every rebuild (same as every other
 * map screen here), so plain string ids suffice.
 */
object NeighborSnrMapBuilder {
    fun build(
        session: RemoteNodeSessionDto,
        neighbors: List<Neighbour>,
        contacts: List<ContactDto>,
        discoveredNodes: List<DiscoveredNodeDto>,
        userLocation: LocationFix?,
        filter: MapFilterState,
        keyDisplayByteCount: Int,
    ): PlottedNeighbors {
        val effectiveContacts = if (filter.favoritesOnly) contacts.filter { it.isFavorite } else contacts
        val effectiveDiscovered = if (filter.effectiveShowDiscovered) discoveredNodes else emptyList()

        val points = mutableListOf<SnrMapPoint>()
        val lines = mutableListOf<SnrMapLine>()
        val badges = mutableListOf<SnrMapBadge>()
        val unplottable = mutableListOf<UnplottableNeighbor>()

        val centerLatitude = session.latitude.takeIf { session.hasLocation }
        val centerLongitude = session.longitude.takeIf { session.hasLocation }
        val hasCenter = centerLatitude != null && centerLongitude != null
        if (hasCenter) {
            points += SnrMapPoint(
                id = "center-${session.publicKey.hexString}",
                latitude = centerLatitude!!,
                longitude = centerLongitude!!,
                label = session.name,
                role = SnrMapPointRole.CENTER,
            )
        }

        for (neighbor in neighbors) {
            val prefixHex = neighbor.publicKeyPrefix.hexString
            val resolved = NeighborNameResolver.resolveLocated(neighbor.publicKeyPrefix, effectiveContacts, effectiveDiscovered, userLocation)
            if (resolved == null) {
                unplottable += UnplottableNeighbor(
                    neighbor = neighbor,
                    displayName = NeighborNameResolver.fallbackName(neighbor.publicKeyPrefix, keyDisplayByteCount),
                    matchKind = NodeNameMatchKind.UNRESOLVED,
                )
                continue
            }

            // Only an exact identity match with a trustworthy coordinate is plotted; the validity
            // guard lives here because a DTO's `hasLocation` only checks non-(0,0) and would
            // otherwise admit an out-of-range point.
            val latitude = resolved.latitude
            val longitude = resolved.longitude
            if (resolved.matchKind != NodeNameMatchKind.EXACT || latitude == null || longitude == null || !isPlottable(latitude, longitude)) {
                unplottable += UnplottableNeighbor(neighbor, resolved.displayName, resolved.matchKind)
                continue
            }

            points += SnrMapPoint(
                id = "neighbor-$prefixHex",
                latitude = latitude,
                longitude = longitude,
                label = resolved.displayName,
                role = SnrMapPointRole.NEIGHBOR,
            )

            // A line and distance/SNR badge are defensible only when the center is also located;
            // without an anchor the neighbor pin still stands alone.
            if (!hasCenter) continue

            lines += SnrMapLine(
                id = "line-$prefixHex",
                fromLatitude = centerLatitude!!,
                fromLongitude = centerLongitude!!,
                toLatitude = latitude,
                toLongitude = longitude,
                quality = SNRQuality.of(neighbor.snr),
            )

            val distance = RFCalculator.distance(GeoCoordinate(centerLatitude, centerLongitude), GeoCoordinate(latitude, longitude))
            val midpoint = midpoint(centerLatitude, centerLongitude, latitude, longitude)
            badges += SnrMapBadge(
                id = "badge-$prefixHex",
                latitude = midpoint.first,
                longitude = midpoint.second,
                text = snrBadgeText(distance, neighbor.snr),
            )
        }

        return PlottedNeighbors(points, lines, badges, disambiguatingUnresolved(unplottable))
    }

    /** "120 m · -3.2 dB". */
    private fun snrBadgeText(distanceMeters: Double, snr: Double): String =
        "${LOSFormatters.formatDistance(distanceMeters)} · ${"%.1f".format(Locale.US, snr)} dB"

    /**
     * Distinct unresolved neighbors can share the clamped key prefix and look identical; colliding
     * titles widen to the full stored prefix.
     */
    private fun disambiguatingUnresolved(unplottable: List<UnplottableNeighbor>): List<UnplottableNeighbor> {
        val titleCounts = mutableMapOf<String, Int>()
        for (item in unplottable) if (item.matchKind == NodeNameMatchKind.UNRESOLVED) {
            titleCounts[item.displayName] = (titleCounts[item.displayName] ?: 0) + 1
        }
        if (titleCounts.none { it.value > 1 }) return unplottable
        return unplottable.map { item ->
            if (item.matchKind != NodeNameMatchKind.UNRESOLVED || (titleCounts[item.displayName] ?: 0) <= 1) {
                item
            } else {
                item.copy(displayName = item.neighbor.publicKeyPrefix.hexString.uppercase())
            }
        }
    }

    private fun isPlottable(latitude: Double, longitude: Double): Boolean = latitude in -90.0..90.0 && longitude in -180.0..180.0

    /**
     * Geographic midpoint of two coordinates, shifting one longitude by 360° before averaging when
     * the pair straddles the antimeridian so the badge lands between them rather than on the
     * opposite hemisphere.
     */
    private fun midpoint(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        var a = lon1
        var b = lon2
        if (abs(a - b) > 180) {
            if (a < b) a += 360 else b += 360
        }
        var midLongitude = (a + b) / 2
        if (midLongitude > 180) midLongitude -= 360
        return (lat1 + lat2) / 2 to midLongitude
    }
}
