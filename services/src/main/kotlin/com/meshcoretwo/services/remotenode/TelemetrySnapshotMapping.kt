// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.LPPDataPoint
import com.meshcoretwo.protocol.LPPValue
import com.meshcoretwo.services.persistence.NodeLocationFix
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.TelemetrySnapshotEntry

/**
 * Maps decoded LPP telemetry data points to snapshot-persistable entries — numeric channels only
 * (float/integer), matching Swift's `NodeStatusViewModel.handleTelemetryResponse`'s `compactMap`.
 * Shared by `RoomStatusViewModel`/`RepeaterStatusViewModel`, whose telemetry handling is otherwise
 * identical (unlike status handling, which takes role-specific metrics — see
 * [RemoteNodeRetry]'s doc for the same shared-vs-per-role split).
 */
fun telemetrySnapshotEntries(dataPoints: List<LPPDataPoint>): List<TelemetrySnapshotEntry> =
    dataPoints.mapNotNull { dp ->
        val numericValue = when (val value = dp.value) {
            is LPPValue.Float -> value.value
            is LPPValue.Integer -> value.value.toDouble()
            else -> null
        } ?: return@mapNotNull null
        TelemetrySnapshotEntry(channel = dp.channel.toInt(), type = dp.typeName, value = numericValue)
    }

/**
 * Plausible altitude in meters, from below the lowest dry land to above jet cruising altitude.
 * Readings outside it are noise and dropped; sea level (0) is inside and kept.
 */
private val PLAUSIBLE_ALTITUDE_METERS = -500.0..10_000.0

/**
 * Whether a coordinate is a plottable fix: in range, and not the (0,0) "null island" a node without
 * a GPS lock reports. Ported from `CLLocationCoordinate2D.isValidFix`.
 */
fun isValidLocationFix(latitude: Double, longitude: Double): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0)

/**
 * The primary fix for a telemetry reading: the first GPS data point, kept only when it is a valid
 * fix. Only that first point is considered — an invalid one yields null even if a later channel
 * carries a good fix, exactly as in Swift. Altitude rides along when plausible and never gates the
 * fix. Ported from `NodeLocationFix.primaryFix(from:)`; lives here rather than on [NodeLocationFix]
 * to keep the persistence model free of LPP types.
 */
fun primaryLocationFix(dataPoints: List<LPPDataPoint>): NodeLocationFix? {
    val gps = dataPoints.firstNotNullOfOrNull { it.value as? LPPValue.Gps } ?: return null
    if (!isValidLocationFix(gps.latitude, gps.longitude)) return null
    return NodeLocationFix(gps.latitude, gps.longitude, gps.altitude.takeIf { it in PLAUSIBLE_ALTITUDE_METERS })
}

/**
 * The snapshot's stored fix, or null when it never recorded one or recorded an invalid one (out of
 * range, or "null island"). Ported from `NodeStatusSnapshotDTO.validCoordinate`; lives here rather
 * than as a property on the DTO for the same reason [isValidLocationFix] does — the persistence
 * model stays free of anything beyond storage concerns.
 */
val NodeStatusSnapshotDto.validCoordinate: Pair<Double, Double>?
    get() {
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        return (lat to lon).takeIf { isValidLocationFix(lat, lon) }
    }

/** What one telemetry reading contributes to a snapshot. [telemetry] is null rather than empty when it has no numeric entries. */
data class TelemetrySnapshotCapture(
    val telemetry: List<TelemetrySnapshotEntry>?,
    val location: NodeLocationFix?,
)

/**
 * The snapshot capture for a telemetry reading, or null when it carries neither numeric entries nor
 * a fix (nothing to record). Ported from the capture half of Swift's
 * `NodeStatusViewModel.handleTelemetryResponse`, shared by both status view models.
 */
fun telemetrySnapshotCapture(dataPoints: List<LPPDataPoint>): TelemetrySnapshotCapture? {
    val entries = telemetrySnapshotEntries(dataPoints)
    val location = primaryLocationFix(dataPoints)
    if (entries.isEmpty() && location == null) return null
    return TelemetrySnapshotCapture(telemetry = entries.ifEmpty { null }, location = location)
}
