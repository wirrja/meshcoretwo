// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.notificationService
import com.meshcoretwo.services.connection.remoteNodeService
import com.meshcoretwo.services.connection.roomServerService
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RoomMessageDto
import com.meshcoretwo.services.remotenode.RoomServerEvent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

sealed class RoomConversationUiState {
    data object Loading : RoomConversationUiState()
    /** The session no longer resolves to a joined room (e.g. left/removed while this screen was open). */
    data object NotFound : RoomConversationUiState()
    data class Loaded(
        val session: RemoteNodeSessionDto,
        /** The contact backing [session]'s public key — used to route a "reconnect" tap to `NodeAuthScreen`. */
        val contactId: UUID?,
        val messages: List<RoomMessageDto>,
        val selfName: String?,
        /** Candidates for the composer's @mention autocomplete — every saved chat-type contact on
         * this radio, same source [ConversationViewModel.Loaded.mentionCandidates] uses for DM/
         * channel targets (a room has no smaller "relevant contacts" set to narrow to). */
        val mentionCandidates: List<ContactDto>,
    ) : RoomConversationUiState()
}

/**
 * Backs the room chat screen (`RoomConversationScreen.kt`) — a deliberately small slice of what
 * iOS's `RoomConversationViewModel` does, following the same "smallest usable slice" scoping
 * precedent as this port's other RemoteNodes UI view models (see `NodeAuthViewModel`/
 * `RoomStatusViewModel`'s class docs): no pagination beyond [MESSAGE_LOAD_LIMIT], no
 * translation/mention-tap-to-DM. Reply *is* ported — see [matchingContactsForSender]'s doc and
 * `RoomConversationScreen.kt`'s long-press menu. @mention autocomplete in the composer *is* ported
 * (see [RoomConversationUiState.Loaded.mentionCandidates]'s doc).
 *
 * Unlike [ConversationViewModel] (DM/channel), this has no [ConversationTarget] — a session id is
 * enough, and the data model ([RoomMessageDto], [RemoteNodeSessionDto]) doesn't overlap with
 * [ConversationViewModel]'s ([MessageDto], [ContactDto]/[ChannelDto]) closely enough to be worth
 * sharing state shape, matching how Swift itself keeps `RoomConversationViewModel` separate from
 * `ChatViewModel`.
 *
 * Reloads on [DeviceConnectionState.READY] entry (matching [ConversationViewModel]) and then on
 * every [RoomServerEvent] for this session — [RoomServerEvent.MessageReceived] (a new inbound or
 * self-echoed message), [RoomServerEvent.StatusUpdated] (an outbound send/retry resolving), and
 * [RoomServerEvent.ConnectionRecovered] (flips the disconnected banner off without a fresh login).
 *
 * Every successful [reload] marks this session read ([RoomServerService.markAsRead]) and claims
 * the notification service's active-conversation slot ([NotificationService.setActiveConversation]
 * with `roomSessionID`), same reasoning as [ConversationViewModel.reload]'s doc — both idempotent,
 * safe to re-run on every reload. [onCleared] releases the slot, but only if this session still
 * owns it — same race guard as [ConversationViewModel.onCleared].
 */
class RoomConversationViewModel(
    private val connectionManager: ConnectionManager,
    private val sessionId: UUID,
) : ViewModel() {
    private val _uiState = MutableStateFlow<RoomConversationUiState>(RoomConversationUiState.Loading)
    val uiState: StateFlow<RoomConversationUiState> = _uiState.asStateFlow()

    init {
        var eventJob: Job? = null
        viewModelScope.launch {
            connectionManager.connectionStateEvents.collect { state ->
                if (state == DeviceConnectionState.READY) {
                    reload()
                    eventJob?.cancel()
                    eventJob = viewModelScope.launch {
                        (connectionManager.roomServerService?.events() ?: emptyFlow()).collect { event ->
                            if (isRelevant(event)) reload()
                        }
                    }
                } else {
                    eventJob?.cancel()
                    _uiState.value = RoomConversationUiState.Loading
                }
            }
        }
    }

    private fun isRelevant(event: RoomServerEvent): Boolean = when (event) {
        is RoomServerEvent.MessageReceived -> event.message.sessionID == sessionId
        is RoomServerEvent.ConnectionRecovered -> event.sessionID == sessionId
        is RoomServerEvent.StatusUpdated -> true // Cheap enough to just reload; matches ConversationViewModel's reasoning.
    }

    private suspend fun reload() {
        val roomServerService = connectionManager.roomServerService
        val radioID = connectionManager.lastConnectedRadioID
        if (roomServerService == null || radioID == null) {
            _uiState.value = RoomConversationUiState.Loading
            return
        }

        val session = connectionManager.remoteNodeService?.fetchSession(sessionId)
        if (session == null || !session.isRoom) {
            _uiState.value = RoomConversationUiState.NotFound
            return
        }

        val contactId = connectionManager.contactService?.getContact(radioID, session.publicKey)?.id
        val messages = roomServerService.fetchMessages(sessionId, limit = MESSAGE_LOAD_LIMIT)
        val selfName = connectionManager.connectedDeviceRecord?.nodeName
        val mentionCandidates = connectionManager.contactService?.getContacts(radioID) ?: emptyList()

        _uiState.value = RoomConversationUiState.Loaded(session, contactId, messages, selfName, mentionCandidates)

        connectionManager.notificationService?.setActiveConversation(roomSessionID = sessionId)
        roomServerService.markAsRead(sessionId)
    }

    fun send(text: String) {
        val roomServerService = connectionManager.roomServerService ?: return
        if (_uiState.value !is RoomConversationUiState.Loaded) return

        viewModelScope.launch {
            try {
                roomServerService.postMessage(sessionId, text)
                reload()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // e.g. RoomServerError.PermissionDenied beating the input bar's own canPost gate in
                // a race — reload below still shows accurate state either way.
            } finally {
                reload()
            }
        }
    }

    /**
     * One-shot load of the contacts matching [senderName] for [SendDMSheet] — same
     * [SenderContactMatcher]-backed lookup as [ConversationViewModel.matchingContactsForSender],
     * duplicated rather than shared since the two view models have no common base to hang it on.
     */
    fun matchingContactsForSender(senderName: String, onResult: (List<ContactDto>) -> Unit) {
        viewModelScope.launch {
            val radioID = connectionManager.lastConnectedRadioID
            val contacts = radioID?.let { connectionManager.contactService?.getContacts(it) } ?: emptyList()
            onResult(SenderContactMatcher.filter(contacts, senderName, excludeBlocked = true))
        }
    }

    override fun onCleared() {
        val notificationService = connectionManager.notificationService ?: return
        if (notificationService.activeRoomSessionID == sessionId) notificationService.setActiveConversation()
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val sessionId: UUID,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RoomConversationViewModel(connectionManager, sessionId) as T
    }

    private companion object {
        const val MESSAGE_LOAD_LIMIT = 100
    }
}
