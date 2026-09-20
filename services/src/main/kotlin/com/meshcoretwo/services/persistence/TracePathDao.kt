// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.util.UUID

/** Room DAO for [TracePathEntity] — dumb CRUD only, matching this module's other DAOs. */
@Dao
interface TracePathDao {
    @Insert
    suspend fun insert(path: TracePathEntity)

    @Update
    suspend fun update(path: TracePathEntity)

    @Delete
    suspend fun delete(path: TracePathEntity)

    @Query("SELECT * FROM saved_trace_paths WHERE id = :id LIMIT 1")
    suspend fun fetch(id: UUID): TracePathEntity?

    @Query("SELECT * FROM saved_trace_paths WHERE radioID = :radioID ORDER BY createdDate DESC")
    suspend fun fetchForRadio(radioID: UUID): List<TracePathEntity>

    /** Every saved path across every device — backup export's unscoped fetch. */
    @Query("SELECT * FROM saved_trace_paths ORDER BY createdDate DESC")
    suspend fun fetchAll(): List<TracePathEntity>

    /** Every saved path for [radioID] — [DeviceStore.deleteDeviceAndData]'s cascade. */
    @Query("DELETE FROM saved_trace_paths WHERE radioID = :radioID")
    suspend fun deleteAll(radioID: UUID)
}
