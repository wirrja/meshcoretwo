// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import java.util.UUID

/** Room DAO for [MessageRepeatEntity] — dumb CRUD only, matching [MessageDao]'s split with [MessageRepeatStore] owning the higher-level logic. */
@Dao
interface MessageRepeatDao {
    @Insert
    suspend fun insert(repeat: MessageRepeatEntity)

    @Query("SELECT * FROM message_repeats WHERE messageID = :messageID ORDER BY receivedAt ASC")
    suspend fun fetchForMessage(messageID: UUID): List<MessageRepeatEntity>

    /**
     * Repeats for every message in [messageIDs] — backup export's scoped-by-parent-IDs fetch.
     * Ported from `fetchAllMessageRepeats(messageIDs:)`, minus its `fetchInChunks` batching
     * (SQLite's bound-parameter limit is in the tens of thousands on modern builds; revisit if a
     * real export ever approaches it).
     */
    @Query("SELECT * FROM message_repeats WHERE messageID IN (:messageIDs)")
    suspend fun fetchForMessages(messageIDs: List<UUID>): List<MessageRepeatEntity>

    @Query("SELECT COUNT(*) FROM message_repeats WHERE rxLogEntryID = :rxLogEntryID")
    suspend fun countByRxLogEntryId(rxLogEntryID: UUID): Int

    @Query("DELETE FROM message_repeats WHERE messageID = :messageID")
    suspend fun deleteForMessage(messageID: UUID)

    /** Repeats for every message in [messageIDs] — [DeviceStore.deleteDeviceAndData]'s cascade. */
    @Query("DELETE FROM message_repeats WHERE messageID IN (:messageIDs)")
    suspend fun deleteForMessages(messageIDs: List<UUID>)
}
