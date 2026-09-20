// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sync

/** Phases of the sync process. Ported from `SyncPhase` (`SyncCoordinator.swift`). */
enum class SyncPhase {
    CONTACTS,
    CHANNELS,
    MESSAGES,
}
