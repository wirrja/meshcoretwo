// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.util.UUID

/**
 * Room DAO for [MessageEntity] — dumb CRUD only, matching [ContactDao]/[DeviceDao]/[ChannelDao]'s
 * split with [MessageStore] owning the update-terminal-state-guard logic.
 */
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun fetchMessage(id: UUID): MessageEntity?

    /** Every message across every device — backup export's unscoped fetch. */
    @Query("SELECT * FROM messages")
    suspend fun fetchAll(): List<MessageEntity>

    /** Every message across the given devices — backup import's existing-row lookup. Ported from `existingMessageLookups`. */
    @Query("SELECT * FROM messages WHERE radioID IN (:radioIDs)")
    suspend fun fetchAll(radioIDs: List<UUID>): List<MessageEntity>

    /** Messages by id — backup import's post-insert cache recompute, which already knows exactly which messages were touched. Ported from the `Message` fetch in `recomputeMessageCaches`. */
    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun fetchByIds(ids: List<UUID>): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE contactID = :contactID " +
            "ORDER BY sortDate DESC, timestamp DESC, createdAt DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun fetchMessagesForContact(contactID: UUID, limit: Int, offset: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "ORDER BY sortDate DESC, timestamp DESC, createdAt DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun fetchMessagesForChannel(radioID: UUID, channelIndex: Int, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE deduplicationKey = :deduplicationKey AND radioID = :radioID")
    suspend fun countByDeduplicationKey(deduplicationKey: String, radioID: UUID): Int

    /** The row a duplicate would collide with — a later flood copy attaches to it as an extra path instead of being dropped. Ported from `fetchMessage(deduplicationKey:radioID:)`. */
    @Query("SELECT * FROM messages WHERE deduplicationKey = :deduplicationKey AND radioID = :radioID LIMIT 1")
    suspend fun findByDeduplicationKey(deduplicationKey: String, radioID: UUID): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "AND timestamp >= :start AND timestamp <= :end " +
            "ORDER BY createdAt DESC, timestamp DESC LIMIT :limit",
    )
    suspend fun fetchChannelMessageCandidates(radioID: UUID, channelIndex: Int, start: Long, end: Long, limit: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE radioID = :radioID AND contactID = :contactID " +
            "AND timestamp >= :start AND timestamp <= :end " +
            "ORDER BY createdAt DESC, timestamp DESC LIMIT :limit",
    )
    suspend fun fetchDMMessageCandidates(radioID: UUID, contactID: UUID, start: Long, end: Long, limit: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "AND timestamp = :timestamp AND directionRawValue = :outgoing AND text = :text " +
            "ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun findSentChannelMessage(radioID: UUID, channelIndex: Int, timestamp: Long, text: String, outgoing: Int): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: UUID)

    /** Ids of every message in a channel slot — [ChannelStore]'s slot-wipe drives the Reaction/MessageRepeat/PendingSend sub-cascade off this. */
    @Query("SELECT id FROM messages WHERE radioID = :radioID AND channelIndex = :channelIndex")
    suspend fun fetchMessageIdsForChannel(radioID: UUID, channelIndex: Int): List<UUID>

    /** Ids of every message for a contact — [MessageStore.deleteMessagesForContact]'s sub-cascade driver. */
    @Query("SELECT id FROM messages WHERE contactID = :contactID")
    suspend fun fetchMessageIdsForContact(contactID: UUID): List<UUID>

    /** Ids of every channel message from a sender — [MessageStore.deleteChannelMessagesFromSender]'s sub-cascade driver. */
    @Query("SELECT id FROM messages WHERE radioID = :radioID AND senderNodeName = :senderNodeName AND channelIndex IS NOT NULL")
    suspend fun fetchChannelMessageIdsFromSender(radioID: UUID, senderNodeName: String): List<UUID>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessages(ids: List<UUID>)

    @Query(
        "SELECT id FROM messages WHERE contactID = :contactID AND containsSelfMention = 1 AND mentionSeen = 0 " +
            "ORDER BY timestamp ASC",
    )
    suspend fun fetchUnseenMentionIDs(contactID: UUID): List<UUID>

    @Query(
        "SELECT id FROM messages WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "AND containsSelfMention = 1 AND mentionSeen = 0 ORDER BY timestamp ASC",
    )
    suspend fun fetchUnseenChannelMentionIDs(radioID: UUID, channelIndex: Int): List<UUID>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    /** Batch update — Room wraps a collection [Update] in a single transaction, same as [MessageDao]'s siblings. Used by the region-reprocess sweep. */
    @Update
    suspend fun updateAll(messages: List<MessageEntity>)

    /**
     * Incoming channel messages matching an RX-log entry's `(channelIndex, senderTimestamp)` —
     * the region-reprocess sweep's channel-message correlation target. `COALESCE(senderTimestamp,
     * timestamp)` mirrors [MessageDto.reactionTimestamp]: the sender's originally-claimed time,
     * not [timestamp] once clock-corrected.
     */
    @Query(
        "SELECT * FROM messages WHERE radioID = :radioID AND channelIndex = :channelIndex AND directionRawValue = :incoming " +
            "AND COALESCE(senderTimestamp, timestamp) = :senderTimestamp",
    )
    suspend fun findChannelMessagesForRegionCorrelation(radioID: UUID, channelIndex: Int, senderTimestamp: Long, incoming: Int): List<MessageEntity>

    /**
     * Incoming direct messages matching an RX-log entry's sender timestamp, scoped to DMs
     * (`channelIndex IS NULL`) — the region-reprocess sweep's DM-message correlation target.
     * Callers still filter the result by [MessageEntity.senderKeyPrefix]'s first byte in Kotlin
     * (same style as [RxLogStore.findRxLogEntryBySenderPrefix]) since SQLite has no convenient
     * single-byte BLOB-prefix comparison here.
     */
    @Query(
        "SELECT * FROM messages WHERE radioID = :radioID AND channelIndex IS NULL AND directionRawValue = :incoming " +
            "AND COALESCE(senderTimestamp, timestamp) = :senderTimestamp",
    )
    suspend fun findDMMessagesForRegionCorrelation(radioID: UUID, senderTimestamp: Long, incoming: Int): List<MessageEntity>

    /** Ids of every message for [radioID] — [DeviceStore.deleteDeviceAndData]'s cascade drives [MessageRepeatDao]/[PendingSendDao]'s messageID-keyed sub-cascade off this before deleting the messages themselves. */
    @Query("SELECT id FROM messages WHERE radioID = :radioID")
    suspend fun fetchMessageIdsForRadio(radioID: UUID): List<UUID>

    /** Every message for [radioID] — [DeviceStore.deleteDeviceAndData]'s cascade. */
    @Query("DELETE FROM messages WHERE radioID = :radioID")
    suspend fun deleteMessagesForRadio(radioID: UUID)
}
