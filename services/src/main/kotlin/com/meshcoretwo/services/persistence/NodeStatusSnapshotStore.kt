// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.withTransaction
import com.meshcoretwo.services.backup.BackupDedupKeys
import com.meshcoretwo.services.backup.PerTypeCounts
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Node-status-snapshot persistence, wrapping [NodeStatusSnapshotDao] with the throttled-capture
 * orchestration [com.meshcoretwo.services.nodesnapshot.NodeSnapshotService] needs. Ported from the
 * "Node Status Snapshots" section of `PersistenceStore+Diagnostics.swift`.
 */
class NodeStatusSnapshotStore(private val database: MeshCoreDatabase) : NodeSnapshotPersisting {
    private val dao get() = database.nodeStatusSnapshotDao()

    /**
     * The fetch-latest decision and the insert-or-enrich both run inside one [androidx.room.withTransaction]
     * block, so two concurrent captures serialize and the second observes the first's row — there
     * is no window for a duplicate in-window insert. Mirrors the same-isolation guarantee Swift's
     * `@ModelActor` gives `recordNodeStatusSnapshot` (a single suspension-free actor-isolated method
     * body), achieved here via a real SQLite transaction instead of actor confinement.
     *
     * Within [NodeSnapshotPolicy.MINIMUM_INTERVAL_SECONDS] of the latest snapshot the capture
     * enriches that row: status fields are applied only when the row is still telemetry-only
     * ([NodeStatusSnapshotDto.uptimeSeconds] `== null`), preserving the one-status-point-per-window
     * throttle; telemetry and neighbor arrays are applied whenever supplied. A location fix is
     * first-wins: it is written only when the row has none yet. Outside the window a new snapshot
     * is inserted. Ported from `recordNodeStatusSnapshot`.
     */
    override suspend fun recordNodeStatusSnapshot(
        nodePublicKey: ByteArray,
        status: NodeStatusMetrics?,
        telemetry: List<TelemetrySnapshotEntry>?,
        neighbors: List<NeighborSnapshotEntry>?,
        location: NodeLocationFix?,
    ): UUID = database.withTransaction {
        val now = Instant.now()
        val latest = dao.fetchLatest(nodePublicKey)

        if (latest != null && Duration.between(latest.timestamp, now).seconds < NodeSnapshotPolicy.MINIMUM_INTERVAL_SECONDS) {
            var enriched = latest
            if (status != null && latest.uptimeSeconds == null) {
                enriched = enriched.applying(status)
            }
            if (telemetry != null) {
                enriched = enriched.copy(telemetryEntries = telemetry)
            }
            if (neighbors != null) {
                enriched = enriched.copy(neighborSnapshots = neighbors)
            }
            if (location != null && enriched.latitude == null) {
                enriched = enriched.copy(latitude = location.latitude, longitude = location.longitude, altitude = location.altitude)
            }
            dao.update(enriched)
            return@withTransaction enriched.id
        }

        var fresh = NodeStatusSnapshotEntity(
            id = UUID.randomUUID(),
            timestamp = now,
            nodePublicKey = nodePublicKey,
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
            neighborSnapshots = neighbors,
            telemetryEntries = telemetry,
            latitude = null,
            longitude = null,
            altitude = null,
        )
        if (status != null) fresh = fresh.applying(status)
        if (location != null) fresh = fresh.copy(latitude = location.latitude, longitude = location.longitude, altitude = location.altitude)
        dao.insert(fresh)
        fresh.id
    }

    override suspend fun fetchNodeStatusSnapshots(nodePublicKey: ByteArray, since: Instant?): List<NodeStatusSnapshotDto> =
        dao.fetchAll(nodePublicKey, since).map { it.toDto() }

    /** Every snapshot across every node — backup export's unscoped fetch. Ported from `fetchAllNodeStatusSnapshots`. */
    suspend fun fetchAllNodeStatusSnapshots(): List<NodeStatusSnapshotDto> = dao.fetchAll().map { it.toDto() }

    override suspend fun deleteOldNodeStatusSnapshots(olderThan: Instant) = dao.deleteOlderThan(olderThan)

    /** Every snapshot key store-wide — backup import's existing-row lookup (snapshots aren't scoped per device). Ported from `existingNodeStatusSnapshotKeys`. */
    suspend fun existingSnapshotKeys(): Set<String> =
        dao.fetchAll().mapTo(mutableSetOf()) { BackupDedupKeys.nodeStatusSnapshotKey(it.nodePublicKey, it.timestamp) }

    /** Inserts backup snapshots whose `(nodePublicKey, timestamp-in-millis)` key isn't already in [existingKeys]. Ported from `batchInsertNodeStatusSnapshots`. */
    suspend fun batchInsertNodeStatusSnapshots(dtos: List<NodeStatusSnapshotDto>, existingKeys: Set<String>): PerTypeCounts {
        val knownKeys = existingKeys.toMutableSet()
        var inserted = 0
        var skipped = 0
        for (dto in dtos) {
            val key = BackupDedupKeys.nodeStatusSnapshotKey(dto.nodePublicKey, dto.timestamp)
            if (!knownKeys.add(key)) {
                skipped++
                continue
            }
            dao.insert(dto.toEntity())
            inserted++
        }
        return PerTypeCounts(inserted = inserted, skipped = skipped)
    }
}

/** Applies captured status metrics onto this entity, leaving neighbor/telemetry/location fields untouched. Ported from `NodeStatusSnapshot.apply(_:)`. */
private fun NodeStatusSnapshotEntity.applying(metrics: NodeStatusMetrics): NodeStatusSnapshotEntity = copy(
    batteryMillivolts = metrics.batteryMillivolts?.toInt(),
    lastSNR = metrics.lastSNR,
    lastRSSI = metrics.lastRSSI?.toInt(),
    noiseFloor = metrics.noiseFloor?.toInt(),
    uptimeSeconds = metrics.uptimeSeconds?.toLong(),
    rxAirtimeSeconds = metrics.rxAirtimeSeconds?.toLong(),
    packetsSent = metrics.packetsSent?.toLong(),
    packetsReceived = metrics.packetsReceived?.toLong(),
    receiveErrors = metrics.receiveErrors?.toLong(),
    sentDirect = metrics.sentDirect?.toLong(),
    sentFlood = metrics.sentFlood?.toLong(),
    receivedDirect = metrics.receivedDirect?.toLong(),
    receivedFlood = metrics.receivedFlood?.toLong(),
    directDuplicates = metrics.directDuplicates?.toLong(),
    floodDuplicates = metrics.floodDuplicates?.toLong(),
    postedCount = metrics.postedCount?.toInt(),
    postPushCount = metrics.postPushCount?.toInt(),
)
