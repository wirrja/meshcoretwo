// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.CompactSearchTopBar
import com.meshcoretwo.android.ui.components.ConfirmDialog
import com.meshcoretwo.android.ui.components.ConnectingState
import com.meshcoretwo.android.ui.components.EmptyState
import com.meshcoretwo.android.ui.components.listCard
import com.meshcoretwo.android.ui.components.FilterChipRow
import com.meshcoretwo.android.ui.components.HeaderIconButton
import com.meshcoretwo.android.ui.components.PresenceAvatar
import com.meshcoretwo.android.ui.formatRelativeTimestamp
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.MessageDto
import java.util.UUID

private enum class ChatFilter(@StringRes val labelRes: Int) {
    ALL(R.string.common_all),
    UNREAD(R.string.common_unread),
    FAVORITES(R.string.common_favorites),
}

/**
 * Chat list — the app's post-onboarding home screen. Ported from `ChatsView.swift`/
 * `ConversationListContent.swift`/`ConversationRow.swift`/`ChannelConversationRow.swift`. Search
 * and the All/Unread/Favorites filter ([ChatFilter], `ChatFilterPicker` on iOS) are a client-side
 * filter over [ChatListViewModel.uiState] — same simplification
 * `com.meshcoretwo.android.contacts.ContactsListScreen` already makes for its own segment tabs, no
 * new ViewModel state. A long press opens a menu: mark as read and favorite toggle (any conversation
 * kind), leave for channels, block and delete-from-contacts for direct chats. Still deferred: swipe
 * actions and mute in that menu, no "failed send" indicator, no mention-count-specific badge styling — see
 * [ChatListViewModel]'s class doc and PLAN.md's Phase 5 chat slice for the full deferral list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListScreen(connectionManager: ConnectionManager, onOpenConversation: (Conversation) -> Unit, onAddChannel: () -> Unit) {
    val viewModel: ChatListViewModel = viewModel(factory = ChatListViewModel.Factory(connectionManager))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ChatFilter.ALL) }
    var menuConversationId by remember { mutableStateOf<UUID?>(null) }
    var blockCandidate by remember { mutableStateOf<ContactDto?>(null) }
    var deleteCandidate by remember { mutableStateOf<ContactDto?>(null) }
    var leaveCandidate by remember { mutableStateOf<ChannelDto?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CompactSearchTopBar(
                title = stringResource(R.string.chats_title),
                connectionManager = connectionManager,
                actions = { HeaderIconButton(onClick = onAddChannel) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.add_channel_title)) } },
                searchQuery = query,
                onSearchQueryChange = { query = it },
                searchPlaceholder = stringResource(R.string.chats_search),
                filters = {
                    FilterChipRow(items = ChatFilter.entries, selected = filter, onSelect = { filter = it }, label = { stringResource(it.labelRes) })
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            is ChatListUiState.Connecting -> ConnectingState(modifier = Modifier.padding(padding))
            is ChatListUiState.Ready -> {
                if (current.items.isEmpty()) {
                    EmptyChatsContent(modifier = Modifier.padding(padding))
                } else {
                    val filtered = current.items.filter(query, filter)
                    if (filtered.isEmpty()) {
                        NoMatchesContent(modifier = Modifier.padding(padding))
                    } else {
                        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                            items(filtered, key = { it.conversation.id }) { item ->
                                val conversation = item.conversation
                                Box(modifier = Modifier.animateItem()) {
                                    ConversationListRow(
                                        item = item,
                                        onClick = { onOpenConversation(conversation) },
                                        onLongClick = { menuConversationId = conversation.id },
                                    )
                                    DropdownMenu(expanded = menuConversationId == conversation.id, onDismissRequest = { menuConversationId = null }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chats_mark_read)) },
                                            enabled = conversation.unreadCount > 0,
                                            onClick = { menuConversationId = null; viewModel.markRead(conversation) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(if (conversation.isFavorite) R.string.chats_remove_favorite else R.string.chats_add_favorite)) },
                                            onClick = { menuConversationId = null; viewModel.toggleFavorite(conversation) },
                                        )
                                        if (conversation is Conversation.Channel) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.chats_leave_channel), color = MaterialTheme.colorScheme.error) },
                                                onClick = { menuConversationId = null; leaveCandidate = conversation.channel },
                                            )
                                        }
                                        if (conversation is Conversation.Direct) {
                                            val contact = conversation.contact
                                            DropdownMenuItem(
                                                text = { Text(stringResource(if (contact.isBlocked) R.string.common_unblock else R.string.common_block)) },
                                                onClick = {
                                                    menuConversationId = null
                                                    if (contact.isBlocked) viewModel.setBlocked(contact, false) else blockCandidate = contact
                                                },
                                            )
                                            if (viewModel.canDelete(contact)) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.chats_delete_from_contacts), color = MaterialTheme.colorScheme.error) },
                                                    onClick = { menuConversationId = null; deleteCandidate = contact },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    blockCandidate?.let { contact ->
        ConfirmDialog(
            title = stringResource(R.string.chats_block_title, contact.displayName),
            body = stringResource(R.string.chats_block_body),
            confirmLabel = stringResource(R.string.common_block),
            onConfirm = { viewModel.setBlocked(contact, true) },
            onDismiss = { blockCandidate = null },
        )
    }
    leaveCandidate?.let { channel ->
        ConfirmDialog(
            title = stringResource(R.string.channel_info_leave_title, channel.name),
            body = stringResource(R.string.channel_info_leave_body),
            confirmLabel = stringResource(R.string.common_leave),
            onConfirm = { viewModel.leaveChannel(channel) },
            onDismiss = { leaveCandidate = null },
        )
    }
    deleteCandidate?.let { contact ->
        ConfirmDialog(
            title = stringResource(R.string.chats_delete_title, contact.displayName),
            body = stringResource(R.string.chats_delete_body),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = { viewModel.delete(contact) },
            onDismiss = { deleteCandidate = null },
        )
    }
}

private fun List<ConversationItem>.filter(query: String, filter: ChatFilter): List<ConversationItem> = this
    .filter { item ->
        when (filter) {
            ChatFilter.ALL -> true
            ChatFilter.UNREAD -> item.conversation.unreadCount > 0
            ChatFilter.FAVORITES -> item.conversation.isFavorite
        }
    }
    .filter { item -> query.isBlank() || item.conversation.displayName.contains(query, ignoreCase = true) }

@Composable
private fun EmptyChatsContent(modifier: Modifier = Modifier) {
    EmptyState(
        icon = R.drawable.ic_chat,
        title = stringResource(R.string.chats_empty_title),
        description = stringResource(R.string.chats_empty_desc),
        modifier = modifier,
    )
}

@Composable
private fun NoMatchesContent(modifier: Modifier = Modifier) {
    EmptyState(
        icon = R.drawable.ic_search,
        title = stringResource(R.string.path_no_matches),
        description = stringResource(R.string.chats_no_match_desc),
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListRow(item: ConversationItem, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val conversation = item.conversation
    val hasUnread = conversation.unreadCount > 0 && !conversation.isMuted
    Row(
        modifier = modifier.listCard(onClick = onClick, onLongClick = onLongClick, emphasized = hasUnread).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceAvatar(
            name = conversation.displayName,
            lastHeard = conversation.lastHeard,
            category = conversation.avatarCategory,
            isPublicChannel = conversation.isPublicChannel,
            size = 52.dp,
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Name + badges share one weighted group; the timestamp sits after it at natural
                // width. (Two weighted siblings — name and a spacer — split the space 50/50 and
                // pushed the time leftwards whenever the name was long.)
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.isMuted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            painterResource(R.drawable.ic_notifications_off),
                            contentDescription = stringResource(R.string.notif_level_muted),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (conversation.isFavorite) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            painterResource(R.drawable.ic_star),
                            contentDescription = stringResource(R.string.common_favorite),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                conversation.lastMessageDate?.let { date ->
                    Text(
                        text = formatRelativeTimestamp(date),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.size(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.previewText(stringResource(R.string.chats_no_messages)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasUnread) {
                    Spacer(modifier = Modifier.width(8.dp))
                    UnreadBadge(count = conversation.unreadCount)
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Message body, or a placeholder for message types with no useful plain-text preview. */
private fun MessageDto.previewText(): String = text.ifEmpty { "…" }

/** Last-message preview text for a row, whichever of [ConversationItem.lastMessage]/
 * [ConversationItem.roomLastMessage] is set — see that class's doc for why there are two. */
private fun ConversationItem.previewText(noMessagesText: String): String =
    lastMessage?.previewText() ?: roomLastMessage?.text?.ifEmpty { "…" } ?: noMessagesText
