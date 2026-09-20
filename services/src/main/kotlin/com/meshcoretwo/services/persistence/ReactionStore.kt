// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.services.backup.BackupDedupKeys
import com.meshcoretwo.services.backup.ParentScopedBatchInsertResult
import com.meshcoretwo.services.backup.PerTypeCounts
import java.util.UUID

/**
 * Reaction persistence, wrapping [ReactionDao]. Ported from the reaction methods of
 * `PersistenceStore+Messages.swift` (`ReactionPersisting`'s conformance) — this is the whole
 * protocol's method set, so nothing is trimmed here.
 */
class ReactionStore(private val database: MeshCoreDatabase) {
    private val dao get() = database.reactionDao()

    /** Saves a new reaction. Always an insert. */
    suspend fun saveReaction(dto: ReactionDto) = dao.insert(dto.toEntity())

    /** Fetches a message's reactions, most recent first. */
    suspend fun fetchReactions(messageID: UUID, limit: Int = 100): List<ReactionDto> =
        dao.fetchReactions(messageID, limit).map { it.toDto() }

    /** Every reaction across every device — backup export's unscoped fetch. Ported from `fetchAllReactions`. */
    suspend fun fetchAllReactions(): List<ReactionDto> = dao.fetchAll().map { it.toDto() }

    /** Whether [senderName] already reacted to [messageID] with [emoji] (dedup check). */
    suspend fun reactionExists(messageID: UUID, senderName: String, emoji: String): Boolean =
        dao.countMatching(messageID, senderName, emoji) > 0

    /** Deletes all reactions for a message. */
    suspend fun deleteReactionsForMessage(messageID: UUID) = dao.deleteForMessage(messageID)

    /** Every reaction key across [messageIds] — backup import's existing-row lookup. Ported from `existingReactionKeys`. */
    suspend fun existingReactionKeys(messageIds: Set<UUID>): Set<String> {
        if (messageIds.isEmpty()) return emptySet()
        return dao.fetchForMessages(messageIds.toList()).mapTo(mutableSetOf()) { BackupDedupKeys.reactionKey(it.messageID, it.senderName, it.emoji) }
    }

    /** Inserts backup reactions whose parent message is in [existingMessageIds] and whose `(messageID, senderName, emoji)` key isn't already in [existingKeys]. Ported from `batchInsertReactions`. */
    suspend fun batchInsertReactions(dtos: List<ReactionDto>, existingKeys: Set<String>, existingMessageIds: Set<UUID>): ParentScopedBatchInsertResult<UUID> {
        val knownKeys = existingKeys.toMutableSet()
        var inserted = 0
        var skipped = 0
        val affectedMessageIds = mutableSetOf<UUID>()
        for (dto in dtos) {
            if (dto.messageID !in existingMessageIds) {
                skipped++
                continue
            }
            val key = BackupDedupKeys.reactionKey(dto.messageID, dto.senderName, dto.emoji)
            if (!knownKeys.add(key)) {
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
