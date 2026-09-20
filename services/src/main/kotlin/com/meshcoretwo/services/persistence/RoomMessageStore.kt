// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.services.backup.BackupDedupKeys
import com.meshcoretwo.services.backup.ParentScopedBatchInsertResult
import com.meshcoretwo.services.backup.PerTypeCounts
import java.util.UUID

/**
 * Room-message persistence, wrapping [RoomMessageDao] with the dedup-check-then-insert and
 * status-write logic Swift keeps in `PersistenceStore+Rooms.swift`'s "RoomMessage Operations"
 * section. Scoped to what [com.meshcoretwo.services.remotenode.RoomServerService]'s ported
 * methods actually call.
 */
class RoomMessageStore(private val database: MeshCoreDatabase) {
    private val dao get() = database.roomMessageDao()

    /** Whether a message with this deduplication key already exists for [sessionID]. */
    suspend fun isDuplicateMessage(sessionID: UUID, deduplicationKey: String): Boolean =
        dao.countByDeduplicationKey(sessionID, deduplicationKey) > 0

    /** Saves a room message, silently ignoring duplicates by deduplication key. Matches `saveRoomMessage`. */
    suspend fun saveMessage(dto: RoomMessageDto) {
        if (isDuplicateMessage(dto.sessionID, dto.deduplicationKey)) return
        dao.insert(dto.toEntity())
    }

    suspend fun fetchMessage(id: UUID): RoomMessageDto? = dao.fetchById(id)?.toDto()

    /** Fetches messages for a room session, oldest first. */
    suspend fun fetchMessages(sessionID: UUID, limit: Int = 50, offset: Int = 0): List<RoomMessageDto> =
        dao.fetchForSession(sessionID, limit, offset).map { it.toDto() }

    /** Room messages for every session in [sessionIDs] — backup export's scoped-by-parent-IDs fetch. Ported from `fetchAllRoomMessages(sessionIDs:)`. */
    suspend fun fetchMessages(sessionIDs: List<UUID>): List<RoomMessageDto> {
        if (sessionIDs.isEmpty()) return emptyList()
        return dao.fetchForSessions(sessionIDs).map { it.toDto() }
    }

    /**
     * Updates a room message's status after a send/retry attempt. A no-op if the row doesn't
     * exist. Matches `updateRoomMessageStatus`: clears [RoomMessageDto.failureSeen] only on a
     * transition *into* [MessageStatus.FAILED] from a non-failed status, and only overwrites
     * [ackCode]/[roundTripTime] when a non-null replacement is given.
     */
    suspend fun updateMessageStatus(id: UUID, status: MessageStatus, ackCode: UInt? = null, roundTripTime: UInt? = null) {
        val existing = dao.fetchById(id) ?: return
        val wasFailed = existing.statusRawValue == MessageStatus.FAILED.rawValue
        dao.update(
            existing.copy(
                statusRawValue = status.rawValue,
                ackCode = ackCode?.toLong() ?: existing.ackCode,
                roundTripTime = roundTripTime?.toLong() ?: existing.roundTripTime,
                failureSeen = if (status == MessageStatus.FAILED && !wasFailed) false else existing.failureSeen,
            ),
        )
    }

    /** Updates a room message's retry bookkeeping. A no-op if the row doesn't exist. Matches `updateRoomMessageRetryStatus`. */
    suspend fun updateMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) {
        val existing = dao.fetchById(id) ?: return
        val wasFailed = existing.statusRawValue == MessageStatus.FAILED.rawValue
        dao.update(
            existing.copy(
                statusRawValue = status.rawValue,
                retryAttempt = retryAttempt,
                maxRetryAttempts = maxRetryAttempts,
                failureSeen = if (status == MessageStatus.FAILED && !wasFailed) false else existing.failureSeen,
            ),
        )
    }

    /** Deletes every message for a room session. Used by [RemoteNodeSessionStore]'s cascading session delete. */
    suspend fun deleteMessages(sessionID: UUID) = dao.deleteForSession(sessionID)

    /** Every room-message key across [sessionIds] — backup import's existing-row lookup. Ported from `existingRoomMessageKeys`. */
    suspend fun existingRoomMessageKeys(sessionIds: Set<UUID>): Set<String> {
        if (sessionIds.isEmpty()) return emptySet()
        return dao.fetchForSessions(sessionIds.toList()).mapTo(mutableSetOf()) { BackupDedupKeys.roomMessageKey(it.sessionID, it.toDto().deduplicationKey) }
    }

    /** Inserts backup room messages whose session is in [existingSessionIds] and whose `(sessionID, deduplicationKey)` key isn't already in [existingKeys]. Ported from `batchInsertRoomMessages`. */
    suspend fun batchInsertRoomMessages(dtos: List<RoomMessageDto>, existingKeys: Set<String>, existingSessionIds: Set<UUID>): ParentScopedBatchInsertResult<UUID> {
        val knownKeys = existingKeys.toMutableSet()
        var inserted = 0
        var skipped = 0
        val affectedSessionIds = mutableSetOf<UUID>()
        for (dto in dtos) {
            if (dto.sessionID !in existingSessionIds) {
                skipped++
                continue
            }
            val key = BackupDedupKeys.roomMessageKey(dto.sessionID, dto.deduplicationKey)
            if (!knownKeys.add(key)) {
                skipped++
                continue
            }
            dao.insert(dto.toEntity())
            affectedSessionIds.add(dto.sessionID)
            inserted++
        }
        return ParentScopedBatchInsertResult(PerTypeCounts(inserted = inserted, skipped = skipped), affectedSessionIds)
    }
}
