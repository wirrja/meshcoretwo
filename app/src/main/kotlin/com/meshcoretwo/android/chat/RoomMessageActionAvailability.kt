// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RoomMessageDto

/**
 * Determines which actions a room message exposes in its long-press actions sheet. Ported 1:1
 * from `RoomMessageActionAvailability.swift` — the room counterpart to [MessageActionAvailability],
 * much smaller since a room-server message has no reactions/block-sender/delete/repeat-details/
 * view-path (see [RoomMessageActionsSheet]'s doc for why those don't apply here).
 */
data class RoomMessageActionAvailability(
    val canReply: Boolean,
    val canSendDM: Boolean,
    val canSendAgain: Boolean,
) {
    constructor(message: RoomMessageDto, session: RemoteNodeSessionDto) : this(
        canReply = !message.isFromSelf && session.canPost,
        canSendDM = !message.isFromSelf && message.authorName != null,
        canSendAgain = message.isFromSelf,
    )
}
