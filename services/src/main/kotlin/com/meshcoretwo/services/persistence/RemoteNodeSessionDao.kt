// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [RemoteNodeSessionEntity] — dumb CRUD only, matching [ContactDao]/[ChannelDao]'s
 * split with [RemoteNodeSessionStore] owning upsert/dedup logic. Prefix and radio-scoped
 * public-key lookups load rows and filter in memory rather than adding dedicated `@Query`s,
 * matching Swift's own `PersistenceStore+Rooms.swift` comment ("SwiftData predicates don't
 * support prefix matching directly") and its `fetchRemoteNodeSessions(radioID:).first(where:)`
 * pattern for `e410eb8d`'s radio-scoped lookups — session counts are small (one row per
 * logged-in room/repeater), so this isn't a real cost.
 */
@Dao
interface RemoteNodeSessionDao {
    @Query("SELECT * FROM remote_node_sessions WHERE id = :id LIMIT 1")
    suspend fun fetchById(id: UUID): RemoteNodeSessionEntity?

    @Query("SELECT * FROM remote_node_sessions WHERE radioID = :radioID")
    suspend fun fetchByRadioID(radioID: UUID): List<RemoteNodeSessionEntity>

    /** Live version of [fetchByRadioID] — backs [RemoteNodeSessionStore.observeSessions], the same
     * live-query precedent as [ContactDao.observeContacts]/[ChannelDao.observeChannels]. */
    @Query("SELECT * FROM remote_node_sessions WHERE radioID = :radioID")
    fun observeByRadioID(radioID: UUID): Flow<List<RemoteNodeSessionEntity>>

    /** Every session across the given devices — backup import's existing-row lookup. Ported from `fetchExistingRemoteNodeSessionsByKey`. */
    @Query("SELECT * FROM remote_node_sessions WHERE radioID IN (:radioIDs)")
    suspend fun fetchByRadioIDs(radioIDs: List<UUID>): List<RemoteNodeSessionEntity>

    /** Sessions by local id — backup import's last-message-date reconciliation, which already has local ids from ID-mapping. Ported from the `RemoteNodeSession` fetch in `applyLastMessageDatesToRemoteNodeSessions`. */
    @Query("SELECT * FROM remote_node_sessions WHERE id IN (:ids)")
    suspend fun fetchByIds(ids: List<UUID>): List<RemoteNodeSessionEntity>

    @Query("SELECT * FROM remote_node_sessions")
    suspend fun fetchAll(): List<RemoteNodeSessionEntity>

    @Query("SELECT * FROM remote_node_sessions WHERE isConnected = 1")
    suspend fun fetchConnected(): List<RemoteNodeSessionEntity>

    @Insert
    suspend fun insert(session: RemoteNodeSessionEntity)

    @Update
    suspend fun update(session: RemoteNodeSessionEntity)

    @Query("DELETE FROM remote_node_sessions WHERE id = :id")
    suspend fun delete(id: UUID)

    /** Every session for [radioID] — [DeviceStore.deleteDeviceAndData]'s cascade. */
    @Query("DELETE FROM remote_node_sessions WHERE radioID = :radioID")
    suspend fun deleteAll(radioID: UUID)

    /**
     * How many sessions (across every device, not just one radio) still reference [publicKey] —
     * [RemoteNodeSessionEntity] is node-identity-scoped rather than radio-scoped (see this
     * interface's doc), so [DeviceStore.deleteDeviceAndData]'s cascade uses this count to decide
     * whether a node's shared [NodeStatusSnapshotEntity] rows are now orphaned.
     */
    @Query("SELECT COUNT(*) FROM remote_node_sessions WHERE publicKey = :publicKey")
    suspend fun countByPublicKey(publicKey: ByteArray): Int

    @Query("UPDATE remote_node_sessions SET isConnected = 0")
    suspend fun resetAllConnections()
}
