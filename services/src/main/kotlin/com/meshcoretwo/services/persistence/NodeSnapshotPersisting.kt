// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant
import java.util.UUID

/**
 * Store operations [com.meshcoretwo.services.nodesnapshot.NodeSnapshotService] needs. Ported from
 * `NodeSnapshotPersisting.swift`'s protocol — kept as a narrow interface (rather than depending on
 * [NodeStatusSnapshotStore] directly) so tests can substitute a fake, matching [DebugLogPersisting]'s
 * precedent. [NodeStatusSnapshotStore] (the only production implementation) matches it one-to-one.
 */
interface NodeSnapshotPersisting {
    /**
     * Atomically captures a status, telemetry, neighbor, and/or location snapshot for a node,
     * enriching the latest in-window snapshot or inserting a new one. Returns the snapshot ID. A
     * location fix is first-wins: it is written only once per row.
     */
    suspend fun recordNodeStatusSnapshot(
        nodePublicKey: ByteArray,
        status: NodeStatusMetrics?,
        telemetry: List<TelemetrySnapshotEntry>?,
        neighbors: List<NeighborSnapshotEntry>?,
        location: NodeLocationFix?,
    ): UUID

    /** Fetches snapshots for a node within a date range, sorted by timestamp ascending. */
    suspend fun fetchNodeStatusSnapshots(nodePublicKey: ByteArray, since: Instant? = null): List<NodeStatusSnapshotDto>

    /** Deletes snapshots older than the given date. */
    suspend fun deleteOldNodeStatusSnapshots(olderThan: Instant)

    /**
     * The neighbor baseline: the previous neighbor-bearing snapshot (for the SNR delta) plus every
     * neighbor public-key prefix seen across history (for the "New" badge), hex-encoded since a raw
     * [ByteArray] has reference equality and can't back a [Set]. Both derive from one fetch and one
     * in-window cutoff, which excludes the reading being viewed so it is never diffed or matched
     * against itself. Neighbor arrays are sparse (a snapshot holds them only when the user expanded
     * the neighbors section), so status- or telemetry-only rows are skipped. Ported from
     * `NodeSnapshotPersisting.fetchNeighborBaseline`, a Swift protocol-extension default
     * implementation — same role here as a Kotlin interface default method.
     */
    suspend fun fetchNeighborBaseline(nodePublicKey: ByteArray): Pair<NodeStatusSnapshotDto?, Set<String>> {
        val all = fetchNodeStatusSnapshots(nodePublicKey)
        val latest = all.lastOrNull()
        val cutoff = if (latest != null &&
            java.time.Duration.between(latest.timestamp, Instant.now()).seconds < NodeSnapshotPolicy.MINIMUM_INTERVAL_SECONDS
        ) {
            latest.timestamp
        } else {
            Instant.now()
        }
        val history = all.filter { it.timestamp < cutoff && it.neighborSnapshots != null }
        val seenPrefixes = history.flatMap { it.neighborSnapshots.orEmpty() }
            .map { entry -> entry.publicKeyPrefix.joinToString("") { "%02x".format(it) } }
            .toSet()
        return history.lastOrNull() to seenPrefixes
    }

    /**
     * The most recent snapshot carrying status fields before [before], for the status delta. A
     * neighbor- or telemetry-only capture inserts a row with no status; skipping those (via
     * [NodeStatusSnapshotDto.uptimeSeconds], the in-window throttle's marker field) keeps such a
     * row from blanking the delta. The in-window capture is kept, unlike the neighbor baseline:
     * status is throttled and never overwrites itself, so an early reading in the current window is
     * still a valid baseline. Ported from `NodeSnapshotPersisting.fetchPreviousStatusSnapshot`.
     */
    suspend fun fetchPreviousStatusSnapshot(nodePublicKey: ByteArray, before: Instant): NodeStatusSnapshotDto? =
        fetchNodeStatusSnapshots(nodePublicKey).lastOrNull { it.timestamp < before && it.uptimeSeconds != null }
}
