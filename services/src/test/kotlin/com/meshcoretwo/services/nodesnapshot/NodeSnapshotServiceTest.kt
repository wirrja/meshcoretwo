// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.nodesnapshot

import com.meshcoretwo.services.persistence.NeighborSnapshotEntry
import com.meshcoretwo.services.persistence.NodeLocationFix
import com.meshcoretwo.services.persistence.NodeSnapshotPersisting
import com.meshcoretwo.services.persistence.NodeStatusMetrics
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.TelemetrySnapshotEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.UUID

/**
 * Ported from Swift's `NodeSnapshotServiceTests`, trimmed to the error-swallowing behavior this
 * thin wrapper is actually responsible for — the throttle/enrichment logic itself is
 * [com.meshcoretwo.services.persistence.NodeStatusSnapshotStore]'s, covered by
 * `NodeStatusSnapshotStoreTest`. Runs under Robolectric so [android.util.Log], called on every
 * failure path and by [NodeSnapshotService.pruneOldSnapshots]'s success path, is shadowed instead
 * of throwing "not mocked" — same reasoning as `DebugLogBufferTest`.
 */
@RunWith(RobolectricTestRunner::class)
class NodeSnapshotServiceTest {
    private val nodePublicKey = ByteArray(32) { it.toByte() }

    @Test
    fun `recordSnapshot delegates to the store and returns its id`() = runBlocking {
        val store = FakeNodeSnapshotPersisting()
        val service = NodeSnapshotService(store)

        val id = service.recordSnapshot(nodePublicKey, status = null, telemetry = null, neighbors = null, location = null)

        assertEquals(store.recordedID, id)
    }

    @Test
    fun `recordSnapshot returns null when the store throws`() = runBlocking {
        val store = FakeNodeSnapshotPersisting(shouldThrow = true)
        val service = NodeSnapshotService(store)

        val id = service.recordSnapshot(nodePublicKey)

        assertNull(id)
    }

    @Test
    fun `neighborBaseline returns an empty baseline when the store throws`() = runBlocking {
        val store = FakeNodeSnapshotPersisting(shouldThrow = true)
        val service = NodeSnapshotService(store)

        val (previous, seenPrefixes) = service.neighborBaseline(nodePublicKey)

        assertNull(previous)
        assertTrue(seenPrefixes.isEmpty())
    }

    @Test
    fun `previousStatusSnapshot returns null when the store throws`() = runBlocking {
        val store = FakeNodeSnapshotPersisting(shouldThrow = true)
        val service = NodeSnapshotService(store)

        assertNull(service.previousStatusSnapshot(nodePublicKey, before = Instant.now()))
    }

    @Test
    fun `fetchSnapshots returns an empty list when the store throws`() = runBlocking {
        val store = FakeNodeSnapshotPersisting(shouldThrow = true)
        val service = NodeSnapshotService(store)

        assertTrue(service.fetchSnapshots(nodePublicKey).isEmpty())
    }

    @Test
    fun `pruneOldSnapshots swallows a store failure`() = runBlocking {
        val store = FakeNodeSnapshotPersisting(shouldThrow = true)
        val service = NodeSnapshotService(store)

        service.pruneOldSnapshots(Instant.now())

        assertTrue(store.pruneCalled)
    }
}

private class FakeNodeSnapshotPersisting(private val shouldThrow: Boolean = false) : NodeSnapshotPersisting {
    val recordedID: UUID = UUID.randomUUID()
    var pruneCalled = false
        private set

    override suspend fun recordNodeStatusSnapshot(
        nodePublicKey: ByteArray,
        status: NodeStatusMetrics?,
        telemetry: List<TelemetrySnapshotEntry>?,
        neighbors: List<NeighborSnapshotEntry>?,
        location: NodeLocationFix?,
    ): UUID {
        if (shouldThrow) throw IllegalStateException("boom")
        return recordedID
    }

    override suspend fun fetchNodeStatusSnapshots(nodePublicKey: ByteArray, since: Instant?): List<NodeStatusSnapshotDto> {
        if (shouldThrow) throw IllegalStateException("boom")
        return emptyList()
    }

    override suspend fun deleteOldNodeStatusSnapshots(olderThan: Instant) {
        pruneCalled = true
        if (shouldThrow) throw IllegalStateException("boom")
    }

    override suspend fun fetchNeighborBaseline(nodePublicKey: ByteArray): Pair<NodeStatusSnapshotDto?, Set<String>> {
        if (shouldThrow) throw IllegalStateException("boom")
        return null to emptySet()
    }

    override suspend fun fetchPreviousStatusSnapshot(nodePublicKey: ByteArray, before: Instant): NodeStatusSnapshotDto? {
        if (shouldThrow) throw IllegalStateException("boom")
        return null
    }
}
