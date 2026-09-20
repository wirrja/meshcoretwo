// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sync

/** Progress information during sync. Ported from `SyncProgress` (`SyncCoordinator.swift`). */
data class SyncProgress(val phase: SyncPhase, val current: Int, val total: Int)

/** Errors from [SyncCoordinator] operations. Ported from `SyncCoordinatorError` (`SyncCoordinator.swift`). */
sealed class SyncCoordinatorError(message: String) : Exception(message) {
    object NotConnected : SyncCoordinatorError("Not connected to device.")
    data class SyncFailed(val reason: String) : SyncCoordinatorError("Sync failed: $reason")
    object AlreadySyncing : SyncCoordinatorError("A sync is already in progress.")
}

/** Current state of [SyncCoordinator]. Ported from `SyncState` (`SyncCoordinator.swift`). */
sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val progress: SyncProgress) : SyncState()
    object Synced : SyncState()
    data class Failed(val error: SyncCoordinatorError) : SyncState()

    val isSyncing: Boolean get() = this is Syncing
}
