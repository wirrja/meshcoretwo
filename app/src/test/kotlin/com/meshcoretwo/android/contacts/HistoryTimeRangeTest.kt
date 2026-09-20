// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers [HistoryTimeRange] — the history charts' range cutoff and in-memory filtering. */
class HistoryTimeRangeTest {
    private val now: Instant = Instant.parse("2026-09-11T12:00:00Z")

    private fun snapshot(daysAgo: Long) = NodeStatusSnapshotDto(
        id = UUID.randomUUID(),
        timestamp = now.minus(daysAgo, ChronoUnit.DAYS),
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
        neighborSnapshots = null,
        telemetryEntries = null,
        latitude = null,
        longitude = null,
        altitude = null,
    )

    @Test
    fun `the default range is one month`() {
        assertEquals(HistoryTimeRange.MONTH, HistoryTimeRange.DEFAULT)
    }

    @Test
    fun `all has no cutoff`() {
        assertNull(HistoryTimeRange.ALL.startInstant(now))
    }

    @Test
    fun `each range counts back from now`() {
        assertEquals(now.minus(7, ChronoUnit.DAYS), HistoryTimeRange.WEEK.startInstant(now))
        assertEquals(now.minus(30, ChronoUnit.DAYS), HistoryTimeRange.MONTH.startInstant(now))
        assertEquals(now.minus(90, ChronoUnit.DAYS), HistoryTimeRange.THREE_MONTHS.startInstant(now))
    }

    @Test
    fun `filtering drops snapshots older than the cutoff`() {
        val snapshots = listOf(snapshot(daysAgo = 1), snapshot(daysAgo = 40))

        val filtered = HistoryTimeRange.MONTH.filter(snapshots, now)

        assertEquals(listOf(snapshots[0]), filtered)
    }

    @Test
    fun `all keeps every snapshot`() {
        val snapshots = listOf(snapshot(daysAgo = 1), snapshot(daysAgo = 400))

        assertEquals(snapshots, HistoryTimeRange.ALL.filter(snapshots, now))
    }

    @Test
    fun `a snapshot exactly on the cutoff is kept`() {
        val snapshots = listOf(snapshot(daysAgo = 7))

        assertEquals(snapshots, HistoryTimeRange.WEEK.filter(snapshots, now))
    }
}
