// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import java.time.Instant

/** Room DAO for [DebugLogEntity] — dumb CRUD only, matching this module's other DAOs. */
@Dao
interface DebugLogDao {
    @Insert
    suspend fun insertAll(entries: List<DebugLogEntity>)

    @Query("SELECT * FROM debug_log_entries WHERE timestamp >= :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun fetchSince(since: Instant, limit: Int): List<DebugLogEntity>

    @Query("SELECT COUNT(*) FROM debug_log_entries")
    suspend fun count(): Int

    @Query("DELETE FROM debug_log_entries WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Instant)

    @Query("DELETE FROM debug_log_entries WHERE id IN (SELECT id FROM debug_log_entries ORDER BY timestamp ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("DELETE FROM debug_log_entries")
    suspend fun deleteAll()
}
