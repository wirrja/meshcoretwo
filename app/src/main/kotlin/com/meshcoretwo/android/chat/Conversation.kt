// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.android.contacts.toInstantOrNull
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import java.time.Instant
import java.util.UUID

/**
 * A row in the chat list — a direct contact, a channel, or a joined room. Ported from
 * `Conversation.swift`. Unlike Swift's `.room(RemoteNodeSessionDTO)`, this port's [Room] case also
 * carries [Room.contactId]: the chat list needs a contact id to reach `NodeAuthScreen` (which
 * takes a contact id, not a session id) when the tapped room is disconnected. Swift's tap handler
 * resolves that separately from `ChatViewModel`'s own contact cache; this port just carries it
 * alongside since nothing else here already indexes contacts by session.
 *
 * Lives in `app`, not `services`, matching where the Swift source itself puts `Conversation` and
 * the `ChatViewModel` that builds it (`MC1/Views/Chats/...`, not `MC1Services`) — this is
 * UI-shaping logic, not a service.
 */
sealed class Conversation {
    data class Direct(val contact: ContactDto) : Conversation()
    data class Channel(val channel: ChannelDto) : Conversation()

    /**
     * A joined room, surfaced in the chat list alongside contacts/channels (Swift's `.room` case).
     * [contactId] is the id of the [ContactDto] backing [session.publicKey][RemoteNodeSessionDto
     * .publicKey] — resolved by the caller (a session→contact join needs a suspend lookup this
     * file's pure [buildConversationList] doesn't do), `null` only if that contact was somehow
     * removed without cascading the session (shouldn't happen — session deletion is cascaded from
     * contact/device deletion elsewhere).
     */
    data class Room(val session: RemoteNodeSessionDto, val contactId: UUID?) : Conversation()

    val id: UUID
        get() = when (this) {
            is Direct -> contact.id
            is Channel -> channel.id
            is Room -> session.id
        }

    val displayName: String
        get() = when (this) {
            is Direct -> contact.displayName
            is Channel -> channel.name
            is Room -> session.name
        }

    val lastMessageDate: Instant?
        get() = when (this) {
            is Direct -> contact.lastMessageDate
            is Channel -> channel.lastMessageDate
            is Room -> session.lastMessageDate
        }

    val unreadCount: Int
        get() = when (this) {
            is Direct -> contact.unreadCount
            is Channel -> channel.unreadCount
            is Room -> session.unreadCount
        }

    val isFavorite: Boolean
        get() = when (this) {
            is Direct -> contact.isFavorite
            is Channel -> channel.isFavorite
            is Room -> session.isFavorite
        }

    val isMuted: Boolean
        get() = when (this) {
            is Direct -> contact.isMuted
            is Channel -> channel.notificationLevel == NotificationLevel.MUTED
            is Room -> session.notificationLevel == NotificationLevel.MUTED
        }

    /** `null` for [Channel]/[Room] — neither has a single "last heard" instant like a contact does. */
    val lastHeard: Instant?
        get() = (this as? Direct)?.contact?.lastHeardTimestamp?.toInstantOrNull()

    /** `null` for a regular [Direct] chat contact — its avatar draws a per-name identity color instead. */
    val avatarCategory: AvatarCategory?
        get() = when (this) {
            is Direct -> AvatarCategory.fromContactType(contact.type)
            is Channel -> AvatarCategory.CHANNEL
            is Room -> AvatarCategory.ROOM
        }

    val isPublicChannel: Boolean
        get() = (this as? Channel)?.channel?.isPublicChannel ?: false
}

/**
 * Fallback sort key for a conversation with no messages, so it sorts to the end rather than the
 * (epoch-zero-adjacent) start. Ported from `ChatViewModel.noMessageSentinel`.
 */
private val noMessageSentinel: Instant = Instant.MIN

/**
 * Merges contacts and channels into the chat list's row order: favorites first, each group
 * sorted by [Conversation.lastMessageDate] descending. Ported from
 * `ChatViewModel+ConversationCache.recomputeSnapshot()`/`sortedByLastMessage()`, minus the
 * `pendingRemovalIDs` masking (no optimistic-delete UI yet) and the room-session branch (see
 * [Conversation]'s doc).
 *
 * [contacts] and [channels] are expected pre-fetched (e.g. via `ContactService.getContacts`/
 * `ChannelService.getChannels`) — this function only shapes and filters/sorts them:
 * - Contacts: excludes repeaters and blocked contacts (`type != .repeater && !isBlocked`).
 * - Channels: excludes unconfigured slots (`name.isEmpty && !hasSecret`).
 *
 * [rooms], unlike [contacts]/[channels], arrives pre-shaped as [Conversation.Room] rather than a
 * raw DTO list — building each one needs an async contact lookup (see that case's doc), which this
 * otherwise-synchronous function can't do itself; the caller ([ChatListViewModel]) does that join
 * before calling in. No filtering is applied here — every session [RoomServerService
 * .observeRoomSessions] emits is already room-only.
 */
fun buildConversationList(contacts: List<ContactDto>, channels: List<ChannelDto>, rooms: List<Conversation.Room> = emptyList()): List<Conversation> {
    val direct = contacts
        .filter { it.type != ContactType.REPEATER && !it.isBlocked }
        .map { Conversation.Direct(it) }
    val channel = channels
        .filter { it.name.isNotEmpty() || it.hasSecret }
        .map { Conversation.Channel(it) }
    val all = direct + channel + rooms

    fun sortedByLastMessage(items: List<Conversation>) =
        items.sortedByDescending { it.lastMessageDate ?: noMessageSentinel }

    val (favorites, others) = all.partition { it.isFavorite }
    return sortedByLastMessage(favorites) + sortedByLastMessage(others)
}
