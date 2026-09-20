// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.util.UUID

/**
 * Room DAO for [RoomMessageEntity] — dumb CRUD only, matching [MessageDao]'s split with
 * [RoomMessageStore] owning the dedup-check-then-insert/terminal-state-guard logic. Ported from
 * the `RoomMessage` queries in `PersistenceStore+Rooms.swift`.
 */
@Dao
interface RoomMessageDao {
    @Query("SELECT COUNT(*) FROM room_messages WHERE sessionID = :sessionID AND deduplicationKey = :deduplicationKey")
    suspend fun countByDeduplicationKey(sessionID: UUID, deduplicationKey: String): Int

    @Query("SELECT * FROM room_messages WHERE id = :id LIMIT 1")
    suspend fun fetchById(id: UUID): RoomMessageEntity?

    @Query("SELECT * FROM room_messages WHERE sessionID = :sessionID ORDER BY timestamp ASC, createdAt ASC LIMIT :limit OFFSET :offset")
    suspend fun fetchForSession(sessionID: UUID, limit: Int, offset: Int): List<RoomMessageEntity>

    /**
     * Room messages for every session in [sessionIDs] — backup export's scoped-by-parent-IDs
     * fetch. Ported from `fetchAllRoomMessages(sessionIDs:)`, see [MessageRepeatDao
     * .fetchForMessages]'s doc for why this skips `fetchInChunks` batching.
     */
    @Query("SELECT * FROM room_messages WHERE sessionID IN (:sessionIDs)")
    suspend fun fetchForSessions(sessionIDs: List<UUID>): List<RoomMessageEntity>

    @Insert
    suspend fun insert(message: RoomMessageEntity)

    @Update
    suspend fun update(message: RoomMessageEntity)

    @Query("DELETE FROM room_messages WHERE sessionID = :sessionID")
    suspend fun deleteForSession(sessionID: UUID)
}
