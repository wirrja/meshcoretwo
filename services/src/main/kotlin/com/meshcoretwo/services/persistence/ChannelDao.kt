// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [ChannelEntity] — dumb CRUD only, matching [ContactDao]/[DeviceDao]'s split with
 * [ChannelStore] owning upsert logic. `index` is a SQLite keyword (as in `CREATE INDEX`), hence
 * the backtick-quoting in the raw `@Query` strings — Room quotes entity-generated column
 * references itself, but not identifiers written by hand here.
 */
@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE radioID = :radioID ORDER BY `index`")
    suspend fun fetchChannels(radioID: UUID): List<ChannelEntity>

    /** Same rows as [fetchChannels], but as a live Room query that re-emits on any write to `channels`. */
    @Query("SELECT * FROM channels WHERE radioID = :radioID ORDER BY `index`")
    fun observeChannels(radioID: UUID): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE radioID = :radioID AND `index` = :index LIMIT 1")
    suspend fun fetchChannel(radioID: UUID, index: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun fetchChannel(id: UUID): ChannelEntity?

    /** Every channel across every device — backup export's unscoped counterpart of [fetchChannels]. */
    @Query("SELECT * FROM channels")
    suspend fun fetchAll(): List<ChannelEntity>

    /** Every channel across the given devices — backup import's existing-row lookup, scoped to just the radios being touched. Ported from `fetchExistingChannels`. */
    @Query("SELECT * FROM channels WHERE radioID IN (:radioIDs)")
    suspend fun fetchAll(radioIDs: List<UUID>): List<ChannelEntity>

    @Insert
    suspend fun insert(channel: ChannelEntity)

    @Update
    suspend fun update(channel: ChannelEntity)

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun deleteChannel(id: UUID)

    /** Every channel for [radioID] — [DeviceStore.deleteDeviceAndData]'s cascade. */
    @Query("DELETE FROM channels WHERE radioID = :radioID")
    suspend fun deleteAll(radioID: UUID)
}
