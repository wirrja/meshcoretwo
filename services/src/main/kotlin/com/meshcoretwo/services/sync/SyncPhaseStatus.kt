// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sync

/** Phase-level sync outcome. Ported from `SyncPhaseStatus` (`SyncCoordinator.swift`). */
sealed class SyncPhaseStatus {
    object Clean : SyncPhaseStatus()
    object Partial : SyncPhaseStatus()
    object Skipped : SyncPhaseStatus()
    data class Failed(val reason: String) : SyncPhaseStatus()

    val isClean: Boolean get() = this is Clean
}
