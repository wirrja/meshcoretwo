// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.util.UUID

/**
 * Pending-send persistence, wrapping [PendingSendDao] with the sequence-assignment and
 * attempt-count bookkeeping [com.meshcoretwo.services.sendqueue.ChatSendQueueService] needs.
 * Ported from the `PendingSend`-related methods of `PersistenceStore.swift`.
 */
class PendingSendStore(private val database: MeshCoreDatabase) {
    private val dao get() = database.pendingSendDao()

    /** Whether a `PendingSend` row still exists for [messageID] — the drain's terminal-abandon gate. */
    suspend fun hasPendingSend(messageID: UUID): Boolean = dao.fetchByMessageID(messageID) != null

    /** Fetches every pending send for [radioID], ordered by enqueue sequence. Used to replay the queue on hydrate. */
    suspend fun fetchPendingSends(radioID: UUID): List<PendingSendDto> = dao.fetchAll(radioID).map { it.toDto() }

    /**
     * Persists [dto], assigning the next per-radio [PendingSendDto.sequence] so drain order across
     * process death matches original enqueue order. Ignores any `sequence` already set on [dto].
     */
    suspend fun insertPendingSendAssigningSequence(dto: PendingSendDto): PendingSendDto {
        val nextSequence = dao.fetchMaxSequence(dto.radioID) + 1
        val assigned = dto.copy(sequence = nextSequence)
        dao.insert(assigned.toEntity())
        return assigned
    }

    /** Deletes any pending-send row(s) for [messageID]. No-op if none exist. */
    suspend fun deletePendingSendsForMessage(messageID: UUID) = dao.deleteForMessage(messageID)

    /**
     * Bumps [PendingSendDto.attemptCount] by one and returns the new value, or `null` if the row is
     * gone (terminal — the drain should abandon the envelope).
     */
    suspend fun incrementPendingSendAttemptCount(messageID: UUID): Int? {
        val existing = dao.fetchByMessageID(messageID) ?: return null
        val updated = existing.copy(attemptCount = existing.attemptCount + 1)
        dao.update(updated)
        return updated.attemptCount
    }

    /**
     * Deletes every pending-send row whose radio has no matching `Device` row — the
     * "erase device then re-pair" self-heal Swift's `purgeOrphanPendingSends` runs from
     * `PersistenceStore.warmUp()`. Safe to call any time: [com.meshcoretwo.services.ServiceContainer.warmUp]
     * only calls it after the in-progress radio's [DeviceDto] row is already persisted, so that
     * radio's own pending sends never look orphaned. Matches `purgeOrphanPendingSends`.
     *
     * @return the number of rows deleted.
     */
    suspend fun purgeOrphanPendingSends(): Int = dao.deleteOrphaned()
}
