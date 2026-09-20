// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.nodesnapshot

import android.util.Log
import com.meshcoretwo.services.persistence.NeighborSnapshotEntry
import com.meshcoretwo.services.persistence.NodeLocationFix
import com.meshcoretwo.services.persistence.NodeSnapshotPersisting
import com.meshcoretwo.services.persistence.NodeStatusMetrics
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.TelemetrySnapshotEntry
import java.time.Instant
import java.util.UUID

/**
 * Service for managing node status snapshots with throttled capture. Ported from
 * `NodeSnapshotService.swift`'s `actor` — a thin best-effort wrapper with no mutable state of its
 * own (the throttle/atomicity logic lives in [NodeSnapshotPersisting]'s implementation), so unlike
 * [com.meshcoretwo.services.logging.DebugLogBuffer] this needs no dispatcher confinement to port
 * Swift's actor isolation.
 *
 * Wired to `app`'s `RoomStatusViewModel` (status/telemetry) and `RepeaterStatusViewModel`
 * (status/telemetry/neighbors), each recording a snapshot on every successful request and reading
 * the matching baseline first — [previousStatusSnapshot] for the status deltas (both view models),
 * [neighborBaseline] for the neighbor SNR delta, the "New" badge and the disappeared-neighbor rows
 * (repeaters only). [fetchSnapshots] backs `app`'s `NodeStatusHistoryViewModel` (the history
 * drill-down), which fetches a node's whole history once and filters it by time range in memory —
 * so the `since` parameter has no caller yet.
 */
class NodeSnapshotService(private val dataStore: NodeSnapshotPersisting) {
    /**
     * Captures a status, telemetry, and/or neighbor reading for a node, enriching the latest
     * in-window snapshot or inserting a new one. Returns the snapshot ID so callers can target
     * later enrichment, or `null` on persistence failure. The throttle check and the write are
     * atomic in the store, so concurrent captures never duplicate an in-window row.
     */
    suspend fun recordSnapshot(
        nodePublicKey: ByteArray,
        status: NodeStatusMetrics? = null,
        telemetry: List<TelemetrySnapshotEntry>? = null,
        neighbors: List<NeighborSnapshotEntry>? = null,
        location: NodeLocationFix? = null,
    ): UUID? = try {
        dataStore.recordNodeStatusSnapshot(nodePublicKey, status, telemetry, neighbors, location)
    } catch (error: Exception) {
        Log.e(TAG, "Failed to record snapshot", error)
        null
    }

    /**
     * The neighbor baseline for a node: the previous neighbor-bearing snapshot (for the SNR delta)
     * plus every neighbor prefix seen across history (for the "New" badge). Skips status- or
     * telemetry-only rows and the current in-window capture.
     */
    suspend fun neighborBaseline(nodePublicKey: ByteArray): Pair<NodeStatusSnapshotDto?, Set<String>> = try {
        dataStore.fetchNeighborBaseline(nodePublicKey)
    } catch (error: Exception) {
        Log.e(TAG, "Failed to fetch neighbor baseline", error)
        null to emptySet()
    }

    /**
     * Fetches the most recent snapshot carrying status fields, for the status delta. Skips
     * neighbor- or telemetry-only rows so the delta is taken against the previous actual status
     * reading rather than blanking out.
     */
    suspend fun previousStatusSnapshot(nodePublicKey: ByteArray, before: Instant): NodeStatusSnapshotDto? = try {
        dataStore.fetchPreviousStatusSnapshot(nodePublicKey, before)
    } catch (error: Exception) {
        Log.e(TAG, "Failed to fetch previous status snapshot", error)
        null
    }

    /** Fetches all snapshots for a node, optionally filtered by date range. */
    suspend fun fetchSnapshots(nodePublicKey: ByteArray, since: Instant? = null): List<NodeStatusSnapshotDto> = try {
        dataStore.fetchNodeStatusSnapshots(nodePublicKey, since)
    } catch (error: Exception) {
        Log.e(TAG, "Failed to fetch snapshots", error)
        emptyList()
    }

    /** Deletes snapshots older than the given date. */
    suspend fun pruneOldSnapshots(olderThan: Instant) {
        try {
            dataStore.deleteOldNodeStatusSnapshots(olderThan)
            Log.i(TAG, "Pruned snapshots older than $olderThan")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to prune old snapshots", error)
        }
    }

    private companion object {
        const val TAG = "NodeSnapshotService"
    }
}
