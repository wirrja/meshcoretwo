// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rxlog

import com.meshcoretwo.protocol.RegionMatchResult

/**
 * Dual-field read/write helpers for message and RX-log region labeling. Ported from
 * `RegionScopeSemantics.swift`. `protocol`'s [com.meshcoretwo.protocol.TransportCodeRegionResolver]
 * owns [RegionMatchResult] itself (the actual packet-matching algorithm); this object maps match
 * results to the two persisted storage fields and coalesces them back for readers (including rows
 * that only ever had a single `regionScope` written, from before ambiguous-match tracking existed).
 */
object RegionScopeSemantics {
    /** Compact chip join for multi-match labels (e.g. `de-hh / de-by`). */
    const val CHIP_NAME_SEPARATOR = " / "

    /** The two persisted fields a [RegionMatchResult] maps to. */
    data class StorageFields(val regionScope: String?, val regionScopeMatches: List<String>)

    /**
     * Maps a match result into the two persisted fields.
     * - [RegionMatchResult.None] -> `(null, [])`
     * - [RegionMatchResult.Unique] -> `(name, [name])`
     * - [RegionMatchResult.Ambiguous] -> `(null, sorted names)` — never a single first-match name
     */
    fun storageFields(match: RegionMatchResult): StorageFields = when (match) {
        is RegionMatchResult.None -> StorageFields(null, emptyList())
        is RegionMatchResult.Unique -> {
            val trimmed = match.name.trim()
            if (trimmed.isEmpty()) StorageFields(null, emptyList()) else StorageFields(trimmed, listOf(trimmed))
        }
        is RegionMatchResult.Ambiguous -> {
            val filtered = filteredSortedNames(match.names)
            when (filtered.size) {
                0 -> StorageFields(null, emptyList())
                1 -> StorageFields(filtered[0], filtered)
                else -> StorageFields(null, filtered)
            }
        }
    }

    /**
     * Dual-field read. Multi-match wins over a non-null [scope] so a stale single name never
     * surfaces when the match list has two or more entries.
     *
     * Priority: matches >= 2 -> ambiguous; matches == 1 -> unique; empty matches with non-null
     * scope -> unique (legacy); both empty -> none.
     */
    fun coalesce(scope: String?, matches: List<String>): RegionMatchResult {
        val filtered = filteredSortedNames(matches)
        return when (filtered.size) {
            0 -> {
                val trimmed = scope?.trim()
                if (trimmed.isNullOrEmpty()) RegionMatchResult.None else RegionMatchResult.Unique(trimmed)
            }
            1 -> RegionMatchResult.Unique(filtered[0])
            else -> RegionMatchResult.Ambiguous(filtered)
        }
    }

    /** Chip label from a coalesced result. `null` hides the chip. */
    fun chipLabel(match: RegionMatchResult): String? = when (match) {
        is RegionMatchResult.None -> null
        is RegionMatchResult.Unique -> match.name
        is RegionMatchResult.Ambiguous -> match.names.joinToString(CHIP_NAME_SEPARATOR)
    }

    /** Post-filter match names for popover / a11y / Message Info lists. */
    fun matchNames(match: RegionMatchResult): List<String> = when (match) {
        is RegionMatchResult.None -> emptyList()
        is RegionMatchResult.Unique -> listOf(match.name)
        is RegionMatchResult.Ambiguous -> match.names
    }

    /**
     * Blank-filter then ascending sort, de-duplicated. Plain lexicographic order, not Swift's
     * locale-aware `localizedStandardCompare` — same simplification
     * [com.meshcoretwo.protocol.TransportCodeRegionResolver.matchRegions] already documents as
     * immaterial for the short ASCII region-code names this handles.
     */
    private fun filteredSortedNames(names: List<String>): List<String> {
        val trimmed = names.map { it.trim() }.filter { it.isNotEmpty() }
        return trimmed.toSet().sorted()
    }
}
