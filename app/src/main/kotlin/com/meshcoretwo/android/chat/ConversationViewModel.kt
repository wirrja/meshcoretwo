// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.contacts.routeLabel
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.channelService
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.discoveredNodeStore
import com.meshcoretwo.services.connection.heardRepeatsService
import com.meshcoretwo.services.connection.incomingMessageService
import com.meshcoretwo.services.connection.messageService
import com.meshcoretwo.services.connection.notificationService
import com.meshcoretwo.services.connection.pushChannelFloodScope
import com.meshcoretwo.services.connection.reactionService
import com.meshcoretwo.services.connection.rxLogService
import com.meshcoretwo.services.messages.IncomingMessageEvent
import com.meshcoretwo.services.messages.MessageService
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ChannelFloodScope
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.ReactionDto
import com.meshcoretwo.services.utilities.HashtagUtilities
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

sealed class ConversationUiState {
    data object Loading : ConversationUiState()
    /** The target no longer resolves to a contact/channel (e.g. removed while this screen was open). */
    data object NotFound : ConversationUiState()

    /** Another channel took over this conversation's slot — the screen must leave, not show the new occupant. */
    data object SlotReassigned : ConversationUiState()
    data class Loaded(
        val title: String,
        /** "Direct"/"N hops"/"Flood" for a [ConversationTarget.Direct] contact ([ContactDto.routeLabel]
         * — same field/labels [com.meshcoretwo.android.contacts.RouteChip] shows on the Contacts row),
         * `null` for a [ConversationTarget.Channel] (channels have no single-node route to summarize). */
        val subtitle: String?,
        val isEncrypted: Boolean,
        val messages: List<MessageDto>,
        val maxMessageBytes: Int,
        /** This radio's own advertised node name, for self-mention highlighting — `null` while disconnected. */
        val selfName: String?,
        /** Candidates for the composer's @mention autocomplete. See [ConversationViewModel]'s doc for what's deferred. */
        val mentionCandidates: List<ContactDto>,
        /** Backs the message-actions menu's quick-react row and the emoji picker's "Frequently Used" section. */
        val recentEmojis: List<String>,
        /** Flood region this channel currently sends with (per-channel override, else the device default);
         * `null` for DMs and for an "All regions"/un-scoped channel. Shown in the footer of outgoing messages. */
        val sendRegion: String? = null,
    ) : ConversationUiState()
}

/**
 * Backs the single-conversation screen (`ChatConversationScreen.kt`) — a deliberately small slice
 * of what iOS's `ChatViewModel`/`ChatTimeline` do for a conversation: no pagination beyond the
 * latest [MESSAGE_LOAD_LIMIT] messages, no reaction/translation indexing, no timeline "baking"
 * into render items. See PLAN.md's Phase 5 chat slice for the full list of deferrals.
 *
 * [ConversationUiState.Loaded.mentionCandidates] backs the composer's @mention autocomplete
 * (`ChatConversationScreen.kt`'s `ChatInputBar`, via [com.meshcoretwo.services.utilities
 * .MentionUtilities.filterContacts]) — every saved chat-type contact on this radio, for both DM and
 * channel targets, matching Swift's own DM-conversation branch. Swift's channel branch additionally
 * suggests contacts derived from channel senders who posted but were never saved (`chatViewModel
 * .channelSenders`, sorted by recency via `channelSenderOrder`) — this port has no channel-senders
 * index yet, so a channel conversation's suggestions are saved contacts only, alphabetical.
 *
 * Sends are optimistic the same way [MessageService.createPendingMessage] intends: the pending
 * row is persisted and then [reload] immediately shows it (as `.pending`/`.sending`), before the
 * actual radio send (which may take several retry-loop seconds) resolves to `.sent`/`.delivered`/
 * `.failed` — reload runs again after that completes either way, since a failure already persists
 * `.failed` via [MessageService]'s own bookkeeping (see its `failMessageAndRethrow`).
 *
 * Every successful [reload] also runs the "conversation was viewed" side effect Swift's
 * `ChatViewModel.loadMessages` runs after its own message load: marks this target the notification
 * service's active conversation (so [com.meshcoretwo.services.notifications.NotificationCoordinator]
 * skips the unread-badge increment and notification for messages arriving while this screen is
 * open — mirroring `ChatViewModel+Messages.swift`'s `setActiveConversation` call) and clears its
 * unread/mention badges via [com.meshcoretwo.services.contacts.ContactService.markConversationRead]/
 * [com.meshcoretwo.services.channels.ChannelService.markConversationRead]. Both are idempotent, so
 * running them on every reload (not just the first) is safe. [onCleared] releases the active-
 * conversation slot on the way out, but only if this target still owns it — same race guard as
 * Swift's `performCleanup`: a newer conversation's `init` may already have claimed the slot via
 * [com.meshcoretwo.services.notifications.NotificationService.setActiveConversation]'s atomic
 * clear-the-others semantics, and this teardown must not steal it back. Not ported:
 * `markFailedSendsSeen` (the failed-send banner it clears doesn't exist in this port either).
 *
 * A channel [reload] also runs [syncFloodScope] — pushing the channel's effective per-channel/
 * device-default flood scope to the radio session, memoized so repeated reloads don't re-push an
 * unchanged scope.
 *
 * [reload] also filters out [MessageDto.isHiddenOutgoingReaction] rows — a sent reaction is an
 * ordinary outgoing message on the wire, but renders as a badge under its target message (see
 * [ReactionBadgesRow]), not as its own timeline row, same as Swift's timeline never materializes
 * one either. [reactionService]'s events also trigger a [reload] like the other two merged event
 * sources, so both a locally-sent and an incoming reaction refresh
 * [ConversationUiState.Loaded.messages]' cached [MessageDto.reactionSummary] promptly — reload,
 * not an in-place [MessageDto] patch, to stay consistent with how [sendAgain]/[delete] already
 * refresh this screen, even though a per-reaction full reload is more work than Swift's O(1)
 * `updateReactionSummary` patch.
 */
class ConversationViewModel(
    private val connectionManager: ConnectionManager,
    private val target: ConversationTarget,
    private val recentEmojiStore: RecentEmojiStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ConversationUiState>(ConversationUiState.Loading)
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private var contact: ContactDto? = null
    private var channel: ChannelDto? = null

    /** The `(preference, deviceDefaultName)` pair last pushed to the session by [syncFloodScope] —
     * a no-op guard so a re-run [reload] (every incoming message/status event) doesn't re-push an
     * unchanged scope. Ported from `ChatViewModel.lastSetRegionScope`. */
    private var lastSyncedFloodScope: Pair<ChannelFloodScope, String?>? = null

    init {
        var eventJob: Job? = null
        var slotJob: Job? = null
        viewModelScope.launch {
            connectionManager.connectionStateEvents.collect { state ->
                if (state == DeviceConnectionState.READY) {
                    reload()
                    eventJob?.cancel()
                    eventJob = viewModelScope.launch {
                        merge(
                            connectionManager.incomingMessageService?.receivedEvents() ?: emptyFlow(),
                            connectionManager.messageService?.statusEvents() ?: emptyFlow(),
                            connectionManager.reactionService?.events() ?: emptyFlow(),
                            connectionManager.rxLogService?.regionUpdateEvents() ?: emptyFlow(),
                            // A repeat heard after send bumps heardRepeats (✓✓ / hop count) with no other event.
                            connectionManager.heardRepeatsService?.events() ?: emptyFlow(),
                        ).collect { event ->
                            if (isRelevant(event)) reload()
                        }
                    }
                    slotJob?.cancel()
                    slotJob = viewModelScope.launch {
                        connectionManager.channelService?.slotOccupantChanges()?.collect { change ->
                            if (target is ConversationTarget.Channel && target.index in change.indices) {
                                eventJob?.cancel()
                                _uiState.value = ConversationUiState.SlotReassigned
                            }
                        }
                    }
                } else {
                    eventJob?.cancel()
                    slotJob?.cancel()
                    _uiState.value = ConversationUiState.Loading
                }
            }
        }
    }

    private fun isRelevant(event: Any): Boolean = when (event) {
        is IncomingMessageEvent.DirectMessageReceived ->
            target is ConversationTarget.Direct && event.contact.id == target.contactId
        is IncomingMessageEvent.ChannelMessageReceived ->
            target is ConversationTarget.Channel && event.channelIndex == target.index
        else -> true // MessageStatusEvent/ReactionEvent — cheap enough to just reload; see class doc.
    }

    private suspend fun reload() {
        val radioID = connectionManager.lastConnectedRadioID
        val messageService = connectionManager.messageService
        if (radioID == null || messageService == null) {
            _uiState.value = ConversationUiState.Loading
            return
        }

        val selfName = connectionManager.connectedDeviceRecord?.nodeName
        val mentionCandidates = connectionManager.contactService?.getContacts(radioID) ?: emptyList()
        val recentEmojis = recentEmojiStore.load()

        when (target) {
            is ConversationTarget.Direct -> {
                val resolvedContact = connectionManager.contactService?.getContactById(target.contactId)
                if (resolvedContact == null) {
                    _uiState.value = ConversationUiState.NotFound
                    return
                }
                contact = resolvedContact
                val messages = messageService.getMessages(target.contactId, limit = MESSAGE_LOAD_LIMIT)
                    .filterNot { it.isHiddenOutgoingReaction(isDM = true) }
                _uiState.value = ConversationUiState.Loaded(
                    title = resolvedContact.displayName,
                    subtitle = resolvedContact.routeLabel(),
                    isEncrypted = true,
                    messages = messages,
                    maxMessageBytes = MessageService.MAX_DIRECT_MESSAGE_LENGTH,
                    selfName = selfName,
                    mentionCandidates = mentionCandidates,
                    recentEmojis = recentEmojis,
                )
                connectionManager.notificationService?.setActiveConversation(contactID = target.contactId)
                connectionManager.contactService?.markConversationRead(target.contactId)
            }
            is ConversationTarget.Channel -> {
                val resolvedChannel = connectionManager.channelService?.getChannel(radioID, target.index)
                if (resolvedChannel == null) {
                    _uiState.value = ConversationUiState.NotFound
                    return
                }
                channel = resolvedChannel
                val messages = messageService.getMessages(radioID, target.index, limit = MESSAGE_LOAD_LIMIT)
                    .filterNot { it.isHiddenOutgoingReaction(isDM = false) }
                val nodeNameBytes = (connectionManager.connectedDeviceRecord?.nodeName ?: "").toByteArray(Charsets.UTF_8).size
                _uiState.value = ConversationUiState.Loaded(
                    title = resolvedChannel.name,
                    subtitle = null,
                    isEncrypted = resolvedChannel.isEncryptedChannel,
                    selfName = selfName,
                    mentionCandidates = mentionCandidates,
                    messages = messages,
                    maxMessageBytes = MessageService.maxChannelMessageLength(nodeNameBytes),
                    recentEmojis = recentEmojis,
                    sendRegion = when (val scope = resolvedChannel.floodScope) {
                        is ChannelFloodScope.Region -> scope.name
                        is ChannelFloodScope.Inherit -> connectionManager.connectedDeviceRecord?.defaultFloodScopeName
                        is ChannelFloodScope.AllRegions -> null
                    }?.takeIf { it.isNotEmpty() },
                )
                connectionManager.notificationService?.setActiveConversation(channelIndex = target.index, channelRadioID = radioID)
                connectionManager.channelService?.markConversationRead(resolvedChannel.id)
                syncFloodScope(resolvedChannel)
            }
        }
    }

    /**
     * Pushes the device's session-scoped flood key to match [channel]'s effective flood scope — a
     * no-op when the desired scope already matches the last one this instance pushed. Ported from
     * `ChatViewModel+Channels.syncFloodScope`. The radio holds one flood-scope key session-wide, so
     * this must re-run whenever a channel conversation (re)loads, not just once per screen.
     */
    private suspend fun syncFloodScope(channel: ChannelDto) {
        val deviceDefault = connectionManager.connectedDeviceRecord?.defaultFloodScopeName
        val desired = channel.floodScope to deviceDefault
        if (lastSyncedFloodScope == desired) return

        try {
            connectionManager.pushChannelFloodScope(channel.floodScope)
            lastSyncedFloodScope = desired
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Logged nowhere yet (no logger threaded into this ViewModel) — matches this
            // being a best-effort sync; the next reload retries since lastSyncedFloodScope
            // was not updated.
        }
    }

    fun send(text: String) {
        val messageService = connectionManager.messageService ?: return
        val radioID = connectionManager.lastConnectedRadioID ?: return
        if (_uiState.value !is ConversationUiState.Loaded) return

        viewModelScope.launch {
            try {
                when (target) {
                    is ConversationTarget.Direct -> {
                        val contact = this@ConversationViewModel.contact ?: return@launch
                        val pending = messageService.createPendingMessage(text, contact)
                        reload()
                        try {
                            messageService.sendPendingDirectMessage(pending.id, contact)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            // Already persisted as .failed by MessageService; reload below shows it.
                        }
                    }
                    is ConversationTarget.Channel -> {
                        val pending = messageService.createPendingChannelMessage(text, target.index, radioID)
                        reload()
                        try {
                            messageService.sendPendingChannelMessage(pending.id)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            // Already persisted as .failed by MessageService; reload below shows it.
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // e.g. MessageServiceError.MessageTooLong beating the input bar's own byte-count
                // gate in a race, or a repeater somehow reached as a Direct target — reload below
                // still shows accurate state either way; viewModelScope has no default exception
                // handler, so leaving this uncaught would crash the whole app over one bad send.
            } finally {
                reload()
            }
        }
    }

    /**
     * The mesh name to `@[mention]` when replying to [message] — the contact's raw mesh name for a
     * DM (not [ContactDto.displayName]/nickname: a mention must match an actual node name to
     * resolve/highlight, see [com.meshcoretwo.services.utilities.MentionUtilities]), or the
     * incoming message's own sender name for a channel. Ported from `ChatConversationView
     * .handleReply`'s inline `mentionName` `switch`.
     */
    fun mentionNameForReply(message: MessageDto): String = when (target) {
        is ConversationTarget.Direct -> contact?.name ?: UNKNOWN_SENDER_NAME
        is ConversationTarget.Channel -> message.senderNodeName ?: UNKNOWN_SENDER_NAME
    }

    /**
     * Retransmits a previously-sent message — the message-actions menu's "Send Again". Ported from
     * `ChatViewModel+MessageActions.sendAgain`'s dispatch onto [MessageService.resendDirectMessage]/
     * [MessageService.resendChannelMessage], minus its own retry-loop bookkeeping (already inside
     * those two methods, not duplicated at this call site).
     */
    fun sendAgain(message: MessageDto) {
        val messageService = connectionManager.messageService ?: return
        viewModelScope.launch {
            try {
                when (target) {
                    is ConversationTarget.Direct -> {
                        val contact = this@ConversationViewModel.contact ?: return@launch
                        messageService.resendDirectMessage(message.id, contact)
                    }
                    is ConversationTarget.Channel -> messageService.resendChannelMessage(message.id)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Already persisted as .failed by MessageService; reload below shows it.
            } finally {
                reload()
            }
        }
    }

    /** Deletes a single message — the message-actions menu's "Delete". Ported from [MessageService.deleteMessage]'s call site, `ChatViewModel.deleteMessage`. */
    fun delete(message: MessageDto) {
        val messageService = connectionManager.messageService ?: return
        viewModelScope.launch {
            messageService.deleteMessage(message.id)
            reload()
        }
    }

    /**
     * Reacts to [message] with [emoji] — the message-actions menu's quick-react row/emoji picker,
     * and re-tapping an existing [ReactionBadgesRow] badge. Ported from
     * `ChatViewModel+Reactions.sendReaction`'s dispatch onto [com.meshcoretwo.services.reactions
     * .ReactionService.sendChannelReaction]/`.sendDMReaction`, minus its in-flight double-tap guard
     * (already inside those two methods via [com.meshcoretwo.services.persistence.ReactionStore
     * .reactionExists], not duplicated here) and its own `targetSenderName` computation for the
     * channel branch, ported inline below — mirrors `ChatViewModel+Reactions.swift`'s local
     * `if message.isOutgoing { localNodeName } else { message.senderNodeName }`, since
     * [com.meshcoretwo.services.reactions.ReactionService] takes that name as a plain parameter
     * rather than computing it itself (see that class's doc).
     *
     * [RecentEmojiStore.recordUsage] runs unconditionally before the send attempt, matching
     * `RecentEmojisStore.recordUsage`'s call site in `ChatConversationView.handleReact` — the quick-
     * react row should remember an emoji was picked even if the actual send later fails.
     */
    fun sendReaction(emoji: String, message: MessageDto) {
        val reactionService = connectionManager.reactionService ?: return
        val messageService = connectionManager.messageService ?: return
        val selfName = connectionManager.connectedDeviceRecord?.nodeName ?: return
        val currentRecents = (_uiState.value as? ConversationUiState.Loaded)?.recentEmojis ?: recentEmojiStore.load()
        recentEmojiStore.recordUsage(emoji, currentRecents)

        viewModelScope.launch {
            try {
                when (target) {
                    is ConversationTarget.Direct -> {
                        val contact = this@ConversationViewModel.contact ?: return@launch
                        reactionService.sendDMReaction(messageService, emoji, message, contact, selfName)
                    }
                    is ConversationTarget.Channel -> {
                        val radioID = connectionManager.lastConnectedRadioID ?: return@launch
                        val targetSenderName = if (message.isOutgoing) selfName else (message.senderNodeName ?: return@launch)
                        reactionService.sendChannelReaction(messageService, emoji, message, targetSenderName, target.index, radioID, selfName)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Best-effort; reload below reflects whatever actually persisted either way.
            } finally {
                reload()
            }
        }
    }

    /**
     * One-shot load of every reaction on [messageId] for [ReactionDetailsSheet], delivered via
     * [onResult] on the main thread — same callback shape as [findChannelIndexForHashtag].
     */
    fun fetchReactionDetails(messageId: UUID, onResult: (List<ReactionDto>) -> Unit) {
        viewModelScope.launch {
            onResult(connectionManager.reactionService?.fetchReactions(messageId) ?: emptyList())
        }
    }

    /**
     * One-shot load of the contacts matching [senderName] for [BlockSenderSheet]/[SendDMSheet],
     * already-blocked ones excluded — ported from those sheets' own `loadMatchingContacts`, backed
     * by [SenderContactMatcher] (this port has no `PersistenceStore.fetchContacts` distinct from
     * [com.meshcoretwo.services.contacts.ContactService.getContacts]). Delivered via [onResult] on
     * the main thread, same callback shape as [fetchReactionDetails].
     */
    fun matchingContactsForSender(senderName: String, onResult: (List<ContactDto>) -> Unit) {
        viewModelScope.launch {
            val radioID = connectionManager.lastConnectedRadioID
            val contacts = radioID?.let { connectionManager.contactService?.getContacts(it) } ?: emptyList()
            onResult(SenderContactMatcher.filter(contacts, senderName, excludeBlocked = true))
        }
    }

    /**
     * One-shot load of the data behind the message-actions menu's expandable "Repeat Details"/
     * "View Path" row, delivered via [onResult] on the main thread — same callback shape as
     * [fetchReactionDetails]. A no-op (never calls [onResult]) when [message] can show neither,
     * matching `MessageActionsSheet.swift`'s `.task` gate — no point fetching contacts/discovered
     * nodes when [MessageDetailsSection] won't render the expandable row at all.
     */
    fun fetchMessageDetails(message: MessageDto, onResult: (MessageDetailData) -> Unit) {
        val availability = MessageActionAvailability(message)
        if (!availability.showsPathDetail) return

        viewModelScope.launch {
            val radioID = connectionManager.lastConnectedRadioID
            val contacts = radioID?.let { connectionManager.contactService?.getContacts(it) } ?: emptyList()
            val discoveredNodes = radioID?.let { connectionManager.discoveredNodeStore?.fetchDiscoveredNodes(it) } ?: emptyList()
            val repeats = if (availability.canShowRepeatDetails || message.hasExtraIncomingPaths) {
                connectionManager.heardRepeatsService?.refreshRepeats(message.id) ?: emptyList()
            } else {
                null
            }
            onResult(MessageDetailData(repeats, contacts, discoveredNodes))
        }
    }

    /**
     * Blocks a channel sender name — the message-actions menu's "Block Sender". [contactIDs] is the
     * set of matching contacts the user additionally chose to block by identity in
     * [BlockSenderSheet]. Ported from `ChatConversationView.performBlock`'s call onto
     * [com.meshcoretwo.services.contacts.ContactService.blockChannelSender], which already handles
     * deleting the sender's channel messages and blocking those contacts.
     */
    fun blockSender(senderName: String, contactIDs: Set<UUID>) {
        val contactService = connectionManager.contactService ?: return
        val radioID = connectionManager.lastConnectedRadioID ?: return
        viewModelScope.launch {
            contactService.blockChannelSender(radioID, senderName, contactIDs)
            reload()
        }
    }

    /**
     * Releases the notification service's active-conversation slot, but only if this target still
     * owns it — see this class's doc for why a plain unconditional clear would race a newer
     * conversation's `init`.
     */
    override fun onCleared() {
        val notificationService = connectionManager.notificationService ?: return
        when (target) {
            is ConversationTarget.Direct -> {
                if (notificationService.activeContactID == target.contactId) notificationService.setActiveConversation()
            }
            is ConversationTarget.Channel -> {
                val radioID = channel?.radioID ?: return
                if (notificationService.activeChannelIndex == target.index && notificationService.activeChannelRadioID == radioID) {
                    notificationService.setActiveConversation()
                }
            }
        }
    }

    /**
     * Looks up an existing channel by [rawHashtag] (with or without a leading `#`), for a tapped
     * in-message hashtag link — ported from `ChatLinkRouter.handleHashtagTap`'s "already joined"
     * branch. Delivers the match's index via [onResult] on the main thread, or `null` when no
     * locally-joined channel has that name — the caller then falls back to the join-hashtag flow
     * instead (`AddChannelScreen`'s `initialHashtag`, this port's stand-in for the "not joined yet,
     * stage a pending join" branch — no separate confirmation sheet, same reasoning as the
     * deep-link slice's `initialLink`).
     */
    fun findChannelIndexForHashtag(rawHashtag: String, onResult: (UByte?) -> Unit) {
        viewModelScope.launch {
            val radioID = connectionManager.lastConnectedRadioID
            val channelService = connectionManager.channelService
            val normalizedName = "#" + HashtagUtilities.normalizeHashtagName(rawHashtag)
            val match = if (radioID != null && channelService != null) {
                channelService.getChannels(radioID).firstOrNull { it.name == normalizedName }?.index
            } else {
                null
            }
            onResult(match)
        }
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val target: ConversationTarget,
        private val prefs: SharedPreferences,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConversationViewModel(connectionManager, target, RecentEmojiStore(prefs)) as T
    }

    private companion object {
        const val MESSAGE_LOAD_LIMIT = 100
        const val UNKNOWN_SENDER_NAME = "Unknown"
    }
}
