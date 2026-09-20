// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import com.meshcoretwo.services.messages.IncomingMessageNotifying
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MessageDto
import java.util.UUID

/**
 * Bridges [com.meshcoretwo.services.messages.IncomingMessageService] to unread counters and
 * [NotificationService], implementing [IncomingMessageNotifying]. Ported from the relevant half
 * of `SyncCoordinator+HandlerHelpers.swift`'s `updateDMUnreadsAndNotify`/
 * `updateChannelUnreadsAndNotify` — the half that doesn't need a full `SyncCoordinator`
 * (`dataEventBroadcaster`/`notifyConversationsChanged` UI-refresh signaling isn't ported here;
 * [com.meshcoretwo.services.messages.IncomingMessageService.receivedEvents] already gives callers
 * a real-time stream to react to instead).
 */
class NotificationCoordinator(
    private val notificationService: NotificationService,
    private val contactStore: ContactStore,
    private val channelStore: ChannelStore,
) : IncomingMessageNotifying {
    override suspend fun notifyDirectMessage(message: MessageDto, contact: ContactDto?, hasSelfMention: Boolean) {
        val contactID = contact?.id ?: return
        if (contact.isBlocked) return

        if (contactID != notificationService.activeContactID) {
            contactStore.incrementUnreadCount(contactID)
            if (hasSelfMention) contactStore.incrementUnreadMentionCount(contactID)
        }

        notificationService.postDirectMessageNotification(
            contactName = contact.displayName,
            contactID = contactID,
            messageText = message.text,
            messageID = message.id,
            isMuted = contact.isMuted,
        )
    }

    override suspend fun notifyChannelMessage(
        message: MessageDto,
        channel: ChannelDto?,
        channelIndex: UByte,
        senderNodeName: String?,
        hasSelfMention: Boolean,
        radioID: UUID,
    ) {
        // Unresolved channel (no local row for this slot): Swift skips both the counter and the
        // notification here (see [IncomingMessageNotifying.notifyChannelMessage]'s doc).
        channel ?: return

        val isViewingChannel = notificationService.activeChannelIndex == channel.index &&
            notificationService.activeChannelRadioID == channel.radioID
        if (!isViewingChannel) {
            channelStore.incrementChannelUnreadCount(channel.id)
            if (hasSelfMention) channelStore.incrementChannelUnreadMentionCount(channel.id)
        }

        notificationService.postChannelMessageNotification(
            channelName = channel.name,
            channelIndex = channelIndex,
            radioID = radioID,
            senderName = senderNodeName,
            messageText = message.text,
            messageID = message.id,
            notificationLevel = channel.notificationLevel,
            hasSelfMention = hasSelfMention,
        )
    }
}
