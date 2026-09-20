// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.channelService
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.messageService
import com.meshcoretwo.services.connection.roomServerService
import com.meshcoretwo.services.messages.MessageService
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.RoomMessageDto
import com.meshcoretwo.services.remotenode.RoomServerService
import com.meshcoretwo.services.utilities.VContactIdentity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * One row's worth of display data: the conversation plus its most recent message, if any. Exactly
 * one of [lastMessage]/[roomLastMessage] is ever non-null, keyed by [Conversation]'s own case —
 * kept as two separate nullable fields (rather than a shared preview-text type) so the existing
 * [Conversation.Direct]/[Conversation.Channel] path through [MessageDto] is untouched by the
 * [Conversation.Room] addition.
 */
data class ConversationItem(val conversation: Conversation, val lastMessage: MessageDto?, val roomLastMessage: RoomMessageDto? = null)

sealed class ChatListUiState {
    data object Connecting : ChatListUiState()
    data class Ready(val items: List<ConversationItem>) : ChatListUiState()
}

/**
 * Backs the chat list screen (`ChatsListScreen.kt`) — the app-layer analogue of the list-building
 * half of iOS's `ChatViewModel` (`+ConversationCache`/`+ConversationList`). Unlike iOS's
 * monolithic `ChatViewModel`, which owns both the list and the open conversation and can just
 * mutate its in-memory `conversations`/`channels` arrays on e.g. a read/favorite/mute change,
 * Android splits list and conversation into separate view models with no shared state to
 * optimistically update — so instead this subscribes to [ContactService.observeContacts]/
 * [ChannelService.observeChannels]/[RoomServerService.observeRoomSessions], live Room queries that
 * re-emit on any write to the `contacts`/`channels`/`remote_node_sessions` tables (new contact,
 * `markConversationRead`'s counter reset, a message's `lastMessageDate` bump, block/favorite
 * toggle, a room's unread count or connection state changing, ...). Every message-affecting write
 * already touches one of those rows (see `ContactStore.updateContactLastMessage`/
 * `ChannelStore.updateChannelLastMessage`/`RemoteNodeSessionStore.updateRoomActivity`, called from
 * every send/receive path in [MessageService]/`IncomingMessageService`/[RoomServerService]), so
 * this needs no separate message-event subscription — [DeviceConnectionState.READY] entry/exit is
 * the only other trigger.
 *
 * Per-conversation "last message" preview is still fetched one conversation at a time
 * (`getMessages(..., limit = 1)`/`RoomServerService.fetchMessages(..., limit = 1)`, re-run on
 * every list emission) rather than iOS's batched `fetchLastMessages(contactIDs:)` — [MessageService]
 * has no batch-fetch equivalent yet; a fine simplification at this app's scale (a handful to a few
 * dozen contacts/channels/rooms). Message *status* changes (e.g. a failed send) don't currently
 * affect anything this screen renders (see `ChatsListScreen.kt`'s `previewText()`), so they don't
 * need to trigger a re-fetch — revisit if a status-dependent row indicator is ported later.
 *
 * Each [Conversation.Room]'s [Conversation.Room.contactId] is resolved here too (one
 * [ContactService.getContact] lookup per session, by [RoomServerService]'s reported full public
 * key) — see that field's class doc for why.
 */
class ChatListViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _uiState = MutableStateFlow<ChatListUiState>(ChatListUiState.Connecting)
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    init {
        // A fresh ServiceContainer is built per connection, so the observe() subscription below
        // is re-established every time READY is (re-)entered rather than once at init —
        // subscribing once up front could bind to a stale/absent container from before this
        // ViewModel's first connection.
        var observeJob: Job? = null
        viewModelScope.launch {
            connectionManager.connectionStateEvents.collect { state ->
                observeJob?.cancel()
                val radioID = connectionManager.lastConnectedRadioID
                val contactService = connectionManager.contactService
                val channelService = connectionManager.channelService
                val messageService = connectionManager.messageService
                val roomServerService = connectionManager.roomServerService
                if (state == DeviceConnectionState.READY && radioID != null && contactService != null &&
                    channelService != null && messageService != null
                ) {
                    observeJob = viewModelScope.launch {
                        combine(
                            contactService.observeContacts(radioID),
                            channelService.observeChannels(radioID),
                            roomServerService?.observeRoomSessions(radioID) ?: flowOf(emptyList()),
                        ) { contacts, channels, sessions ->
                            val rooms = sessions.map { session ->
                                Conversation.Room(session, contactService.getContact(radioID, session.publicKey)?.id)
                            }
                            buildConversationList(contacts, channels, rooms)
                        }.collectLatest { conversations ->
                            _uiState.value = ChatListUiState.Ready(buildItems(conversations, radioID, messageService, roomServerService))
                        }
                    }
                } else {
                    _uiState.value = ChatListUiState.Connecting
                }
            }
        }
    }

    private suspend fun buildItems(
        conversations: List<Conversation>,
        radioID: UUID,
        messageService: MessageService,
        roomServerService: RoomServerService?,
    ): List<ConversationItem> = conversations.map { conversation ->
        when (conversation) {
            is Conversation.Direct ->
                ConversationItem(conversation, messageService.getMessages(conversation.contact.id, limit = 1).firstOrNull())
            is Conversation.Channel ->
                ConversationItem(conversation, messageService.getMessages(radioID, conversation.channel.index, limit = 1).firstOrNull())
            is Conversation.Room ->
                ConversationItem(conversation, lastMessage = null, roomLastMessage = roomServerService?.fetchMessages(conversation.session.id, limit = 1)?.lastOrNull())
        }
    }

    /** The virtual self-contact can't be removed — same rule as `ContactDetailViewModel.delete`. */
    fun canDelete(contact: ContactDto): Boolean {
        val selfPublicKey = connectionManager.connectedDeviceRecord?.publicKey ?: return true
        return !VContactIdentity.isVContact(contact.publicKey, selfPublicKey)
    }

    /** Long-press "Mark as read": resets the unread badges of any conversation kind, messages untouched. */
    fun markRead(conversation: Conversation) = runAction {
        when (conversation) {
            is Conversation.Direct -> connectionManager.contactService?.markConversationRead(conversation.contact.id)
            is Conversation.Channel -> connectionManager.channelService?.markConversationRead(conversation.channel.id)
            is Conversation.Room -> connectionManager.roomServerService?.markAsRead(conversation.session.id)
        }
    }

    fun toggleFavorite(conversation: Conversation) = runAction {
        val favorite = !conversation.isFavorite
        when (conversation) {
            is Conversation.Direct ->
                connectionManager.contactService?.updateContactPreferences(conversation.contact.id, isFavorite = favorite)
            is Conversation.Channel -> connectionManager.channelService?.setFavorite(conversation.channel.id, favorite)
            is Conversation.Room -> connectionManager.roomServerService?.setFavorite(conversation.session.id, favorite)
        }
    }

    /** Same as the channel info screen's "Leave Channel": clears the slot on the radio and drops the local row. */
    fun leaveChannel(channel: ChannelDto) = runAction {
        connectionManager.channelService?.clearChannel(channel.radioID, channel.index)
    }

    fun setBlocked(contact: ContactDto, blocked: Boolean) = runAction {
        connectionManager.contactService?.updateContactPreferences(contact.id, isBlocked = blocked)
    }

    fun delete(contact: ContactDto) {
        if (!canDelete(contact)) return
        runAction {
            val radioID = connectionManager.lastConnectedRadioID ?: return@runAction
            connectionManager.contactService?.removeContact(radioID, contact.publicKey)
        }
    }

    /**
     * No refetch afterwards — the list subscribes to live Room queries (see the class doc), so the
     * write itself re-emits. A thrown [Exception] (radio NOT_FOUND, session error) is swallowed:
     * [viewModelScope] has no default handler, so it would otherwise crash the process.
     */
    private fun runAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // See doc above.
            }
        }
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatListViewModel(connectionManager) as T
    }
}
