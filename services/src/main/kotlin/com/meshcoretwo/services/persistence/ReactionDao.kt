// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import java.util.UUID

/** Room DAO for [ReactionEntity] — dumb CRUD only, matching this module's other DAOs. */
@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE messageID = :messageID ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun fetchReactions(messageID: UUID, limit: Int): List<ReactionEntity>

    /** Every reaction across every device — backup export's unscoped fetch. */
    @Query("SELECT * FROM reactions")
    suspend fun fetchAll(): List<ReactionEntity>

    /**
     * Reactions for every message in [messageIDs] — backup import's existing-row lookup. Ported
     * from `existingReactionKeys(messageIDs:)`, minus its `fetchInChunks` batching — see
     * [MessageRepeatDao.fetchForMessages]'s doc for why.
     */
    @Query("SELECT * FROM reactions WHERE messageID IN (:messageIDs)")
    suspend fun fetchForMessages(messageIDs: List<UUID>): List<ReactionEntity>

    @Query("SELECT COUNT(*) FROM reactions WHERE messageID = :messageID AND senderName = :senderName AND emoji = :emoji")
    suspend fun countMatching(messageID: UUID, senderName: String, emoji: String): Int

    @Insert
    suspend fun insert(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE messageID = :messageID")
    suspend fun deleteForMessage(messageID: UUID)

    /** Reactions for every message in [messageIDs] — [ChannelStore]'s slot-wipe cascade. Callers chunk under SQLite's variable limit. */
    @Query("DELETE FROM reactions WHERE messageID IN (:messageIDs)")
    suspend fun deleteForMessages(messageIDs: List<UUID>)

    /** Every reaction for [radioID] — [DeviceStore.deleteDeviceAndData]'s cascade, step 1. */
    @Query("DELETE FROM reactions WHERE radioID = :radioID")
    suspend fun deleteAll(radioID: UUID)
}
