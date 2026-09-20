// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.persistence.ReactionStore
import com.meshcoretwo.services.messages.MessageService
import com.meshcoretwo.services.remotenode.RoomServerService
import com.meshcoretwo.services.sync.SyncCoordinator
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Executes the multi-service transactions behind notification actions: quick reply,
 * mark-as-read, and reaction notifications. Ported from `NotificationActionHandler.swift`.
 *
 * Owned by the composition root (not yet ported — see [com.meshcoretwo.services.connection]'s
 * `ConnectionManager` slice status); the `app` layer will install [NotificationService]
 * forwarders that delegate here and inject the two app-layer inputs (connection readiness, local
 * node name) via [configure]. Not wired to a real caller yet in this port — this class is the
 * transaction layer itself, testable in isolation against the real stores/services it composes.
 *
 * Takes individual stores ([ContactStore], [ChannelStore], [MessageStore], [ReactionStore])
 * rather than Swift's single `PersistenceStoreProtocol`, matching this port's per-domain-store
 * convention (see [com.meshcoretwo.services.persistence.ContactStore]'s sibling classes).
 *
 * `@MainActor` isolation has no Kotlin equivalent need here: [isConnectionReady]/[localNodeName]
 * are plain (non-`@MainActor`) closures, since this port has no UI thread affinity requirement at
 * the services layer.
 */
class NotificationActionHandler(
    private val contactStore: ContactStore,
    private val channelStore: ChannelStore,
    private val messageStore: MessageStore,
    private val reactionStore: ReactionStore,
    private val messageService: MessageService,
    private val notificationService: NotificationService,
    private val roomServerService: RoomServerService,
    private val syncCoordinator: SyncCoordinator,
) {
    /** Whether the connection is ready for sends. Injected via [configure] so every call reads the live app-facing connection state. */
    private var isConnectionReady: () -> Boolean = { false }

    /**
     * The connected device's node name, used to suppress self-reaction notifications. Null means
     * [configure] has not yet been called; a non-null closure that returns null means configured
     * but the device name is not yet known.
     */
    private var localNodeName: (() -> String?)? = null

    /** Whether [configure] has been called. Used to distinguish the pre-wiring window from the steady-state where node name may legitimately be null. */
    val isConfigured: Boolean get() = localNodeName != null

    /** Injects the app-layer inputs. Idempotent; re-run per connection when notification handling is configured. */
    fun configure(isConnectionReady: () -> Boolean, localNodeName: () -> String?) {
        this.isConnectionReady = isConnectionReady
        this.localNodeName = localNodeName
    }

    // MARK: - Quick Reply

    suspend fun handleQuickReply(contactID: UUID, text: String) {
        val contact = contactStore.fetchContact(contactID) ?: return

        if (isConnectionReady()) {
            try {
                messageService.sendDirectMessage(text, contact)

                // Clear unread state - user replied so they've seen the chat
                contactStore.clearUnreadCount(contactID)
                notificationService.removeDeliveredNotificationsForContact(contactID)
                syncCoordinator.notifyConversationsChanged()
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Fall through to draft handling
            }
        }

        notificationService.saveDraft(contactID, text)
        notificationService.postQuickReplyFailedNotification(contact.displayName, contactID)
    }

    suspend fun handleChannelQuickReply(radioID: UUID, channelIndex: UByte, text: String) {
        // Fetch channel for display name in failure notification
        val channel = channelStore.fetchChannel(radioID, channelIndex)
        val channelName = channelDisplayName(channel?.name, channelIndex)

        if (!isConnectionReady()) {
            notificationService.postChannelQuickReplyFailedNotification(channelName, radioID, channelIndex)
            return
        }

        try {
            messageService.sendChannelMessage(text, channelIndex, radioID)

            // Clear unread state - user replied so they've seen the channel
            channel?.let { channelStore.clearChannelUnreadCount(it.id) }
            notificationService.removeDeliveredNotificationsForChannel(channelIndex, radioID)
            syncCoordinator.notifyConversationsChanged()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            notificationService.postChannelQuickReplyFailedNotification(channelName, radioID, channelIndex)
        }
    }

    /** Resolves a channel's display name, preferring the stored name, then the localized fallback, then a last-resort English literal. */
    private fun channelDisplayName(name: String?, index: UByte): String =
        name ?: notificationService.stringProvider?.defaultChannelName(index.toInt()) ?: "Channel $index"

    // MARK: - Mark as Read

    suspend fun handleMarkAsRead(contactID: UUID, messageID: UUID) {
        try {
            messageStore.markMessageAsRead(messageID)
            contactStore.clearUnreadCount(contactID)
            notificationService.removeDeliveredNotification(messageID)
            syncCoordinator.notifyConversationsChanged()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Silently ignore
        }
    }

    suspend fun handleChannelMarkAsRead(radioID: UUID, channelIndex: UByte, messageID: UUID) {
        try {
            messageStore.markMessageAsRead(messageID)
            channelStore.fetchChannel(radioID, channelIndex)?.let { channelStore.clearChannelUnreadCount(it.id) }
            notificationService.removeDeliveredNotification(messageID)
            syncCoordinator.notifyConversationsChanged()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Silently ignore
        }
    }

    suspend fun handleRoomMarkAsRead(sessionID: UUID, messageID: UUID) {
        try {
            roomServerService.markAsRead(sessionID)
            notificationService.removeDeliveredNotification(messageID)
            syncCoordinator.notifyConversationsChanged()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Silently ignore
        }
    }

    // MARK: - Reactions

    /** Handles posting a notification when someone reacts to the user's message. */
    suspend fun handleReactionNotification(messageID: UUID) {
        // Suppress the notification entirely when configure() has not yet been called. Posting
        // during that window risks notifying the user about their own reaction; missing a
        // stranger's reaction for a moment is harmless.
        val localNodeNameProvider = localNodeName ?: return

        // Fetch the message to check if it's outgoing
        val message = messageStore.fetchMessage(messageID)?.takeIf { it.isOutgoing } ?: return

        // Fetch the latest reaction for this message
        val latestReaction = reactionStore.fetchReactions(messageID, limit = 1).firstOrNull() ?: return

        // Check if this is a self-reaction (user reacting to their own message)
        val nodeName = localNodeNameProvider()
        if (nodeName != null && latestReaction.senderName == nodeName) return

        // Check mute status based on message type
        val isMuted = when {
            message.contactID != null -> contactStore.fetchContact(message.contactID)?.isMuted ?: false
            message.channelIndex != null ->
                channelStore.fetchChannel(message.radioID, message.channelIndex)?.notificationLevel == NotificationLevel.MUTED
            else -> false
        }
        if (isMuted) return

        val truncatedPreview = reactionPreview(message.text)
        val body = notificationService.stringProvider?.reactionNotificationBody(latestReaction.emoji, truncatedPreview)
            ?: "Reacted ${latestReaction.emoji} to your message: \"$truncatedPreview\""

        notificationService.postReactionNotification(
            reactorName = latestReaction.senderName,
            body = body,
            messageID = messageID,
            contactID = message.contactID,
            channelIndex = message.channelIndex,
            radioID = if (message.channelIndex != null) message.radioID else null,
        )
    }

    companion object {
        private const val REACTION_PREVIEW_MAX_LENGTH = 50
        private const val REACTION_PREVIEW_KEEP_LENGTH = 47
        private const val REACTION_PREVIEW_ELLIPSIS = "..."

        /** Truncates a reacted-to message for display in the notification body. */
        fun reactionPreview(text: String): String =
            if (text.length > REACTION_PREVIEW_MAX_LENGTH) text.take(REACTION_PREVIEW_KEEP_LENGTH) + REACTION_PREVIEW_ELLIPSIS else text
    }
}
