// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.services.persistence.NeighborSnapshotEntry
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.TelemetrySnapshotEntry
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [telemetryChannelGroups], [neighborSnrCharts] and the data gates in
 * [TelemetryHistoryOverviewViewModel]'s companion — the non-Compose half of the telemetry history
 * screens. Ports the logic cases of `TelemetryHistoryOverviewViewModelTests.swift`; its loading
 * cases need a live `ConnectionManager` service graph and are not ported, the same split
 * [RadioMetricChartsBuilderTest] makes for the status history.
 */
class TelemetryChartsBuilderTest {
    private val baseTime: Instant = Instant.parse("2026-09-01T00:00:00Z")

    private fun snapshot(
        minutesIn: Long = 0,
        telemetry: List<TelemetrySnapshotEntry>? = null,
        neighbors: List<NeighborSnapshotEntry>? = null,
        batteryMillivolts: UShort? = null,
        sentDirect: UInt? = null,
        sentFlood: UInt? = null,
        receivedDirect: UInt? = null,
        receivedFlood: UInt? = null,
        directDuplicates: UInt? = null,
        floodDuplicates: UInt? = null,
    ) = NodeStatusSnapshotDto(
        id = UUID.randomUUID(),
        timestamp = baseTime.plus(minutesIn, ChronoUnit.MINUTES),
        nodePublicKey = ByteArray(32) { 0xAB.toByte() },
        batteryMillivolts = batteryMillivolts,
        lastSNR = null,
        lastRSSI = null,
        noiseFloor = null,
        uptimeSeconds = null,
        rxAirtimeSeconds = null,
        packetsSent = null,
        packetsReceived = null,
        receiveErrors = null,
        sentDirect = sentDirect,
        sentFlood = sentFlood,
        receivedDirect = receivedDirect,
        receivedFlood = receivedFlood,
        directDuplicates = directDuplicates,
        floodDuplicates = floodDuplicates,
        postedCount = null,
        postPushCount = null,
        neighborSnapshots = neighbors,
        telemetryEntries = telemetry,
        latitude = null,
        longitude = null,
        altitude = null,
    )

    private fun neighbor(vararg prefix: Int, snr: Double) =
        NeighborSnapshotEntry(publicKeyPrefix = prefix.map { it.toByte() }.toByteArray(), snr = snr, secondsAgo = 30)

    // MARK: - Channel groups

    @Test
    fun `snapshots without telemetry produce no channel groups`() {
        assertTrue(telemetryChannelGroups(listOf(snapshot(batteryMillivolts = 3800u)), ocvArray = emptyList()).isEmpty())
    }

    @Test
    fun `channel groups sort by channel, then voltage first, then alphabetically`() {
        // Ported from Swift's "channelGroups groups by channel and sorts by chartSortPriority then alphabetically".
        val groups = telemetryChannelGroups(
            listOf(
                snapshot(
                    telemetry = listOf(
                        TelemetrySnapshotEntry(channel = 2, type = "Humidity", value = 55.0),
                        TelemetrySnapshotEntry(channel = 0, type = "Voltage", value = 3.8),
                        TelemetrySnapshotEntry(channel = 0, type = "Temperature", value = 22.5),
                        TelemetrySnapshotEntry(channel = 2, type = "Voltage", value = 4.1),
                    ),
                ),
            ),
            ocvArray = emptyList(),
        )

        assertEquals(listOf(0, 2), groups.map { it.channel })
        assertEquals(listOf("Voltage", "Temperature"), groups[0].charts.map { it.title })
        assertEquals(listOf("Voltage", "Humidity"), groups[1].charts.map { it.title })
    }

    @Test
    fun `a chart gathers one point per snapshot in history order`() {
        val first = snapshot(minutesIn = 0, telemetry = listOf(TelemetrySnapshotEntry(1, "Temperature", 20.0)))
        val second = snapshot(minutesIn = 20, telemetry = listOf(TelemetrySnapshotEntry(1, "Temperature", 21.5)))

        val points = telemetryChannelGroups(listOf(first, second), ocvArray = emptyList()).single().charts.single().series.single().dataPoints

        assertEquals(listOf(20.0, 21.5), points.map { it.value })
        assertEquals(listOf(first.id, second.id), points.map { it.id })
        assertEquals(listOf(first.timestamp, second.timestamp), points.map { it.timestamp })
    }

    @Test
    fun `a known sensor type carries its unit and no fixed domain`() {
        val chart = telemetryChannelGroups(
            listOf(snapshot(telemetry = listOf(TelemetrySnapshotEntry(0, "Temperature", 22.5)))),
            ocvArray = listOf(3000, 4200),
        ).single().charts.single()

        assertEquals("°C", chart.unit)
        assertNull(chart.yAxisDomain)
    }

    @Test
    fun `a voltage chart spans the battery curve`() {
        val chart = telemetryChannelGroups(
            listOf(snapshot(telemetry = listOf(TelemetrySnapshotEntry(0, "Voltage", 3.8)))),
            ocvArray = listOf(3000, 4200),
        ).single().charts.single()

        assertEquals("V", chart.unit)
        val domain = chart.yAxisDomain
        assertNotNull(domain)
        // OCV 3.0-4.2 V, which already contains the 3.8 V reading, ±0.5 V.
        assertEquals(2.5, domain!!.start, 1e-9)
        assertEquals(4.7, domain.endInclusive, 1e-9)
    }

    @Test
    fun `an unknown type name still charts, titled raw and unitless`() {
        val chart = telemetryChannelGroups(
            listOf(snapshot(telemetry = listOf(TelemetrySnapshotEntry(0, "Radiation", 0.12)))),
            ocvArray = emptyList(),
        ).single().charts.single()

        assertEquals("Radiation", chart.title)
        assertEquals("", chart.unit)
        assertNull(chart.yAxisDomain)
    }

    @Test
    fun `unknown types sort alphabetically after voltage`() {
        val titles = telemetryChannelGroups(
            listOf(
                snapshot(
                    telemetry = listOf(
                        TelemetrySnapshotEntry(0, "radiation", 0.1),
                        TelemetrySnapshotEntry(0, "Humidity", 40.0),
                        TelemetrySnapshotEntry(0, "Voltage", 3.9),
                    ),
                ),
            ),
            ocvArray = emptyList(),
        ).single().charts.map { it.title }

        assertEquals(listOf("Voltage", "Humidity", "radiation"), titles)
    }

    // MARK: - Neighbor charts

    @Test
    fun `neighbor charts use the resolved name, fall back to uppercase hex, and sort by title`() {
        val charts = neighborSnrCharts(
            listOf(snapshot(neighbors = listOf(neighbor(0x0A, 0xFF, snr = 4.0), neighbor(0x01, 0x02, snr = 6.5)))),
        ) { prefix -> if (prefix[0] == 0x01.toByte()) "Tower" else null }

        assertEquals(listOf("0AFF", "Tower"), charts.map { it.title })
        assertTrue(charts.all { it.unit == "dB" })
    }

    @Test
    fun `a neighbor chart gathers its SNR across snapshots`() {
        val charts = neighborSnrCharts(
            listOf(
                snapshot(minutesIn = 0, neighbors = listOf(neighbor(0x01, snr = 6.5))),
                snapshot(minutesIn = 20),
                snapshot(minutesIn = 40, neighbors = listOf(neighbor(0x01, snr = 5.0), neighbor(0x02, snr = 1.0))),
            ),
        ) { null }

        assertEquals(listOf("01", "02"), charts.map { it.title })
        assertEquals(listOf(6.5, 5.0), charts[0].series.single().dataPoints.map { it.value })
    }

    @Test
    fun `neighbors resolving to the same name keep separate charts`() {
        val charts = neighborSnrCharts(
            listOf(snapshot(neighbors = listOf(neighbor(0x01, snr = 1.0), neighbor(0x02, snr = 2.0)))),
        ) { "Tower" }

        assertEquals(2, charts.size)
    }

    // MARK: - Data gates

    @Test
    fun `radio section surfaces for a snapshot carrying only packet-type counters`() {
        // Ported from Swift's "Radio section surfaces for a snapshot carrying only packet-type counters".
        val onlyCounters = snapshot(
            sentDirect = 100u,
            sentFlood = 200u,
            receivedDirect = 300u,
            receivedFlood = 400u,
            directDuplicates = 11u,
            floodDuplicates = 22u,
        )
        assertTrue(TelemetryHistoryOverviewViewModel.hasRadioData(listOf(onlyCounters)))
        assertFalse(TelemetryHistoryOverviewViewModel.hasRadioData(listOf(snapshot())))
    }

    @Test
    fun `telemetry data needs at least one non-empty entry list`() {
        assertFalse(TelemetryHistoryOverviewViewModel.hasTelemetryData(listOf(snapshot(), snapshot(telemetry = emptyList()))))
        assertTrue(
            TelemetryHistoryOverviewViewModel.hasTelemetryData(
                listOf(snapshot(), snapshot(telemetry = listOf(TelemetrySnapshotEntry(0, "Voltage", 3.8)))),
            ),
        )
    }

    @Test
    fun `neighbor data needs at least one non-empty neighbor list`() {
        assertFalse(TelemetryHistoryOverviewViewModel.hasNeighborData(listOf(snapshot(), snapshot(neighbors = emptyList()))))
        assertTrue(TelemetryHistoryOverviewViewModel.hasNeighborData(listOf(snapshot(neighbors = listOf(neighbor(0x01, snr = 6.5))))))
    }

    // MARK: - Single-neighbor chart

    @Test
    fun `a single-neighbor chart takes that neighbor's SNR from each snapshot that recorded it`() {
        val chart = neighborSnrChart(
            listOf(
                snapshot(minutesIn = 0, neighbors = listOf(neighbor(0x0A, 0xFF, snr = 6.5), neighbor(0x01, snr = 1.0))),
                snapshot(minutesIn = 20, neighbors = listOf(neighbor(0x01, snr = 2.0))),
                snapshot(minutesIn = 40, neighbors = listOf(neighbor(0x0A, 0xFF, snr = 4.0))),
            ),
            neighborPrefixHex = "0aff",
            name = "Tower",
        )

        assertEquals("Tower", chart.title)
        assertEquals("dB", chart.unit)
        assertEquals(listOf(6.5, 4.0), chart.series.single().dataPoints.map { it.value })
    }

    @Test
    fun `neighborPrefixHex renders zero-padded uppercase hex`() {
        assertEquals("0AFF01", neighborPrefixHex(byteArrayOf(0x0A, 0xFF.toByte(), 0x01)))
    }
}
