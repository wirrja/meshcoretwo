// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.annotation.StringRes
import com.meshcoretwo.android.R
import androidx.compose.ui.graphics.Color
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto

/**
 * The radio-metric history charts built from a node's stored snapshots — a port of
 * `RadioMetricCharts.swift`, kept as a pure builder (no Compose) so the chart-selection rules are
 * unit-testable, the same split [NeighborSnrMapBuilder] uses for the neighbor map.
 *
 * Where Swift hands each chart to a host-supplied `chartContainer`/`packetSection` view builder,
 * this returns the three groups and lets [NodeStatusHistoryScreen] wrap them: [radio] one card per
 * chart, [packets] under a shared "Packets" header (as in Swift's `PacketChartsGroup`), [posts] the
 * room-only counters. Charts with no data are skipped, so a repeater's snapshots produce no
 * post-counter charts and a room's produce no receive-error chart.
 */
data class RadioMetricCharts(
    val radio: List<MetricChartSpec>,
    val packets: List<MetricChartSpec>,
    val posts: List<MetricChartSpec>,
) {
    val isEmpty: Boolean get() = radio.isEmpty() && packets.isEmpty() && posts.isEmpty()
}

/** Stand-ins for the SwiftUI system colors (`.mint`/`.blue`/`.purple`/`.indigo`/`.orange`/`.red`/`.cyan`). */
private val BatteryColor = Color(0xFF00BFA5)
private val SnrColor = Color(0xFF1E88E5)
private val RssiColor = Color(0xFF8E24AA)
private val NoiseFloorColor = Color(0xFF3949AB)
private val DirectColor = Color(0xFF1E88E5)
private val FloodColor = Color(0xFFEF6C00)
private val ReceiveErrorsColor = Color(0xFFC62828)
private val PostsReceivedColor = Color(0xFF8E24AA)
private val PostsPushedColor = Color(0xFF00ACC1)

fun radioMetricCharts(snapshots: List<NodeStatusSnapshotDto>, ocvArray: List<Int>): RadioMetricCharts {
    val batteryPoints = snapshots.mapNotNull { snapshot ->
        snapshot.batteryMillivolts?.let { MetricDataPoint(snapshot.id, snapshot.timestamp, it.toDouble() / 1000.0) }
    }
    val radio = listOfNotNull(
        singleSeriesChart("Battery", "V", BatteryColor, batteryPoints, voltageChartDomain(ocvArray, batteryPoints), R.string.chart_battery),
        singleSeriesChart("SNR", "dB", SnrColor, snapshots.mapNotNull { s -> s.lastSNR?.let { MetricDataPoint(s.id, s.timestamp, it) } }),
        singleSeriesChart(
            "RSSI",
            "dBm",
            RssiColor,
            snapshots.mapNotNull { s -> s.lastRSSI?.let { MetricDataPoint(s.id, s.timestamp, it.toDouble()) } },
        ),
        singleSeriesChart(
            "Noise Floor",
            "dBm",
            NoiseFloorColor,
            snapshots.mapNotNull { s -> s.noiseFloor?.let { MetricDataPoint(s.id, s.timestamp, it.toDouble()) } },
        ),
    )

    val sentDirect = counterPoints(snapshots) { it.sentDirect }
    val sentFlood = counterPoints(snapshots) { it.sentFlood }
    val receivedDirect = counterPoints(snapshots) { it.receivedDirect }
    val receivedFlood = counterPoints(snapshots) { it.receivedFlood }
    val directDuplicates = counterPoints(snapshots) { it.directDuplicates }
    val floodDuplicates = counterPoints(snapshots) { it.floodDuplicates }
    val receiveErrors = counterPoints(snapshots) { it.receiveErrors }

    // Every packet chart shares one Y-axis domain spanning all series, so a glance shows which
    // counter is climbing fastest (as in Swift).
    val packetDomain = sharedMetricDomain(
        listOf(sentDirect, sentFlood, receivedDirect, receivedFlood, directDuplicates, floodDuplicates, receiveErrors),
    )
    val packets = listOfNotNull(
        directFloodChart("Sent", sentDirect, sentFlood, packetDomain, R.string.chart_sent),
        directFloodChart("Received", receivedDirect, receivedFlood, packetDomain, R.string.chart_received),
        directFloodChart("Duplicates", directDuplicates, floodDuplicates, packetDomain, R.string.chart_duplicates),
        singleSeriesChart("Errors", "", ReceiveErrorsColor, receiveErrors, packetDomain, R.string.chart_errors),
    )

    val posts = listOfNotNull(
        singleSeriesChart(
            "Posts Received",
            "",
            PostsReceivedColor,
            snapshots.mapNotNull { s -> s.postedCount?.let { MetricDataPoint(s.id, s.timestamp, it.toDouble()) } },
            titleRes = R.string.chart_posts_received,
        ),
        singleSeriesChart(
            "Posts Pushed",
            "",
            PostsPushedColor,
            snapshots.mapNotNull { s -> s.postPushCount?.let { MetricDataPoint(s.id, s.timestamp, it.toDouble()) } },
            titleRes = R.string.chart_posts_pushed,
        ),
    )

    return RadioMetricCharts(radio = radio, packets = packets, posts = posts)
}

private fun counterPoints(snapshots: List<NodeStatusSnapshotDto>, counter: (NodeStatusSnapshotDto) -> UInt?): List<MetricDataPoint> =
    snapshots.mapNotNull { snapshot -> counter(snapshot)?.let { MetricDataPoint(snapshot.id, snapshot.timestamp, it.toDouble()) } }

private fun singleSeriesChart(
    title: String,
    unit: String,
    color: Color,
    dataPoints: List<MetricDataPoint>,
    yAxisDomain: ClosedFloatingPointRange<Double>? = null,
    @StringRes titleRes: Int? = null,
): MetricChartSpec? {
    if (dataPoints.isEmpty()) return null
    return MetricChartSpec(title, unit, listOf(MetricSeries(title, color, dataPoints)), yAxisDomain, titleRes)
}

/** An overlaid Direct/Flood chart, or null when neither series carries data. Empty series are dropped. */
private fun directFloodChart(
    title: String,
    direct: List<MetricDataPoint>,
    flood: List<MetricDataPoint>,
    yAxisDomain: ClosedFloatingPointRange<Double>?,
    @StringRes titleRes: Int? = null,
): MetricChartSpec? {
    val series = listOf(
        MetricSeries("Direct", DirectColor, direct),
        MetricSeries("Flood", FloodColor, flood),
    ).filter { it.dataPoints.isNotEmpty() }
    if (series.isEmpty()) return null
    return MetricChartSpec(title, "", series, yAxisDomain, titleRes)
}
