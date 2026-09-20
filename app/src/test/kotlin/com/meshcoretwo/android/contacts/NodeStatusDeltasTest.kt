// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [NodeStatusDeltas] — the status deltas both node status screens show against the previous
 * stored snapshot. Same fixture-builder pattern as [NodeStatusDisplayTest]/
 * [RepeaterStatusViewModelCompanionTest]; the composables in the same file need an instrumented
 * test host, so only the pure delta/formatting half is covered here.
 */
class NodeStatusDeltasTest {
    private fun status(
        battery: Int = 3700,
        noiseFloor: Int = -100,
        lastRSSI: Int = -80,
        lastSNR: Double = 5.0,
    ) = StatusResponse(
        publicKeyPrefix = ByteArray(6),
        battery = battery,
        txQueueLength = 0,
        noiseFloor = noiseFloor,
        lastRSSI = lastRSSI,
        packetsReceived = 1u,
        packetsSent = 2u,
        airtime = 30u,
        uptime = 3661u,
        sentFlood = 5u,
        sentDirect = 6u,
        receivedFlood = 7u,
        receivedDirect = 8u,
        fullEvents = 0,
        lastSNR = lastSNR,
        directDuplicates = 1,
        floodDuplicates = 2,
        rxAirtime = 15u,
    )

    private fun snapshot(
        timestamp: Instant = Instant.EPOCH,
        batteryMillivolts: UShort? = 3600u,
        lastSNR: Double? = 4.0,
        lastRSSI: Short? = -90,
        noiseFloor: Short? = -95,
    ) = NodeStatusSnapshotDto(
        id = UUID.randomUUID(),
        timestamp = timestamp,
        nodePublicKey = ByteArray(6),
        batteryMillivolts = batteryMillivolts,
        lastSNR = lastSNR,
        lastRSSI = lastRSSI,
        noiseFloor = noiseFloor,
        uptimeSeconds = 3000u,
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
        latitude = null,
        longitude = null,
        altitude = null,
    )

    @Test
    fun `deltas are null without a baseline snapshot`() {
        val current = status()
        assertNull(NodeStatusDeltas.batteryDeltaMillivolts(current, null))
        assertNull(NodeStatusDeltas.snrDelta(current, null))
        assertNull(NodeStatusDeltas.rssiDelta(current, null))
        assertNull(NodeStatusDeltas.noiseFloorDelta(current, null))
    }

    @Test
    fun `deltas are null when the baseline carries no reading for the metric`() {
        val current = status()
        val blank = snapshot(batteryMillivolts = null, lastSNR = null, lastRSSI = null, noiseFloor = null)
        assertNull(NodeStatusDeltas.batteryDeltaMillivolts(current, blank))
        assertNull(NodeStatusDeltas.snrDelta(current, blank))
        assertNull(NodeStatusDeltas.rssiDelta(current, blank))
        assertNull(NodeStatusDeltas.noiseFloorDelta(current, blank))
    }

    @Test
    fun `deltas subtract the baseline from the current reading`() {
        val current = status(battery = 3700, lastSNR = 5.0, lastRSSI = -80, noiseFloor = -100)
        val baseline = snapshot(batteryMillivolts = 3600u, lastSNR = 4.0, lastRSSI = -90, noiseFloor = -95)

        assertEquals(100, NodeStatusDeltas.batteryDeltaMillivolts(current, baseline))
        assertEquals(1.0, NodeStatusDeltas.snrDelta(current, baseline)!!, 1e-9)
        assertEquals(10, NodeStatusDeltas.rssiDelta(current, baseline))
        assertEquals(-5, NodeStatusDeltas.noiseFloorDelta(current, baseline))
    }

    @Test
    fun `previousSnapshotTimestamp is null without a baseline snapshot`() {
        assertNull(NodeStatusDeltas.previousSnapshotTimestamp(null))
    }

    @Test
    fun `previousSnapshotTimestamp under an hour counts minutes`() {
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val baseline = snapshot(timestamp = now.minus(12, ChronoUnit.MINUTES))

        assertEquals(UiText.of(R.string.status_since_last_min, 12), NodeStatusDeltas.previousSnapshotTimestamp(baseline, now))
    }

    @Test
    fun `previousSnapshotTimestamp under a day counts hours`() {
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val baseline = snapshot(timestamp = now.minus(3, ChronoUnit.HOURS))

        assertEquals(UiText.of(R.string.status_since_last_hour, 3), NodeStatusDeltas.previousSnapshotTimestamp(baseline, now))
    }

    @Test
    fun `previousSnapshotTimestamp a day or more shows the date`() {
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val baseline = snapshot(timestamp = now.minus(3, ChronoUnit.DAYS))

        assertEquals(R.string.status_since_date, (NodeStatusDeltas.previousSnapshotTimestamp(baseline, now) as UiText.Res).id)
    }

    @Test
    fun `previousSnapshotTimestamp clamps a baseline timestamped in the future`() {
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val baseline = snapshot(timestamp = now.plus(5, ChronoUnit.MINUTES))

        assertEquals(UiText.of(R.string.status_since_last_min, 0), NodeStatusDeltas.previousSnapshotTimestamp(baseline, now))
    }

    @Test
    fun `deltaMagnitude drops the sign and appends the unit`() {
        assertEquals("0.005 V", NodeStatusDeltas.deltaMagnitude(-0.005, " V", fractionDigits = 3))
        assertEquals("10 dBm", NodeStatusDeltas.deltaMagnitude(10.0, " dBm", fractionDigits = 0))
    }

    @Test
    fun `isImprovement follows the metric's good direction`() {
        assertTrue(NodeStatusDeltas.isImprovement(1.0, higherIsBetter = true))
        assertFalse(NodeStatusDeltas.isImprovement(-1.0, higherIsBetter = true))
        assertTrue(NodeStatusDeltas.isImprovement(-1.0, higherIsBetter = false))
        assertFalse(NodeStatusDeltas.isImprovement(1.0, higherIsBetter = false))
    }

    @Test
    fun `isNegligible covers changes under a hundredth`() {
        assertTrue(NodeStatusDeltas.isNegligible(0.005))
        assertFalse(NodeStatusDeltas.isNegligible(0.02))
    }
}
