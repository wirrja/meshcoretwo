// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.protocol.Neighbour
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.services.persistence.NeighborSnapshotEntry
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [RepeaterStatusViewModel]'s repeater-only display formatters — role-independent ones
 * moved to `NodeStatusDisplayTest` once this became a second consumer (see [RoomStatusViewModel]'s
 * class doc).
 */
class RepeaterStatusViewModelCompanionTest {
    private fun key(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

    private fun neighbor(prefix: ByteArray, snr: Double = 5.0, secondsAgo: Int = 0) =
        Neighbour(publicKeyPrefix = prefix, secondsAgo = secondsAgo, snr = snr)

    private fun snapshotEntry(prefix: ByteArray, snr: Double = 5.0, secondsAgo: Int = 0) =
        NeighborSnapshotEntry(publicKeyPrefix = prefix, snr = snr, secondsAgo = secondsAgo)

    private fun snapshot(neighborSnapshots: List<NeighborSnapshotEntry>?) = NodeStatusSnapshotDto(
        id = UUID.randomUUID(),
        timestamp = Instant.EPOCH,
        nodePublicKey = ByteArray(6),
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
        neighborSnapshots = neighborSnapshots,
        telemetryEntries = null,
        latitude = null,
        longitude = null,
        altitude = null,
    )

    private fun status(receiveErrors: UInt = 0u) = StatusResponse(
        publicKeyPrefix = ByteArray(6),
        battery = 3700,
        txQueueLength = 0,
        noiseFloor = -100,
        lastRSSI = -80,
        packetsReceived = 1u,
        packetsSent = 2u,
        airtime = 30u,
        uptime = 3661u,
        sentFlood = 5u,
        sentDirect = 6u,
        receivedFlood = 7u,
        receivedDirect = 8u,
        fullEvents = 0,
        lastSNR = 5.0,
        directDuplicates = 1,
        floodDuplicates = 2,
        rxAirtime = 15u,
        receiveErrors = receiveErrors,
    )

    @Test
    fun `receiveErrorsDisplay is null when status is null`() {
        assertNull(RepeaterStatusViewModel.receiveErrorsDisplay(null))
    }

    @Test
    fun `receiveErrorsDisplay is null when count is zero`() {
        assertNull(RepeaterStatusViewModel.receiveErrorsDisplay(status(receiveErrors = 0u)))
    }

    @Test
    fun `receiveErrorsDisplay shows a positive count`() {
        assertEquals("4", RepeaterStatusViewModel.receiveErrorsDisplay(status(receiveErrors = 4u)))
    }

    @Test
    fun `neighborKeyDisplay shows uppercase hex of the first two bytes`() {
        val prefix = byteArrayOf(0x0A, 0xBC.toByte(), 0x01, 0x02)
        assertEquals("0ABC", RepeaterStatusViewModel.neighborKeyDisplay(prefix))
    }

    @Test
    fun `lastSeenDisplay under a minute shows seconds`() {
        assertEquals("45s ago", RepeaterStatusViewModel.lastSeenDisplay(45))
    }

    @Test
    fun `lastSeenDisplay under an hour shows minutes`() {
        assertEquals("5m ago", RepeaterStatusViewModel.lastSeenDisplay(300))
    }

    @Test
    fun `lastSeenDisplay an hour or more shows hours`() {
        assertEquals("2h ago", RepeaterStatusViewModel.lastSeenDisplay(7200))
    }

    @Test
    fun `neighborSNRDisplay formats one decimal`() {
        assertEquals("5.5 dB", RepeaterStatusViewModel.neighborSNRDisplay(5.5))
    }

    @Test
    fun `isNewNeighbor is false with no baseline snapshot yet`() {
        assertFalse(RepeaterStatusViewModel.isNewNeighbor(key(0xAA), previousNeighborSnapshot = null, seenNeighborPrefixes = emptySet()))
    }

    @Test
    fun `isNewNeighbor is false when the prefix was already seen`() {
        val baseline = snapshot(listOf(snapshotEntry(key(0xAA))))
        assertFalse(RepeaterStatusViewModel.isNewNeighbor(key(0xAA), baseline, seenNeighborPrefixes = setOf("aa")))
    }

    @Test
    fun `isNewNeighbor is true when a baseline exists but this prefix wasn't in it`() {
        val baseline = snapshot(listOf(snapshotEntry(key(0xAA))))
        assertTrue(RepeaterStatusViewModel.isNewNeighbor(key(0xBB), baseline, seenNeighborPrefixes = setOf("aa")))
    }

    @Test
    fun `disappearedNeighbors is empty without a baseline snapshot`() {
        assertTrue(RepeaterStatusViewModel.disappearedNeighbors(emptyList(), previousNeighborSnapshot = null).isEmpty())
    }

    @Test
    fun `disappearedNeighbors returns baseline entries missing from the current list`() {
        val baseline = snapshot(listOf(snapshotEntry(key(0xAA)), snapshotEntry(key(0xBB))))
        val current = listOf(neighbor(key(0xAA)))

        val disappeared = RepeaterStatusViewModel.disappearedNeighbors(current, baseline)

        assertEquals(listOf(snapshotEntry(key(0xBB))), disappeared)
    }

    @Test
    fun `disappearedNeighbors is empty when every baseline entry is still present`() {
        val baseline = snapshot(listOf(snapshotEntry(key(0xAA))))
        val current = listOf(neighbor(key(0xAA)))

        assertTrue(RepeaterStatusViewModel.disappearedNeighbors(current, baseline).isEmpty())
    }

    @Test
    fun `neighborSnrDelta is null without a baseline snapshot`() {
        assertNull(RepeaterStatusViewModel.neighborSnrDelta(neighbor(key(0xAA)), previousNeighborSnapshot = null))
    }

    @Test
    fun `neighborSnrDelta is null when the neighbor is absent from the baseline`() {
        val baseline = snapshot(listOf(snapshotEntry(key(0xBB), snr = 1.0)))
        assertNull(RepeaterStatusViewModel.neighborSnrDelta(neighbor(key(0xAA), snr = 5.0), baseline))
    }

    @Test
    fun `neighborSnrDelta reports the change against the baseline entry`() {
        val baseline = snapshot(listOf(snapshotEntry(key(0xAA), snr = 3.0)))
        assertEquals(2.0, RepeaterStatusViewModel.neighborSnrDelta(neighbor(key(0xAA), snr = 5.0), baseline)!!, 1e-9)
    }

    @Test
    fun `neighborSnrDelta hides changes below a tenth of a dB`() {
        val baseline = snapshot(listOf(snapshotEntry(key(0xAA), snr = 5.0)))
        assertNull(RepeaterStatusViewModel.neighborSnrDelta(neighbor(key(0xAA), snr = 5.05), baseline))
    }
}
