// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import java.util.UUID

/** Room DAO for [TracePathRunEntity] — dumb CRUD only, matching [MessageRepeatDao]'s split with [TracePathStore] owning the higher-level logic. */
@Dao
interface TracePathRunDao {
    @Insert
    suspend fun insert(run: TracePathRunEntity)

    @Query("SELECT * FROM trace_path_runs WHERE pathID = :pathID ORDER BY date ASC")
    suspend fun fetchForPath(pathID: UUID): List<TracePathRunEntity>

    /** Every run id store-wide — backup import needs this since [TracePathRunEntity.id] is globally unique, not scoped per path. Ported from the `TracePathRun` fetch in `batchInsertSavedTracePaths`. */
    @Query("SELECT id FROM trace_path_runs")
    suspend fun fetchAllIds(): List<UUID>

    @Query("DELETE FROM trace_path_runs WHERE pathID = :pathID")
    suspend fun deleteForPath(pathID: UUID)
}
