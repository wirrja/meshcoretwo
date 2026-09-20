// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [radioMetricCharts], [sharedMetricDomain], [voltageChartDomain], [nearestMetricPoint] and
 * [MetricChartSpec]'s derived properties — the whole non-Compose half of the history charts
 * (`RadioMetricCharts.swift`/`MetricChartView.swift`). The drawing itself needs an instrumented
 * host, the same split [NeighborSnrMapBuilderTest] uses for the neighbor map.
 */
class RadioMetricChartsBuilderTest {
    private val baseTime: Instant = Instant.parse("2026-09-01T00:00:00Z")

    private fun snapshot(
        minutesIn: Long = 0,
        batteryMillivolts: UShort? = null,
        lastSNR: Double? = null,
        lastRSSI: Short? = null,
        noiseFloor: Short? = null,
        sentDirect: UInt? = null,
        sentFlood: UInt? = null,
        receivedDirect: UInt? = null,
        receivedFlood: UInt? = null,
        directDuplicates: UInt? = null,
        floodDuplicates: UInt? = null,
        receiveErrors: UInt? = null,
        postedCount: UShort? = null,
        postPushCount: UShort? = null,
    ) = NodeStatusSnapshotDto(
        id = UUID.randomUUID(),
        timestamp = baseTime.plus(minutesIn, ChronoUnit.MINUTES),
        nodePublicKey = ByteArray(6),
        batteryMillivolts = batteryMillivolts,
        lastSNR = lastSNR,
        lastRSSI = lastRSSI,
        noiseFloor = noiseFloor,
        uptimeSeconds = null,
        rxAirtimeSeconds = null,
        packetsSent = null,
        packetsReceived = null,
        receiveErrors = receiveErrors,
        sentDirect = sentDirect,
        sentFlood = sentFlood,
        receivedDirect = receivedDirect,
        receivedFlood = receivedFlood,
        directDuplicates = directDuplicates,
        floodDuplicates = floodDuplicates,
        postedCount = postedCount,
        postPushCount = postPushCount,
        neighborSnapshots = null,
        telemetryEntries = null,
        latitude = null,
        longitude = null,
        altitude = null,
    )

    private fun point(value: Double, minutesIn: Long = 0) =
        MetricDataPoint(UUID.randomUUID(), baseTime.plus(minutesIn, ChronoUnit.MINUTES), value)

    @Test
    fun `no snapshots produce no charts at all`() {
        assertTrue(radioMetricCharts(emptyList(), ocvArray = emptyList()).isEmpty)
    }

    @Test
    fun `a metric with no captured values gets no chart`() {
        val charts = radioMetricCharts(listOf(snapshot(lastSNR = 5.0)), ocvArray = emptyList())

        assertEquals(listOf("SNR"), charts.radio.map { it.title })
        assertTrue(charts.packets.isEmpty())
        assertTrue(charts.posts.isEmpty())
    }

    @Test
    fun `battery values are plotted in volts`() {
        val charts = radioMetricCharts(listOf(snapshot(batteryMillivolts = 3700u)), ocvArray = emptyList())

        val battery = charts.radio.single { it.title == "Battery" }
        assertEquals(3.7, battery.series.single().dataPoints.single().value, 1e-9)
    }

    @Test
    fun `packet charts overlay a Direct and Flood series`() {
        val charts = radioMetricCharts(listOf(snapshot(sentDirect = 4u, sentFlood = 6u)), ocvArray = emptyList())

        val sent = charts.packets.single { it.title == "Sent" }
        assertEquals(listOf("Direct", "Flood"), sent.series.map { it.name })
        assertTrue(sent.isMultiSeries)
    }

    @Test
    fun `a packet chart drops the series that carries no data`() {
        val charts = radioMetricCharts(listOf(snapshot(sentDirect = 4u)), ocvArray = emptyList())

        val sent = charts.packets.single { it.title == "Sent" }
        assertEquals(listOf("Direct"), sent.series.map { it.name })
    }

    @Test
    fun `every packet chart shares one Y-axis domain spanning all counters`() {
        val snapshots = listOf(snapshot(sentDirect = 4u, receivedFlood = 200u, receiveErrors = 1u))

        val charts = radioMetricCharts(snapshots, ocvArray = emptyList())

        val domains = charts.packets.map { it.yAxisDomain }
        assertTrue(domains.all { it == 0.0..210.0 })
    }

    @Test
    fun `room-only post counters become their own charts`() {
        val charts = radioMetricCharts(listOf(snapshot(postedCount = 3u, postPushCount = 2u)), ocvArray = emptyList())

        assertEquals(listOf("Posts Received", "Posts Pushed"), charts.posts.map { it.title })
    }

    @Test
    fun `sharedMetricDomain is null without a positive maximum`() {
        assertNull(sharedMetricDomain(emptyList()))
        assertNull(sharedMetricDomain(listOf(listOf(point(0.0)))))
    }

    @Test
    fun `sharedMetricDomain spans zero to the maximum plus five percent`() {
        val domain = sharedMetricDomain(listOf(listOf(point(10.0)), listOf(point(100.0))))!!

        assertEquals(0.0, domain.start, 1e-9)
        assertEquals(105.0, domain.endInclusive, 1e-9)
    }

    @Test
    fun `voltageChartDomain is null without an OCV curve`() {
        assertNull(voltageChartDomain(emptyList(), listOf(point(3.7))))
    }

    @Test
    fun `voltageChartDomain buffers the OCV range`() {
        val domain = voltageChartDomain(listOf(4200, 3000), dataPoints = emptyList())!!

        assertEquals(2.5, domain.start, 1e-9)
        assertEquals(4.7, domain.endInclusive, 1e-9)
    }

    @Test
    fun `voltageChartDomain widens so data outliers are never clipped`() {
        val domain = voltageChartDomain(listOf(4200, 3000), dataPoints = listOf(point(5.0)))!!

        assertEquals(5.5, domain.endInclusive, 1e-9)
    }

    @Test
    fun `voltageChartDomain never goes below zero volts`() {
        val domain = voltageChartDomain(listOf(400, 300), dataPoints = emptyList())!!

        assertEquals(0.0, domain.start, 1e-9)
    }

    @Test
    fun `nearestMetricPoint snaps onto the closest sample`() {
        val points = listOf(point(1.0, minutesIn = 0), point(2.0, minutesIn = 60))

        val nearest = nearestMetricPoint(points, baseTime.plus(50, ChronoUnit.MINUTES))

        assertEquals(2.0, nearest!!.value, 1e-9)
    }

    @Test
    fun `nearestMetricPoint is null without samples`() {
        assertNull(nearestMetricPoint(emptyList(), baseTime))
    }

    @Test
    fun `a chart needs two points in one series to draw a line`() {
        val single = MetricChartSpec("Sent", "", listOf(MetricSeries("Direct", DirectTestColor, listOf(point(4.0)))))
        val paired = MetricChartSpec(
            "Sent",
            "",
            listOf(MetricSeries("Direct", DirectTestColor, listOf(point(4.0), point(5.0, minutesIn = 15)))),
        )

        assertTrue(!single.hasEnoughData)
        assertTrue(paired.hasEnoughData)
    }

    @Test
    fun `the empty state sums the first value of every drawn series`() {
        val spec = MetricChartSpec(
            "Sent",
            "",
            listOf(
                MetricSeries("Direct", DirectTestColor, listOf(point(4.0))),
                MetricSeries("Flood", DirectTestColor, listOf(point(6.0))),
                MetricSeries("Unused", DirectTestColor, emptyList()),
            ),
        )

        assertEquals(10.0, spec.emptyStateValue!!, 1e-9)
        assertEquals(listOf("Direct", "Flood"), spec.drawnSeries.map { it.name })
    }

    @Test
    fun `the empty state has no value when nothing was captured`() {
        assertNull(MetricChartSpec("Sent", "", listOf(MetricSeries("Direct", DirectTestColor, emptyList()))).emptyStateValue)
    }

    private companion object {
        val DirectTestColor = androidx.compose.ui.graphics.Color(0xFF1E88E5)
    }
}
