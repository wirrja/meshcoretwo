// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.RoomMessageDto
import java.util.UUID

/**
 * Room-server notifications broadcast by [RoomServerService.events]. Ported from
 * `RoomServerEvent.swift`, plus [MessageReceived] — Swift's counterpart lives on the broader,
 * app-layer `MessageEvent` bus (`MessageEvent.roomMessageReceived`) rather than on
 * `RoomServerEvent` itself, but `RoomServerService` (unlike Swift's `MessagePollingService`/
 * `RoomServerService` split) already owns both the save and the sibling events in
 * [RoomServerService.handleIncomingMessage], so this is the natural, minimal home for it — no new
 * cross-service event bus needed for this port. The stream is multicast via `MutableSharedFlow` —
 * every subscriber receives every event, matching [RemoteNodeEvent]'s doc.
 */
sealed class RoomServerEvent {
    /** A room message's delivery status changed (send/retry resolved to delivered/sent/failed). */
    data class StatusUpdated(val messageID: UUID, val status: MessageStatus) : RoomServerEvent()

    /** An inbound message proved a previously-disconnected room session is actually active. */
    data class ConnectionRecovered(val sessionID: UUID) : RoomServerEvent()

    /** A new room message (incoming or self-echoed) was persisted — see this class's doc. */
    data class MessageReceived(val message: RoomMessageDto) : RoomServerEvent()
}
