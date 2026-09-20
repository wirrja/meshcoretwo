// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.android.pathediting.NeighborNameResolver
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactPathHop
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.rendering.NodeNameMatchKind
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.rf.GeoCoordinate
import com.meshcoretwo.services.rf.RFCalculator

/** A message-path pin: the originating sender, a resolved intermediate repeater hop, or the receiving device. */
enum class MessagePathMapPointRole { SENDER, HOP, RECEIVER }

data class MessagePathMapPoint(val id: String, val latitude: Double, val longitude: Double, val label: String, val role: MessagePathMapPointRole)

data class PlottedMessagePath(
    val points: List<MessagePathMapPoint>,
    /** Polyline through every located node in path order (sender → hops → receiver); fewer than two points yields an empty line. */
    val lineCoordinates: List<Pair<Double, Double>>,
    /** Great-circle length of [lineCoordinates]; `null` until at least two nodes resolve to a coordinate. */
    val totalDistanceMeters: Double?,
    /** Hops in the message's path, placed or not. */
    val hopCount: Int = 0,
    /** Whether any hop couldn't be placed (unknown, no location, or ambiguous), so the distance skips it. */
    val isDistanceIncomplete: Boolean = false,
) {
    /**
     * Whether the map is worth showing: endpoint pins alone aren't a path, and a single placed hop
     * with skipped hops is a shortcut rather than the path. Ported from `CanvasModel.showsPathMap`
     * (upstream `1e17adb4`).
     */
    val showsPathMap: Boolean
        get() {
            if (hopCount == 0) return points.isNotEmpty()
            val placedHops = points.count { it.role == MessagePathMapPointRole.HOP }
            if (placedHops == 0) return false
            if (placedHops == 1 && isDistanceIncomplete) return false
            return true
        }
}

/**
 * Builds the pins/line for the "Path map" screen (`MessagePathMapScreen.kt`) from data already in
 * hand. Ported from `MessagePathMapView.swift`'s `buildLocatedNodes`/`totalPathDistance`, trimmed
 * to plain lat/lon data the same way [com.meshcoretwo.android.contacts.NeighborSnrMapBuilder]/
 * `LocationPathMapBuilder` already trim their Swift counterparts — a pure function returning
 * whatever this port's simpler MapLibre layer needs, not MapKit types. Also not ported: per-hop
 * `hopIndex`/`badgeText` as separate fields (folded into [MessagePathMapPoint.label] instead,
 * since — like those two builders — this port has no sprite renderer to place a numbered badge on
 * top of a pin).
 *
 * Only an *exact* match is plotted for a repeater hop — matching Swift's own reasoning here (a
 * 1-byte path hash can't tell collisions apart, so a fallback match would put the line through the
 * wrong node). Unlike [com.meshcoretwo.android.contacts.NeighborSnrMapBuilder], hop resolution goes
 * through [NeighborNameResolver.resolveLocated] rather than raw `RepeaterResolver.resolve` (Swift's
 * choice) — this port already has that helper doing the same contacts-then-discovered lookup with
 * the same exactness gate, so reusing it avoids a second resolution path for one screen.
 */
object MessagePathMapBuilder {
    /**
     * [hops] defaults to the message's own path. An *extra* arrival of the same message (a later
     * flood copy that took a different route — see [MessagePathArrival.kt]) passes its own hops
     * instead: the endpoints are the same sender and receiver, only the middle differs, so one
     * builder serves every arrival rather than a second near-copy per route.
     */
    fun build(
        message: MessageDto,
        contacts: List<ContactDto>,
        discoveredNodes: List<DiscoveredNodeDto>,
        selfDevice: DeviceDto?,
        receiverName: String,
        userLocation: LocationFix?,
        hops: List<ContactPathHop> = message.pathHops,
    ): PlottedMessagePath {
        val points = mutableListOf<MessagePathMapPoint>()

        message.locatedSender(contacts)?.let { sender ->
            points += MessagePathMapPoint(
                id = "sender-${sender.id}",
                latitude = sender.latitude,
                longitude = sender.longitude,
                label = sender.displayName,
                role = MessagePathMapPointRole.SENDER,
            )
        }

        val repeaters = contacts.filter { it.type == ContactType.REPEATER }
        val discoveredRepeaters = discoveredNodes.filter { it.nodeType == ContactType.REPEATER }
        val seenCoordinates = mutableSetOf<Pair<Double, Double>>()
        var isDistanceIncomplete = false
        for ((index, hop) in hops.withIndex()) {
            val resolved = NeighborNameResolver.resolveLocated(hop.data, repeaters, discoveredRepeaters, userLocation)
            val latitude = resolved?.latitude
            val longitude = resolved?.longitude
            if (resolved == null || resolved.matchKind != NodeNameMatchKind.EXACT || latitude == null || longitude == null) {
                isDistanceIncomplete = true
                continue
            }
            if (!seenCoordinates.add(latitude to longitude)) continue

            points += MessagePathMapPoint(
                id = "hop-$index-${hop.hex}",
                latitude = latitude,
                longitude = longitude,
                label = "Hop ${index + 1}: ${resolved.displayName}",
                role = MessagePathMapPointRole.HOP,
            )
        }

        val receiverCoordinate = when {
            selfDevice != null && selfDevice.hasLocation -> selfDevice.latitude to selfDevice.longitude
            userLocation != null -> userLocation.latitude to userLocation.longitude
            else -> null
        }
        receiverCoordinate?.let { (latitude, longitude) ->
            points += MessagePathMapPoint(
                id = "receiver",
                latitude = latitude,
                longitude = longitude,
                label = receiverName,
                role = MessagePathMapPointRole.RECEIVER,
            )
        }

        val lineCoordinates = points.map { it.latitude to it.longitude }.takeIf { it.size >= 2 } ?: emptyList()
        val totalDistanceMeters = lineCoordinates.takeIf { it.size >= 2 }?.let(::totalDistance)
        return PlottedMessagePath(points, lineCoordinates, totalDistanceMeters, hopCount = hops.size, isDistanceIncomplete = isDistanceIncomplete)
    }

    private fun totalDistance(coordinates: List<Pair<Double, Double>>): Double =
        coordinates.zipWithNext { (lat1, lon1), (lat2, lon2) ->
            RFCalculator.distance(GeoCoordinate(lat1, lon1), GeoCoordinate(lat2, lon2))
        }.sum()
}

/** Same "not 0,0" check [com.meshcoretwo.android.settings.SettingsScreen] already inlines for a [DeviceDto] — no shared extension exists yet for one caller apiece. */
private val DeviceDto.hasLocation: Boolean get() = latitude != 0.0 || longitude != 0.0
