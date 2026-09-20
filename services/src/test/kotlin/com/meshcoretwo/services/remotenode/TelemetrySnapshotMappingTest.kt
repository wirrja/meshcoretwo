// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.LPPDataPoint
import com.meshcoretwo.protocol.LPPSensorType
import com.meshcoretwo.protocol.LPPValue
import com.meshcoretwo.services.persistence.NodeLocationFix
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.TelemetrySnapshotEntry
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [primaryLocationFix] (the first four cases are ported from `NodeLocationFixTests.swift`)
 * and [telemetrySnapshotCapture], the shared capture both status view models record, plus
 * [validCoordinate].
 */
class TelemetrySnapshotMappingTest {
    private fun gps(channel: Int, latitude: Double, longitude: Double, altitude: Double = 0.0) =
        LPPDataPoint(channel.toUByte(), LPPSensorType.GPS, LPPValue.Gps(latitude, longitude, altitude))

    private fun temperature(channel: Int, value: Double) =
        LPPDataPoint(channel.toUByte(), LPPSensorType.TEMPERATURE, LPPValue.Float(value))

    @Test
    fun `primary fix is the first valid GPS point`() {
        val fix = primaryLocationFix(listOf(temperature(1, 21.5), gps(2, 37.7749, -122.4194)))

        assertEquals(37.7749, fix?.latitude)
        assertEquals(-122.4194, fix?.longitude)
    }

    @Test
    fun `a zero-zero fix is dropped`() {
        assertNull(primaryLocationFix(listOf(gps(1, 0.0, 0.0))))
    }

    @Test
    fun `an out-of-range fix is dropped`() {
        assertNull(primaryLocationFix(listOf(gps(1, 999.0, 999.0))))
    }

    @Test
    fun `no GPS point yields no fix`() {
        assertNull(primaryLocationFix(listOf(temperature(1, 21.5))))
    }

    @Test
    fun `only the first GPS point is considered, as in Swift`() {
        assertNull(primaryLocationFix(listOf(gps(1, 0.0, 0.0), gps(2, 37.7749, -122.4194))))
    }

    @Test
    fun `an implausible altitude is dropped without dropping the fix`() {
        val fix = primaryLocationFix(listOf(gps(1, 37.7749, -122.4194, altitude = 50_000.0)))

        assertEquals(NodeLocationFix(37.7749, -122.4194, altitude = null), fix)
    }

    @Test
    fun `sea-level altitude is kept`() {
        assertEquals(0.0, primaryLocationFix(listOf(gps(1, 37.7749, -122.4194, altitude = 0.0)))?.altitude)
    }

    @Test
    fun `capture is null with neither numeric entries nor a fix`() {
        assertNull(telemetrySnapshotCapture(emptyList()))
        assertNull(telemetrySnapshotCapture(listOf(LPPDataPoint(1u, LPPSensorType.PRESENCE, LPPValue.Digital(true)))))
    }

    @Test
    fun `a GPS-only reading captures the fix with no telemetry list`() {
        val capture = telemetrySnapshotCapture(listOf(gps(1, 37.7749, -122.4194)))

        assertNull(capture?.telemetry)
        assertEquals(NodeLocationFix(37.7749, -122.4194, altitude = 0.0), capture?.location)
    }

    @Test
    fun `entries and fix are captured together`() {
        val capture = telemetrySnapshotCapture(listOf(temperature(1, 21.5), gps(2, 37.7749, -122.4194)))

        assertEquals(listOf(TelemetrySnapshotEntry(channel = 1, type = "Temperature", value = 21.5)), capture?.telemetry)
        assertEquals(37.7749, capture?.location?.latitude)
    }

    // MARK: - validCoordinate

    private fun snapshot(latitude: Double?, longitude: Double?) = NodeStatusSnapshotDto(
        id = UUID.randomUUID(),
        timestamp = Instant.parse("2026-09-01T00:00:00Z"),
        nodePublicKey = ByteArray(32) { 0xAB.toByte() },
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
        latitude = latitude,
        longitude = longitude,
        altitude = null,
    )

    @Test
    fun `validCoordinate is the stored pair when in range`() {
        assertEquals(37.7749 to -122.4194, snapshot(37.7749, -122.4194).validCoordinate)
    }

    @Test
    fun `validCoordinate is null with no stored fix`() {
        assertNull(snapshot(null, null).validCoordinate)
    }

    @Test
    fun `validCoordinate is null for null island`() {
        assertNull(snapshot(0.0, 0.0).validCoordinate)
    }

    @Test
    fun `validCoordinate is null out of range`() {
        assertNull(snapshot(999.0, 999.0).validCoordinate)
    }
}
