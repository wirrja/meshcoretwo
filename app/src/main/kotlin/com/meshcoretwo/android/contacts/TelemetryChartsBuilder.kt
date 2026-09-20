// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.ui.graphics.Color
import com.meshcoretwo.protocol.LPPSensorType
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto

/**
 * One telemetry channel's sensor charts. Ported from `ChannelGroup` (`ChannelGroup.swift`), with
 * each of its `TelemetryChartGroup`s already turned into the [MetricChartSpec] that Swift's
 * `TelemetryHistoryView.chartView(for:)` builds from it — the same "describe now, render later"
 * split [radioMetricCharts] uses.
 */
data class TelemetryChannelGroup(val channel: Int, val charts: List<MetricChartSpec>)

/** Stand-ins for the SwiftUI system colors `LPPSensorType+Chart.swift` assigns per sensor type. */
private val OrangeColor = Color(0xFFEF6C00)
private val RedColor = Color(0xFFC62828)
private val TealColor = Color(0xFF00897B)
private val PurpleColor = Color(0xFF8E24AA)
private val YellowColor = Color(0xFFF9A825)
private val MintColor = Color(0xFF00BFA5)
private val PinkColor = Color(0xFFD81B60)
private val BlueColor = Color(0xFF1E88E5)
private val GreenColor = Color(0xFF43A047)
private val IndigoColor = Color(0xFF3949AB)
private val CyanColor = Color(0xFF00ACC1)

/** Chart accent per sensor type. Ported from `LPPSensorType.chartColor`. */
private val LPPSensorType.chartColor: Color
    get() = when (this) {
        LPPSensorType.VOLTAGE, LPPSensorType.ENERGY -> OrangeColor
        LPPSensorType.TEMPERATURE -> RedColor
        LPPSensorType.HUMIDITY -> TealColor
        LPPSensorType.BAROMETER -> PurpleColor
        LPPSensorType.ILLUMINANCE -> YellowColor
        LPPSensorType.CURRENT -> MintColor
        LPPSensorType.POWER -> PinkColor
        LPPSensorType.FREQUENCY -> BlueColor
        LPPSensorType.ALTITUDE, LPPSensorType.DISTANCE -> GreenColor
        LPPSensorType.DIRECTION -> IndigoColor
        else -> CyanColor
    }

/** Lower sorts earlier; voltage leads each channel. Ported from `LPPSensorType.chartSortPriority`. */
private val LPPSensorType.chartSortPriority: Int
    get() = if (this == LPPSensorType.VOLTAGE) 0 else 1

/**
 * Groups every stored telemetry entry by channel, then by sensor type, into one chart per type.
 * Ported from `ChannelGroup.groups(from:)`: channels ascending, charts within a channel by
 * [chartSortPriority] then title. A type name no [LPPSensorType] matches (a newer firmware's sensor)
 * still gets a chart, titled with the raw name, unitless, in the default accent — as in Swift.
 *
 * Entries are stored under [com.meshcoretwo.services.remotenode.typeName], which spells every type
 * exactly as [LPPSensorType.displayName] does, so [LPPSensorType.fromName] recovers the type.
 *
 * Values are charted as stored, in the metric units the radio reports. Swift converts temperature,
 * pressure, altitude and distance to imperial units for non-metric locales
 * (`LPPSensorType.convertedValue`/`localizedUnitSymbol`); the port has no unit-system preference
 * anywhere yet (see `LPPDataPointDisplay.kt`), and the history must agree with the live telemetry
 * rows on the status screens.
 */
fun telemetryChannelGroups(snapshots: List<NodeStatusSnapshotDto>, ocvArray: List<Int>): List<TelemetryChannelGroup> {
    val pointsByChannel = sortedMapOf<Int, LinkedHashMap<String, MutableList<MetricDataPoint>>>()
    snapshots.forEach { snapshot ->
        snapshot.telemetryEntries.orEmpty().forEach { entry ->
            pointsByChannel.getOrPut(entry.channel) { LinkedHashMap() }
                .getOrPut(entry.type) { mutableListOf() }
                .add(MetricDataPoint(snapshot.id, snapshot.timestamp, entry.value))
        }
    }

    return pointsByChannel.map { (channel, pointsByType) ->
        val charts = pointsByType
            .map { (type, points) -> LPPSensorType.fromName(type) to telemetryChart(type, points, ocvArray) }
            .sortedWith(
                compareBy<Pair<LPPSensorType?, MetricChartSpec>> { (sensorType, _) -> sensorType?.chartSortPriority ?: 1 }
                    // Swift uses locale-aware `localizedStandardCompare`; a case-insensitive compare
                    // stands in, as in `RepeaterResolver`.
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { (_, chart) -> chart.title },
            )
            .map { (_, chart) -> chart }
        TelemetryChannelGroup(channel, charts)
    }
}

/** Ported from `TelemetryHistoryView.chartView(for:)`: voltage charts span the node's battery curve. */
private fun telemetryChart(type: String, points: List<MetricDataPoint>, ocvArray: List<Int>): MetricChartSpec {
    val sensorType = LPPSensorType.fromName(type)
    val title = sensorType?.displayName ?: type
    return MetricChartSpec(
        title = title,
        unit = sensorType?.unit ?: "",
        series = listOf(MetricSeries(title, sensorType?.chartColor ?: CyanColor, points)),
        yAxisDomain = if (sensorType == LPPSensorType.VOLTAGE) voltageChartDomain(ocvArray, points) else null,
    )
}

/**
 * One SNR chart per neighbor seen across [snapshots], titled with [resolveName]'s answer for its
 * public-key prefix or, failing that, the prefix as uppercase hex, and sorted by that title. Ported
 * from `TelemetryHistoryOverviewView.buildNeighborCharts(from:)`. Charts are keyed by prefix, so
 * two neighbors that resolve to the same name still get separate charts.
 */
fun neighborSnrCharts(snapshots: List<NodeStatusSnapshotDto>, resolveName: (ByteArray) -> String?): List<MetricChartSpec> {
    val pointsByPrefix = LinkedHashMap<String, Pair<String, MutableList<MetricDataPoint>>>()
    snapshots.forEach { snapshot ->
        snapshot.neighborSnapshots.orEmpty().forEach { neighbor ->
            val hex = neighborPrefixHex(neighbor.publicKeyPrefix)
            val (_, points) = pointsByPrefix.getOrPut(hex) { (resolveName(neighbor.publicKeyPrefix) ?: hex) to mutableListOf() }
            points.add(MetricDataPoint(snapshot.id, snapshot.timestamp, neighbor.snr))
        }
    }
    return pointsByPrefix.values
        .sortedBy { (name, _) -> name }
        .map { (name, points) -> MetricChartSpec(name, "dB", listOf(MetricSeries(name, BlueColor, points))) }
}

/** Uppercase hex of a neighbor's public-key prefix: the fallback chart title and the route key for [NeighborSnrChartScreen]. */
fun neighborPrefixHex(prefix: ByteArray): String = prefix.joinToString("") { "%02X".format(it) }

/**
 * A single neighbor's SNR chart: from each snapshot, the first neighbor entry whose prefix matches
 * [neighborPrefixHex] (compared case-insensitively). Snapshots that didn't record that neighbor add
 * no point. Ported from `NeighborSNRChartView`'s snapshot-to-point mapping.
 */
fun neighborSnrChart(snapshots: List<NodeStatusSnapshotDto>, neighborPrefixHex: String, name: String): MetricChartSpec {
    val points = snapshots.mapNotNull { snapshot ->
        snapshot.neighborSnapshots.orEmpty()
            .firstOrNull { neighborPrefixHex(it.publicKeyPrefix).equals(neighborPrefixHex, ignoreCase = true) }
            ?.let { MetricDataPoint(snapshot.id, snapshot.timestamp, it.snr) }
    }
    return MetricChartSpec(name, "dB", listOf(MetricSeries(name, BlueColor, points)))
}
