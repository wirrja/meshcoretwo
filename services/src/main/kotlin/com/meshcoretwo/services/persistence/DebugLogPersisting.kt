// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant

/**
 * Store operations [com.meshcoretwo.services.logging.DebugLogBuffer] needs. Ported from
 * `DebugLogPersisting.swift`'s protocol — kept as a narrow interface (rather than depending on
 * [DebugLogStore] directly) so tests can substitute a fake that controls save timing/failure
 * without a real Room database, matching [DebugLogStore] (the only production implementation)
 * one-to-one.
 */
interface DebugLogPersisting {
    /** Saves a batch of debug log entries. */
    suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>)

    /** Fetches debug log entries received since [since], newest first, capped at [limit]. */
    suspend fun fetchDebugLogEntries(since: Instant, limit: Int = 1000): List<DebugLogEntryDto>

    /** Counts all debug log entries. */
    suspend fun countDebugLogEntries(): Int

    /** Prunes entries older than [cutoff], then enforces a hard row ceiling of [keepCount]. */
    suspend fun pruneDebugLogEntries(cutoff: Instant, keepCount: Int)

    /** Clears all debug log entries. */
    suspend fun clearDebugLogEntries()
}
