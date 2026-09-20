// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.services.backup.ParentScopedBatchInsertResult
import com.meshcoretwo.services.backup.PerTypeCounts
import java.util.UUID

/**
 * Message-repeat persistence, wrapping [MessageRepeatDao]. Scoped to what
 * [com.meshcoretwo.services.repeats.HeardRepeatsService]'s ported methods (`processForRepeats`,
 * `refreshRepeats`) and [com.meshcoretwo.services.messages.MessageService.resendChannelMessage]
 * actually call.
 *
 * [findSentChannelMessage] and [incrementMessageHeardRepeats]/[incrementMessageSendCount] live on
 * [MessageStore] instead of here since they query/mutate the `messages` table, not
 * `message_repeats`.
 */
class MessageRepeatStore(private val database: MeshCoreDatabase) {
    private val dao get() = database.messageRepeatDao()

    /** Saves a new repeat entry. Always an insert. */
    suspend fun saveMessageRepeat(dto: MessageRepeatDto) = dao.insert(dto.toEntity())

    /** Fetches all repeats for a message, oldest first. */
    suspend fun fetchMessageRepeats(messageID: UUID): List<MessageRepeatDto> = dao.fetchForMessage(messageID).map { it.toDto() }

    /** Repeats for every message in [messageIDs] — backup export's scoped-by-parent-IDs fetch. Ported from `fetchAllMessageRepeats(messageIDs:)`. */
    suspend fun fetchMessageRepeats(messageIDs: List<UUID>): List<MessageRepeatDto> {
        if (messageIDs.isEmpty()) return emptyList()
        return dao.fetchForMessages(messageIDs).map { it.toDto() }
    }

    /** Whether a repeat has already been recorded for this RX-log entry. */
    suspend fun messageRepeatExists(rxLogEntryID: UUID): Boolean = dao.countByRxLogEntryId(rxLogEntryID) > 0

    /** Deletes every recorded repeat for a message. Ported from `deleteMessageRepeats`. */
    suspend fun deleteMessageRepeats(messageID: UUID) = dao.deleteForMessage(messageID)

    /** IDs of every repeat already recorded for any message in [messageIds] — backup import's existing-row lookup. Ported from `existingMessageRepeatIDs`. */
    suspend fun existingRepeatIds(messageIds: Set<UUID>): Set<UUID> {
        if (messageIds.isEmpty()) return emptySet()
        return dao.fetchForMessages(messageIds.toList()).mapTo(mutableSetOf()) { it.id }
    }

    /**
     * Inserts backup repeats whose parent message is in [existingMessageIds] and whose `id` isn't
     * already in [existingIds] — each repeat's `id` is unique-by-construction, so it dedups
     * store-wide rather than per-message. Ported from `batchInsertMessageRepeats`.
     */
    suspend fun batchInsertMessageRepeats(dtos: List<MessageRepeatDto>, existingIds: Set<UUID>, existingMessageIds: Set<UUID>): ParentScopedBatchInsertResult<UUID> {
        val knownIds = existingIds.toMutableSet()
        var inserted = 0
        var skipped = 0
        val affectedMessageIds = mutableSetOf<UUID>()
        for (dto in dtos) {
            if (dto.messageID !in existingMessageIds) {
                skipped++
                continue
            }
            if (!knownIds.add(dto.id)) {
                skipped++
                continue
            }
            dao.insert(dto.toEntity())
            affectedMessageIds.add(dto.messageID)
            inserted++
        }
        return ParentScopedBatchInsertResult(PerTypeCounts(inserted = inserted, skipped = skipped), affectedMessageIds)
    }
}
