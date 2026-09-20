// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import com.meshcoretwo.services.backup.BackupDedupKeys
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

/**
 * Exercises [NodeStatusSnapshotStore] against a real in-memory Room database via Robolectric —
 * ported from Swift's `NodeSnapshotServiceTests`' coverage of `recordNodeStatusSnapshot`/
 * `fetchNeighborBaseline`/`fetchPreviousStatusSnapshot`, trimmed to the throttle/enrichment/
 * first-wins-location rules that section documents, rather than every one of Swift's 755 lines of
 * cases.
 */
@RunWith(RobolectricTestRunner::class)
class NodeStatusSnapshotStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: NodeStatusSnapshotStore

    private val nodePublicKey = ByteArray(32) { it.toByte() }
    private val otherNodePublicKey = ByteArray(32) { (it + 1).toByte() }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = NodeStatusSnapshotStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Backdates the latest snapshot for [nodePublicKey] so the next capture falls outside the throttle window. */
    private suspend fun pushLatestOutsideWindow(nodePublicKey: ByteArray) {
        val dao = database.nodeStatusSnapshotDao()
        val latest = requireNotNull(dao.fetchLatest(nodePublicKey))
        dao.update(latest.copy(timestamp = Instant.now().minusSeconds(NodeSnapshotPolicy.MINIMUM_INTERVAL_SECONDS + 1)))
    }

    private fun metrics(uptimeSeconds: UInt? = 100u, lastSNR: Double? = 5.0) = NodeStatusMetrics(
        batteryMillivolts = 3700u,
        lastSNR = lastSNR,
        lastRSSI = (-90).toShort(),
        noiseFloor = (-110).toShort(),
        uptimeSeconds = uptimeSeconds,
        rxAirtimeSeconds = null,
        packetsSent = 10u,
        packetsReceived = 20u,
        receiveErrors = null,
        sentDirect = null,
        sentFlood = null,
        receivedDirect = null,
        receivedFlood = null,
        directDuplicates = null,
        floodDuplicates = null,
    )

    @Test
    fun `recordNodeStatusSnapshot inserts a new row when none exists`() = runTest {
        val id = store.recordNodeStatusSnapshot(nodePublicKey, metrics(), telemetry = null, neighbors = null, location = null)

        val rows = store.fetchNodeStatusSnapshots(nodePublicKey)
        assertEquals(1, rows.size)
        assertEquals(id, rows.single().id)
        assertEquals(100u, rows.single().uptimeSeconds)
    }

    @Test
    fun `recordNodeStatusSnapshot enriches the latest row within the throttle window`() = runTest {
        val firstID = store.recordNodeStatusSnapshot(nodePublicKey, metrics(), telemetry = null, neighbors = null, location = null)

        val secondID = store.recordNodeStatusSnapshot(
            nodePublicKey,
            status = null,
            telemetry = listOf(TelemetrySnapshotEntry(channel = 0, type = "temp", value = 21.5)),
            neighbors = null,
            location = null,
        )

        assertEquals(firstID, secondID)
        val rows = store.fetchNodeStatusSnapshots(nodePublicKey)
        assertEquals(1, rows.size)
        assertEquals(1, rows.single().telemetryEntries?.size)
    }

    @Test
    fun `recordNodeStatusSnapshot does not overwrite status once uptimeSeconds is set within window`() = runTest {
        store.recordNodeStatusSnapshot(nodePublicKey, metrics(uptimeSeconds = 100u, lastSNR = 5.0), telemetry = null, neighbors = null, location = null)

        store.recordNodeStatusSnapshot(nodePublicKey, metrics(uptimeSeconds = 200u, lastSNR = 9.0), telemetry = null, neighbors = null, location = null)

        val row = store.fetchNodeStatusSnapshots(nodePublicKey).single()
        assertEquals(100u, row.uptimeSeconds)
        assertEquals(5.0, row.lastSNR)
    }

    @Test
    fun `recordNodeStatusSnapshot location is first-wins within the window`() = runTest {
        store.recordNodeStatusSnapshot(
            nodePublicKey, status = null, telemetry = null, neighbors = null,
            location = NodeLocationFix(latitude = 1.0, longitude = 2.0, altitude = 3.0),
        )

        store.recordNodeStatusSnapshot(
            nodePublicKey, status = null, telemetry = null, neighbors = null,
            location = NodeLocationFix(latitude = 9.0, longitude = 9.0, altitude = 9.0),
        )

        val row = store.fetchNodeStatusSnapshots(nodePublicKey).single()
        assertEquals(1.0, row.latitude)
        assertEquals(2.0, row.longitude)
    }

    @Test
    fun `recordNodeStatusSnapshot inserts a new row outside the throttle window`() = runTest {
        val firstID = store.recordNodeStatusSnapshot(nodePublicKey, metrics(), telemetry = null, neighbors = null, location = null)
        pushLatestOutsideWindow(nodePublicKey)

        val secondID = store.recordNodeStatusSnapshot(nodePublicKey, metrics(), telemetry = null, neighbors = null, location = null)

        assertTrue(firstID != secondID)
        assertEquals(2, store.fetchNodeStatusSnapshots(nodePublicKey).size)
    }

    @Test
    fun `fetchNeighborBaseline returns the previous neighbor-bearing snapshot, excluding the current in-window capture`() = runTest {
        val prefixA = byteArrayOf(0x01, 0x02)
        val prefixB = byteArrayOf(0x03, 0x04)

        store.recordNodeStatusSnapshot(
            nodePublicKey, status = null, telemetry = null,
            neighbors = listOf(NeighborSnapshotEntry(prefixA, snr = 4.0, secondsAgo = 10)),
            location = null,
        )
        pushLatestOutsideWindow(nodePublicKey)

        // This second capture is the "reading being viewed" — its own prefix must not appear in
        // seenPrefixes, and it must not be returned as its own baseline.
        store.recordNodeStatusSnapshot(
            nodePublicKey, status = null, telemetry = null,
            neighbors = listOf(NeighborSnapshotEntry(prefixB, snr = 6.0, secondsAgo = 5)),
            location = null,
        )

        val (previous, seenPrefixes) = store.fetchNeighborBaseline(nodePublicKey)

        assertEquals(listOf(NeighborSnapshotEntry(prefixA, snr = 4.0, secondsAgo = 10)), previous?.neighborSnapshots)
        assertEquals(setOf("0102"), seenPrefixes)
    }

    @Test
    fun `fetchPreviousStatusSnapshot skips neighbor-only rows and returns the prior status reading`() = runTest {
        store.recordNodeStatusSnapshot(nodePublicKey, metrics(uptimeSeconds = 50u), telemetry = null, neighbors = null, location = null)
        pushLatestOutsideWindow(nodePublicKey)

        store.recordNodeStatusSnapshot(
            nodePublicKey, status = null, telemetry = null,
            neighbors = listOf(NeighborSnapshotEntry(byteArrayOf(0x01), snr = 1.0, secondsAgo = 1)),
            location = null,
        )

        val previous = store.fetchPreviousStatusSnapshot(nodePublicKey, before = Instant.now().plusSeconds(60))

        assertEquals(50u, previous?.uptimeSeconds)
    }

    @Test
    fun `deleteOldNodeStatusSnapshots removes rows before the cutoff`() = runTest {
        store.recordNodeStatusSnapshot(nodePublicKey, metrics(), telemetry = null, neighbors = null, location = null)
        val dao = database.nodeStatusSnapshotDao()
        val row = dao.fetchLatest(nodePublicKey)!!
        dao.update(row.copy(timestamp = Instant.now().minusSeconds(400 * 24 * 60 * 60L)))
        store.recordNodeStatusSnapshot(otherNodePublicKey, metrics(), telemetry = null, neighbors = null, location = null)

        store.deleteOldNodeStatusSnapshots(olderThan = Instant.now().minusSeconds(365 * 24 * 60 * 60L))

        assertTrue(store.fetchNodeStatusSnapshots(nodePublicKey).isEmpty())
        assertEquals(1, store.fetchNodeStatusSnapshots(otherNodePublicKey).size)
    }

    @Test
    fun `fetchNeighborBaseline is empty when the node has no history`() = runTest {
        val (previous, seenPrefixes) = store.fetchNeighborBaseline(nodePublicKey)
        assertNull(previous)
        assertTrue(seenPrefixes.isEmpty())
    }

    private fun snapshotDto(nodePublicKey: ByteArray = this.nodePublicKey, timestamp: Instant = Instant.now()) = NodeStatusSnapshotDto(
        id = UUID.randomUUID(), timestamp = timestamp, nodePublicKey = nodePublicKey,
        batteryMillivolts = null, lastSNR = null, lastRSSI = null, noiseFloor = null,
        uptimeSeconds = null, rxAirtimeSeconds = null, packetsSent = null, packetsReceived = null,
        receiveErrors = null, sentDirect = null, sentFlood = null, receivedDirect = null, receivedFlood = null,
        directDuplicates = null, floodDuplicates = null, postedCount = null, postPushCount = null,
        neighborSnapshots = null, telemetryEntries = null, latitude = null, longitude = null, altitude = null,
    )

    @Test
    fun `existingSnapshotKeys keys by (nodePublicKey, timestamp-in-millis) store-wide`() = runTest {
        // Whole-second timestamp: NodeStatusSnapshotEntity.timestamp round-trips through Room at
        // epoch-second precision (see Converters.kt), so a sub-second component here would make
        // the fetched-back key disagree with the one computed from this local Instant.
        val timestamp = Instant.ofEpochSecond(1_700_000_000)
        store.batchInsertNodeStatusSnapshots(listOf(snapshotDto(timestamp = timestamp)), emptySet())

        val keys = store.existingSnapshotKeys()

        assertEquals(setOf(BackupDedupKeys.nodeStatusSnapshotKey(nodePublicKey, timestamp)), keys)
    }

    @Test
    fun `batchInsertNodeStatusSnapshots inserts a new snapshot and skips a duplicate key`() = runTest {
        val timestamp = Instant.ofEpochMilli(1_700_000_000_000)
        val existingKey = BackupDedupKeys.nodeStatusSnapshotKey(nodePublicKey, timestamp)
        val duplicate = snapshotDto(timestamp = timestamp)
        val fresh = snapshotDto(nodePublicKey = otherNodePublicKey, timestamp = timestamp)

        val counts = store.batchInsertNodeStatusSnapshots(listOf(duplicate, fresh), setOf(existingKey))

        assertEquals(1, counts.inserted)
        assertEquals(1, counts.skipped)
    }
}
