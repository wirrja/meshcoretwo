// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant

/**
 * Debug-log persistence, wrapping [DebugLogDao] with the retention logic Swift keeps in
 * `PersistenceStore+Diagnostics.swift`'s "Debug Log Entries" section. The production
 * implementation of [DebugLogPersisting] — see that interface's doc for why it exists separately.
 */
class DebugLogStore(private val database: MeshCoreDatabase) : DebugLogPersisting {
    private val dao get() = database.debugLogDao()

    override suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>) =
        dao.insertAll(entries.map { it.toEntity() })

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Int): List<DebugLogEntryDto> =
        dao.fetchSince(since, limit).map { it.toDto() }

    override suspend fun countDebugLogEntries(): Int = dao.count()

    /** Prunes entries older than [cutoff], then deletes the oldest remaining rows down to [keepCount] if the ceiling is still exceeded. Ported from `pruneDebugLogEntries`. */
    override suspend fun pruneDebugLogEntries(cutoff: Instant, keepCount: Int) {
        dao.deleteOlderThan(cutoff)
        val count = dao.count()
        if (count > keepCount) {
            dao.deleteOldest(count - keepCount)
        }
    }

    override suspend fun clearDebugLogEntries() = dao.deleteAll()
}
