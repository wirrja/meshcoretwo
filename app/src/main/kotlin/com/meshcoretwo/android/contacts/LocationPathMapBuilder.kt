// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.remotenode.validCoordinate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A plotted fix. [isLatest] is the hero pin (the most recent fix); every other fix is a plain dot. */
data class LocationMapPoint(val id: String, val latitude: Double, val longitude: Double, val isLatest: Boolean)

/** One trail segment: consecutive fixes close enough in time to be worth joining with a line. */
data class LocationMapLine(val id: String, val coordinates: List<Pair<Double, Double>>)

/** A plotted fix's report detail, surfaced in the tap callout. [id] is the source snapshot's id. */
data class LocationReport(val id: UUID, val timestamp: Instant, val altitude: Double?)

data class PlottedLocationPath(
    val points: List<LocationMapPoint>,
    /** Time-ordered trail split into segments at each long silence; empty for a single fix. */
    val lines: List<LocationMapLine>,
    /** Pin id → its report, for the tap callout. */
    val reports: Map<String, LocationReport>,
)

/**
 * Turns time-filtered snapshots into the pins and trail for the location history map. Pure: a
 * function of the snapshots passed in. Ported from `LocationPathMapBuilder.swift`, trimmed to what
 * this port's simpler MapLibre layer needs — plain lat/lon data (mirroring
 * [com.meshcoretwo.android.contacts.NeighborSnrMapBuilder]'s trim of the same MapKit types) instead
 * of a generic `MapPoint`/`MapLine`, and string ids instead of Swift's SHA-256-derived `UUID`
 * namespacing (that exists only so MapKit's incremental annotation diffing keeps stable identities;
 * this port's GeoJSON source is replaced wholesale on every rebuild).
 *
 * Not ported: the per-dot recency-graded sprite ramp (`PinSpriteRenderer`'s bucketed pin images) —
 * this port has no sprite renderer, so every non-hero fix renders as the same plain dot style
 * instead of fading from cool to hot with recency. A future Phase 6 (visual design) pass can revisit
 * this once the port has a sprite/marker system worth grading.
 *
 * Precondition: [snapshots] are in ascending timestamp order (the fetch and the history time-range
 * filter both preserve it), so the builder never re-sorts.
 */
object LocationPathMapBuilder {
    /** Cap on plotted pins for the inline preview, where dense sprites are noise. The full-screen map opts out (`decimatePins = false`) so every listed report is a tappable pin. The trail always uses every fix; only pins are decimated. */
    const val maxPins = 60

    /** Reports farther apart in time than this are not joined by a trail segment: a node silent this long didn't travel a straight line between the two fixes, so bridging them would draw a route that never happened. ~4x the nominal cadence. */
    private val maxConnectedInterval: Duration = Duration.ofHours(1)

    private data class Fix(val snapshot: NodeStatusSnapshotDto, val latitude: Double, val longitude: Double)

    fun build(snapshots: List<NodeStatusSnapshotDto>, decimatePins: Boolean = true): PlottedLocationPath {
        val fixes = snapshots.mapNotNull { snapshot ->
            snapshot.validCoordinate?.let { (lat, lon) -> Fix(snapshot, lat, lon) }
        }
        if (fixes.isEmpty()) return PlottedLocationPath(emptyList(), emptyList(), emptyMap())

        val reports = mutableMapOf<String, LocationReport>()
        fun makePin(fix: Fix, isLatest: Boolean): LocationMapPoint {
            val point = LocationMapPoint("location-${fix.snapshot.id}", fix.latitude, fix.longitude, isLatest)
            reports[point.id] = LocationReport(fix.snapshot.id, fix.snapshot.timestamp, fix.snapshot.altitude)
            return point
        }

        // One fix: a lone hero pin, no degenerate one-length trail.
        if (fixes.size < 2) {
            return PlottedLocationPath(listOf(makePin(fixes[0], isLatest = true)), emptyList(), reports)
        }

        val points = pins(fixes, decimate = decimatePins, makePin = ::makePin)
        return PlottedLocationPath(points, segments(fixes), reports)
    }

    /** The most recent valid fix. Snapshots are ascending, so the last valid one is the latest fix. */
    fun latestFix(snapshots: List<NodeStatusSnapshotDto>): Pair<Double, Double>? =
        snapshots.asReversed().firstNotNullOfOrNull { it.validCoordinate }

    /**
     * The inline preview path for [LocationHistorySection]: either the whole path (pins plus trail)
     * or just the latest fix as a lone hero pin, with no trail. Ported from
     * `LocationHistorySection.rebuild()`/`singleFixPath(at:)`.
     */
    fun preview(snapshots: List<NodeStatusSnapshotDto>, showsFullPath: Boolean): PlottedLocationPath {
        if (!showsFullPath) {
            val fix = latestFix(snapshots) ?: return PlottedLocationPath(emptyList(), emptyList(), emptyMap())
            return PlottedLocationPath(listOf(LocationMapPoint("location-fix-latest", fix.first, fix.second, isLatest = true)), emptyList(), emptyMap())
        }
        return build(snapshots)
    }

    /**
     * Every fix but the latest is a dot; only the latest is the hero. Decimated by stride for the
     * inline preview; kept whole for the full-screen map so every report stays tappable.
     */
    private fun pins(fixes: List<Fix>, decimate: Boolean, makePin: (Fix, Boolean) -> LocationMapPoint): List<LocationMapPoint> {
        val dotFixes = mutableListOf(fixes[0])
        val interior = fixes.subList(1, fixes.size - 1)
        if (interior.isNotEmpty()) {
            val step = if (decimate) {
                // Reserve two slots for the first dot and the hero pin appended separately. Ceiling
                // division so the interior count never exceeds the budget: a floor stride can
                // undercount and spill total pins past maxPins.
                val interiorBudget = maxPins - 2
                maxOf(1, (interior.size + interiorBudget - 1) / interiorBudget)
            } else {
                1
            }
            var index = 0
            while (index < interior.size) {
                dotFixes += interior[index]
                index += step
            }
        }

        val points = dotFixes.map { fix -> makePin(fix, false) }.toMutableList()
        points += makePin(fixes.last(), true)
        return points
    }

    /**
     * Splits the ascending fixes into trail segments, breaking wherever two consecutive reports are
     * more than [maxConnectedInterval] apart. A run of a single fix contributes no segment.
     */
    private fun segments(fixes: List<Fix>): List<LocationMapLine> {
        val lines = mutableListOf<LocationMapLine>()
        var run = mutableListOf(fixes[0].latitude to fixes[0].longitude)

        fun closeRun() {
            if (run.size >= 2) lines += LocationMapLine("location-trail-${lines.size}", run.toList())
        }

        for (index in 1 until fixes.size) {
            val gap = Duration.between(fixes[index - 1].snapshot.timestamp, fixes[index].snapshot.timestamp)
            if (gap > maxConnectedInterval) {
                closeRun()
                run = mutableListOf(fixes[index].latitude to fixes[index].longitude)
            } else {
                run += fixes[index].latitude to fixes[index].longitude
            }
        }
        closeRun()
        return lines
    }
}
