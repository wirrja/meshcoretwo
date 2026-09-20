// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.util.UUID

/**
 * Room DAO for [BlockedChannelSenderEntity] — dumb CRUD only, matching [ContactDao]/[ChannelDao]'s
 * split with [ContactStore] owning the upsert-by-`(radioID, name)` logic.
 */
@Dao
interface BlockedChannelSenderDao {
    @Query("SELECT * FROM blocked_channel_senders WHERE radioID = :radioID AND name = :name LIMIT 1")
    suspend fun fetch(radioID: UUID, name: String): BlockedChannelSenderEntity?

    @Query("SELECT * FROM blocked_channel_senders WHERE radioID = :radioID ORDER BY dateBlocked DESC")
    suspend fun fetchAll(radioID: UUID): List<BlockedChannelSenderEntity>

    /** Every blocked sender across every device — backup export's unscoped fetch. */
    @Query("SELECT * FROM blocked_channel_senders ORDER BY dateBlocked DESC")
    suspend fun fetchAll(): List<BlockedChannelSenderEntity>

    /** Every blocked sender across the given devices — backup import's existing-row lookup. Ported from `existingBlockedSenderKeys`. */
    @Query("SELECT * FROM blocked_channel_senders WHERE radioID IN (:radioIDs)")
    suspend fun fetchAll(radioIDs: List<UUID>): List<BlockedChannelSenderEntity>

    @Insert
    suspend fun insert(entry: BlockedChannelSenderEntity)

    @Update
    suspend fun update(entry: BlockedChannelSenderEntity)

    @Query("DELETE FROM blocked_channel_senders WHERE radioID = :radioID AND name = :name")
    suspend fun delete(radioID: UUID, name: String)

    /** Every blocked sender for [radioID] — [DeviceStore.deleteDeviceAndData]'s cascade. */
    @Query("DELETE FROM blocked_channel_senders WHERE radioID = :radioID")
    suspend fun deleteAll(radioID: UUID)
}
