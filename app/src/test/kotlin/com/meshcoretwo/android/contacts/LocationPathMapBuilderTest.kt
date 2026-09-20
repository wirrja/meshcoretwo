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
 * Covers [LocationPathMapBuilder]. Ports the fixture-driven cases of
 * `LocationPathMapBuilderTests.swift`; not ported are the recency-bucket grading cases (this port
 * has no per-dot sprite ramp, see the builder's class doc) and the seeded-simulator-track cases
 * (no `MockDataProvider` equivalent in this port).
 */
class LocationPathMapBuilderTest {
    /** A single base so offsets produce exact gaps, unlike calling `Instant.now()` per fixture. */
    private val base: Instant = Instant.parse("2026-09-01T00:00:00Z")

    private fun snapshot(offsetMinutes: Long, lat: Double?, lon: Double?, altitude: Double? = null) = NodeStatusSnapshotDto(
        id = UUID.randomUUID(),
        timestamp = base.plus(offsetMinutes, ChronoUnit.MINUTES),
        nodePublicKey = ByteArray(32) { 0xDD.toByte() },
        batteryMillivolts = null,
        lastSNR = null,
        lastRSSI = null,
        noiseFloor = null,
        uptimeSeconds = null,
        rxAirtimeSeconds = null,
        packetsSent = null,
        packetsReceived = null,
        receiveErrors = null,
        sentDirect = null,
        sentFlood = null,
        receivedDirect = null,
        receivedFlood = null,
        directDuplicates = null,
        floodDuplicates = null,
        postedCount = null,
        postPushCount = null,
        neighborSnapshots = null,
        telemetryEntries = null,
        latitude = lat,
        longitude = lon,
        altitude = altitude,
    )

    @Test
    fun `two valid fixes yield a trail segment, a dot, and the hero`() {
        val built = LocationPathMapBuilder.build(
            listOf(snapshot(-120, 37.0, -122.0), snapshot(-60, 37.1, -122.1)),
        )
        assertEquals(1, built.lines.size)
        assertEquals(2, built.lines.first().coordinates.size)
        assertEquals(false, built.points.first().isLatest)
        assertEquals(true, built.points.last().isLatest)
    }

    @Test
    fun `a single valid fix yields a hero pin and no line`() {
        val built = LocationPathMapBuilder.build(listOf(snapshot(-60, 37.0, -122.0)))
        assertTrue(built.lines.isEmpty())
        assertEquals(1, built.points.size)
        assertTrue(built.points.first().isLatest)
    }

    @Test
    fun `snapshots without a valid fix are excluded`() {
        val built = LocationPathMapBuilder.build(
            listOf(snapshot(-180, null, null), snapshot(-120, 0.0, 0.0), snapshot(-60, 37.0, -122.0)),
        )
        assertTrue(built.lines.isEmpty())
        assertEquals(1, built.points.size)
    }

    @Test
    fun `empty input yields no path`() {
        val built = LocationPathMapBuilder.build(emptyList())
        assertTrue(built.points.isEmpty())
        assertTrue(built.lines.isEmpty())
        assertTrue(built.reports.isEmpty())
    }

    @Test
    fun `trail preserves ascending input order`() {
        val built = LocationPathMapBuilder.build(
            listOf(snapshot(-30, 37.0, -122.0), snapshot(-20, 38.0, -123.0), snapshot(-10, 39.0, -124.0)),
        )
        assertEquals(1, built.lines.size)
        assertEquals(37.0, built.lines.first().coordinates.first().first, 1e-9)
        assertEquals(39.0, built.lines.first().coordinates.last().first, 1e-9)
    }

    // MARK: - Gap-break segmentation

    @Test
    fun `a long silence splits the trail into two segments`() {
        // Two 10-min-apart runs separated by a 140-min gap (greater than the 60-min threshold).
        val built = LocationPathMapBuilder.build(
            listOf(
                snapshot(-200, 37.0, -122.0),
                snapshot(-190, 37.1, -122.0),
                snapshot(-50, 38.0, -123.0),
                snapshot(-40, 38.1, -123.0),
            ),
        )
        assertEquals(2, built.lines.size)
        assertTrue(built.lines.all { it.coordinates.size == 2 })
        assertEquals(built.lines.map { it.id }.toSet().size, built.lines.size)
    }

    @Test
    fun `fixes within the connected interval stay one segment`() {
        val built = LocationPathMapBuilder.build(
            (0 until 5).map { i -> snapshot(-60 + i * 15L, 37.0 + i * 0.01, -122.0) },
        )
        assertEquals(1, built.lines.size)
        assertEquals(5, built.lines.first().coordinates.size)
    }

    @Test
    fun `a lone fix on the far side of a gap contributes no segment`() {
        val built = LocationPathMapBuilder.build(
            listOf(snapshot(-200, 37.0, -122.0), snapshot(-190, 37.1, -122.0), snapshot(-10, 38.0, -123.0)),
        )
        assertEquals(1, built.lines.size)
        assertEquals(2, built.lines.first().coordinates.size)
    }

    // MARK: - Report side table

    @Test
    fun `every pin maps to a report carrying its timestamp and altitude`() {
        val snapshots = listOf(
            snapshot(-30, 37.0, -122.0, altitude = 10.0),
            snapshot(-15, 37.1, -122.1, altitude = null),
            snapshot(-1, 37.2, -122.2, altitude = 42.0),
        )
        val built = LocationPathMapBuilder.build(snapshots)
        assertEquals(built.points.size, built.reports.size)
        built.points.forEach { point -> assertTrue(built.reports.containsKey(point.id)) }

        val reportedIds = built.reports.values.map { it.id }.toSet()
        assertEquals(snapshots.map { it.id }.toSet(), reportedIds)

        val heroId = built.points.last().id
        assertEquals(42.0, built.reports.getValue(heroId).altitude)
        assertEquals(snapshots.last().id, built.reports.getValue(heroId).id)
    }

    // MARK: - Decimation

    @Test
    fun `intermediate pins are decimated under a wide set`() {
        val snapshots = (0 until 200).map { i -> snapshot(-2000 + i * 10L, 37.0 + i * 0.001, -122.0) }
        val built = LocationPathMapBuilder.build(snapshots)
        // 198 interior fixes decimated into a 58-pin budget (ceiling stride 4) plus the two
        // endpoints yields exactly 52; pinned so endpoints-only or over-cap both fail.
        assertEquals(52, built.points.size)
        assertTrue(built.points.size <= LocationPathMapBuilder.maxPins)
        // 10-min spacing keeps every fix connected, so the trail is one segment of all 200.
        assertEquals(200, built.lines.first().coordinates.size)
    }

    @Test
    fun `decimation off keeps a pin for every fix`() {
        val snapshots = (0 until 200).map { i -> snapshot(-2000 + i * 10L, 37.0 + i * 0.001, -122.0) }
        val built = LocationPathMapBuilder.build(snapshots, decimatePins = false)
        assertEquals(200, built.points.size)
        assertEquals(200, built.reports.size)
    }

    @Test
    fun `only the latest fix is the hero, every earlier fix is a dot`() {
        val snapshots = (0 until 5).map { i -> snapshot(-50 + i * 10L, 37.0 + i * 0.01, -122.0) }
        val built = LocationPathMapBuilder.build(snapshots, decimatePins = false)
        assertTrue(built.points.dropLast(1).all { !it.isLatest })
        assertTrue(built.points.last().isLatest)
    }

    // MARK: - latestFix

    @Test
    fun `latestFix returns the last valid fix after trailing invalid snapshots`() {
        val latest = LocationPathMapBuilder.latestFix(
            listOf(snapshot(-180, 37.0, -122.0), snapshot(-120, 38.0, -123.0), snapshot(-60, null, null)),
        )
        assertEquals(38.0 to -123.0, latest)
    }

    @Test
    fun `latestFix returns null when no snapshot has a valid fix`() {
        assertNull(LocationPathMapBuilder.latestFix(listOf(snapshot(-120, null, null), snapshot(-60, 0.0, 0.0))))
    }
}
