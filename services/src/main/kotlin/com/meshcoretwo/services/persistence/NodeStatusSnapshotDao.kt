// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.time.Instant

/** Room DAO for [NodeStatusSnapshotEntity] — dumb CRUD only, matching [PendingSendDao]'s split with [NodeStatusSnapshotStore] owning the throttled-capture orchestration. */
@Dao
interface NodeStatusSnapshotDao {
    @Query("SELECT * FROM node_status_snapshots WHERE nodePublicKey = :nodePublicKey ORDER BY timestamp DESC LIMIT 1")
    suspend fun fetchLatest(nodePublicKey: ByteArray): NodeStatusSnapshotEntity?

    @Query("SELECT * FROM node_status_snapshots WHERE nodePublicKey = :nodePublicKey AND (:since IS NULL OR timestamp >= :since) ORDER BY timestamp ASC")
    suspend fun fetchAll(nodePublicKey: ByteArray, since: Instant?): List<NodeStatusSnapshotEntity>

    /** Every snapshot across every node — backup export's unscoped fetch. */
    @Query("SELECT * FROM node_status_snapshots ORDER BY timestamp ASC")
    suspend fun fetchAll(): List<NodeStatusSnapshotEntity>

    @Insert
    suspend fun insert(entity: NodeStatusSnapshotEntity)

    @Update
    suspend fun update(entity: NodeStatusSnapshotEntity)

    @Query("DELETE FROM node_status_snapshots WHERE timestamp < :date")
    suspend fun deleteOlderThan(date: Instant)

    /**
     * Every snapshot for [nodePublicKey] — called only once [DeviceStore.deleteDeviceAndData]'s
     * cascade has confirmed (via [RemoteNodeSessionDao.countByPublicKey]) no session anywhere still
     * references this node, since snapshots are node-identity-scoped, not radio-scoped.
     */
    @Query("DELETE FROM node_status_snapshots WHERE nodePublicKey = :nodePublicKey")
    suspend fun deleteForPublicKey(nodePublicKey: ByteArray)
}
