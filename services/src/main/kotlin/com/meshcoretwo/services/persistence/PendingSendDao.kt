// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.util.UUID

/** Room DAO for [PendingSendEntity] — dumb CRUD only, matching [ContactDao]/[ChannelDao]'s split with [PendingSendStore] owning sequencing logic. */
@Dao
interface PendingSendDao {
    @Query("SELECT * FROM pending_sends WHERE messageID = :messageID LIMIT 1")
    suspend fun fetchByMessageID(messageID: UUID): PendingSendEntity?

    @Query("SELECT * FROM pending_sends WHERE radioID = :radioID ORDER BY sequence ASC")
    suspend fun fetchAll(radioID: UUID): List<PendingSendEntity>

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM pending_sends WHERE radioID = :radioID")
    suspend fun fetchMaxSequence(radioID: UUID): Int

    @Insert
    suspend fun insert(entry: PendingSendEntity)

    @Update
    suspend fun update(entry: PendingSendEntity)

    @Query("DELETE FROM pending_sends WHERE messageID = :messageID")
    suspend fun deleteForMessage(messageID: UUID)

    /** Every row for every message in [messageIDs] — [DeviceStore.deleteDeviceAndData]'s cascade. */
    @Query("DELETE FROM pending_sends WHERE messageID IN (:messageIDs)")
    suspend fun deleteForMessages(messageIDs: List<UUID>)

    /**
     * Every row for [radioID] — [DeviceStore.deleteDeviceAndData]'s cascade's defensive sweep,
     * run after [deleteForMessages], to catch rows whose `messageID` no longer matches any message
     * (e.g. a `PendingSend` created for a message that was already deleted some other way).
     */
    @Query("DELETE FROM pending_sends WHERE radioID = :radioID")
    suspend fun deleteAll(radioID: UUID)

    /** Deletes every row whose [PendingSendEntity.radioID] has no matching `devices` row. Returns the count deleted. */
    @Query("DELETE FROM pending_sends WHERE radioID NOT IN (SELECT radioID FROM devices)")
    suspend fun deleteOrphaned(): Int
}
