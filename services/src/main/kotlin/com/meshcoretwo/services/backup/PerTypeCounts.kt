// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

/**
 * Insert/merge/skip/dropped counts for a single model type during backup import. Ported from
 * `AppBackupEnvelope.swift`'s `PerTypeCounts`. [dropped] rows could not be restored at all (e.g. a
 * channel with no free local slot and its messages/reactions, or discover-list rows past the
 * per-radio cap) — distinct from [skipped], which means the row was already present locally.
 */
data class PerTypeCounts(
    val inserted: Int = 0,
    val merged: Int = 0,
    val skipped: Int = 0,
    val dropped: Int = 0,
) {
    companion object {
        val ZERO = PerTypeCounts()
    }
}
