// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sync

/**
 * Data-change events broadcast by [SyncCoordinator.dataEvents]. Ported from `SyncDataEvent.swift`,
 * trimmed to the two cases [SyncCoordinator] itself is the source of truth for.
 *
 * Not ported here: `directMessageReceived`/`channelMessageReceived` — already available via
 * [com.meshcoretwo.services.messages.IncomingMessageService.receivedEvents]; `roomMessageReceived`
 * — `RoomServerService` has no equivalent event stream yet; `reactionReceived` — `ReactionService`
 * doesn't emit one yet either. Swift funnels all six into one broadcaster because `SyncCoordinator`
 * is the single cross-cutting actor; this port keeps each concern's events on its owning service's
 * own `Flow` instead of re-plumbing them through a second broadcaster, matching the DI-everywhere
 * (no single God-object) pattern used throughout the rest of the port. A caller that wants "any
 * data changed" can combine [SyncCoordinator.dataEvents] with the per-service flows directly.
 */
sealed class SyncDataEvent {
    /** Contacts data changed; observers should reload contact lists. */
    object ContactsChanged : SyncDataEvent()

    /** Conversations data changed; observers should reload chat lists. */
    object ConversationsChanged : SyncDataEvent()
}
