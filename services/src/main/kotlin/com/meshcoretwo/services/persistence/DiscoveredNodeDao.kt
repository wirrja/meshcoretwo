// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.util.UUID

/**
 * Room DAO for [DiscoveredNodeEntity]. Deliberately dumb CRUD only — upsert/cap-eviction/
 * pending-inbound-hop business logic lives in [DiscoveredNodeStore], mirroring [ContactDao]'s
 * split. Ported from the subset of `PersistenceStore.swift`'s "Discovered Nodes" section (see its
 * class doc) this vertical slice covers.
 */
@Dao
interface DiscoveredNodeDao {
    @Query("SELECT * FROM discovered_nodes WHERE radioID = :radioID AND publicKey = :publicKey LIMIT 1")
    suspend fun fetch(radioID: UUID, publicKey: ByteArray): DiscoveredNodeEntity?

    @Query("SELECT * FROM discovered_nodes WHERE radioID = :radioID")
    suspend fun fetchAll(radioID: UUID): List<DiscoveredNodeEntity>

    /** Every discovered node across every device — backup export's unscoped fetch. */
    @Query("SELECT * FROM discovered_nodes")
    suspend fun fetchAll(): List<DiscoveredNodeEntity>

    /** Every discovered node across the given devices — backup import's existing-row lookup. Ported from `existingDiscoveredNodeKeys`. */
    @Query("SELECT * FROM discovered_nodes WHERE radioID IN (:radioIDs)")
    suspend fun fetchAll(radioIDs: List<UUID>): List<DiscoveredNodeEntity>

    @Query("SELECT COUNT(*) FROM discovered_nodes WHERE radioID = :radioID")
    suspend fun count(radioID: UUID): Int

    /** Oldest-heard-first rows for a device, for cap eviction — see [DiscoveredNodeStore]. */
    @Query("SELECT * FROM discovered_nodes WHERE radioID = :radioID ORDER BY lastHeard ASC LIMIT :limit")
    suspend fun fetchOldest(radioID: UUID, limit: Int): List<DiscoveredNodeEntity>

    @Insert
    suspend fun insert(node: DiscoveredNodeEntity)

    @Update
    suspend fun update(node: DiscoveredNodeEntity)

    @Delete
    suspend fun delete(node: DiscoveredNodeEntity)

    @Query("DELETE FROM discovered_nodes WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM discovered_nodes WHERE radioID = :radioID")
    suspend fun deleteAll(radioID: UUID)
}
