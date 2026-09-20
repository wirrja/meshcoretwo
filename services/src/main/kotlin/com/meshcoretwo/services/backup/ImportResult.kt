// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import java.util.UUID

/**
 * Tracks per-model insert/merge/skip counts during a backup import. Ported from
 * `AppBackupEnvelope.swift`'s `ImportResult` — a mutable class here rather than a Swift
 * `mutating struct`, since the future import sub-slice (see PLAN.md's "Backup/restore — план
 * среза") will thread one instance through a long, imperative insert/merge pass the same way the
 * Swift source's `mutating func record` does.
 */
class ImportResult {
    private val counts: MutableMap<BackupModelKind, PerTypeCounts> = BackupModelKind.entries.associateWith { PerTypeCounts.ZERO }.toMutableMap()

    var userDefaultsRestored: Boolean = false

    /**
     * Local channel slots, keyed by radioID, whose occupancy changed during import (relocated,
     * merged at a different slot, or dropped). The main-actor caller clears chat drafts for these
     * slots so a draft typed against one channel can't silently follow a slot to a different
     * channel.
     */
    val channelSlotsAffectedByImport: MutableMap<UUID, MutableSet<UByte>> = mutableMapOf()

    fun counts(kind: BackupModelKind): PerTypeCounts = counts.getValue(kind)

    /** Adds to the counts for [kind]. All import phases funnel through this single mutator so totals and per-kind buckets can't drift. */
    fun record(kind: BackupModelKind, inserted: Int = 0, merged: Int = 0, skipped: Int = 0, dropped: Int = 0) {
        val current = counts.getValue(kind)
        counts[kind] = current.copy(
            inserted = current.inserted + inserted,
            merged = current.merged + merged,
            skipped = current.skipped + skipped,
            dropped = current.dropped + dropped,
        )
    }

    val totalInserted: Int get() = counts.values.sumOf { it.inserted }
    val totalMerged: Int get() = counts.values.sumOf { it.merged }
    val totalSkipped: Int get() = counts.values.sumOf { it.skipped }
    val totalDropped: Int get() = counts.values.sumOf { it.dropped }
    val totalRestoredRecordCount: Int get() = totalInserted + totalMerged
    val hasRestoredChanges: Boolean get() = totalRestoredRecordCount > 0 || userDefaultsRestored
}
