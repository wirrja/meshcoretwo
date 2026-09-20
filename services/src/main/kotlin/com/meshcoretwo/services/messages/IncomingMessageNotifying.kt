// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.MessageDto
import java.util.UUID

/**
 * Narrow interface [IncomingMessageService] uses to run the unread-counter/notification side
 * effects of a just-saved incoming message, mirroring Swift's `SyncCoordinator.
 * updateDMUnreadsAndNotify`/`updateChannelUnreadsAndNotify`. Satisfied by
 * `com.meshcoretwo.services.notifications.NotificationCoordinator` — same narrow-interface trick
 * as [ReactionHandling]/[RxLogCorrelating], so `messages` doesn't need to know the `notifications`
 * package exists when a caller has no use for it.
 */
interface IncomingMessageNotifying {
    /**
     * Runs the unread-counter increment and notification post for a just-saved direct message.
     * A `null`/blocked [contact] is a no-op (matching Swift's `contact?.isBlocked != true` guard)
     * — a blocked contact's messages still save, they just never bump unread or notify.
     */
    suspend fun notifyDirectMessage(message: MessageDto, contact: ContactDto?, hasSelfMention: Boolean)

    /**
     * Runs the unread-counter increment and notification post for a just-saved channel message.
     * An unresolved [channel] (`null` — no local row for this slot) is a no-op for both the
     * counter and the notification, matching Swift's `if let channelID = channel?.id` guard and
     * `shouldPostChannelNotification(forResolvedChannel:)` (`channel != nil`); Swift additionally
     * logs an "unresolved channel" diagnostic summary in that case, which this port drops (pure
     * logging, no persisted state). Callers are expected to have already dropped blocked-sender
     * messages before calling, matching Swift's `saveMessage` happening before this side-effect
     * step.
     */
    suspend fun notifyChannelMessage(
        message: MessageDto,
        channel: ChannelDto?,
        channelIndex: UByte,
        senderNodeName: String?,
        hasSelfMention: Boolean,
        radioID: UUID,
    )
}
