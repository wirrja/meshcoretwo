// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sync

/** Structured result for a full (or initial) sync. Ported from `FullSyncResult` (`SyncCoordinator.swift`). */
data class FullSyncResult(
    val contacts: SyncPhaseStatus,
    val channels: SyncPhaseStatus,
    val messages: SyncPhaseStatus,
    val channelRetryIndices: List<UByte> = emptyList(),
) {
    /** Whether the connection is at least usable for messaging — the contacts phase came back clean. */
    val isConnectionUsable: Boolean get() = contacts is SyncPhaseStatus.Clean

    companion object {
        /** Returned when a sync call is dropped because one is already in progress. */
        val SKIPPED = FullSyncResult(contacts = SyncPhaseStatus.Skipped, channels = SyncPhaseStatus.Skipped, messages = SyncPhaseStatus.Skipped)
    }
}
