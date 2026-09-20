// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.reactions

import java.util.UUID

/**
 * Notifications broadcast by [ReactionService]. Ported from the reaction case of
 * `SyncDataEvent.swift`, trimmed to the one case this MVP emits — see [ReactionService]'s class
 * doc for what's deferred.
 */
sealed class ReactionEvent {
    /** A reaction was newly persisted for [messageID]; [summary] is its updated `"👍:3,❤️:2"`-style cache. */
    data class ReactionReceived(val messageID: UUID, val summary: String) : ReactionEvent()
}
