// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.chat.linkify.LinkToken
import com.meshcoretwo.android.chat.linkify.MessageLinkTokenizer
import com.meshcoretwo.android.chat.linkify.styledMessageText
import com.meshcoretwo.android.ui.components.FullScreenMessage
import com.meshcoretwo.android.ui.components.LoadingScreen
import com.meshcoretwo.android.ui.theme.LocalAppTheme
import com.meshcoretwo.android.ui.theme.LocalIsDarkTheme
import com.meshcoretwo.android.ui.theme.identityColor
import com.meshcoretwo.android.ui.theme.incomingBubbleColor
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.messages.MessageService
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.RoomMessageDto
import java.time.Duration
import java.util.UUID

/**
 * Room chat screen — a dedicated counterpart to [ChatConversationScreen] for joined rooms, ported
 * from `RoomConversationView.swift`/`Components/RoomMessageBubble.swift`. Kept separate rather
 * than folded into [ChatConversationScreen] (see [RoomConversationViewModel]'s doc for why): the
 * message shape ([RoomMessageDto], multi-party, keyed on [RoomMessageDto.authorKeyPrefix]) and the
 * connection/permission gating (disconnected / read-only-guest / can-post) don't overlap with a
 * DM/channel conversation closely enough to share code — matching Swift's own split. Trimmed to a
 * first slice, same deferrals as [RoomConversationViewModel]'s doc: no reactions/translation. A
 * long-press opens [RoomMessageActionsSheet] (reply/send DM/copy/send again — see that class's doc
 * for why it's smaller than [MessageActionsSheet]).
 *
 * The chat list only ever navigates here for an already-connected session (see its tap handler in
 * `MainScreen.kt`) — [onReconnect] exists only for the session disconnecting *while* this screen
 * is already open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomConversationScreen(
    connectionManager: ConnectionManager,
    sessionId: UUID,
    onBack: () -> Unit,
    onOpenRoomInfo: () -> Unit,
    onReconnect: (contactId: UUID) -> Unit,
    onOpenConversation: (UUID) -> Unit = {},
) {
    val viewModel: RoomConversationViewModel = viewModel(factory = RoomConversationViewModel.Factory(connectionManager, sessionId))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val connectionState by connectionManager.connectionStateEvents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var actionsMessage by remember { mutableStateOf<RoomMessageDto?>(null) }
    var sendDMSenderName by remember { mutableStateOf<String?>(null) }
    var pendingReplyMentionName by remember { mutableStateOf<String?>(null) }

    // Rooms have no channel/hashtag/contact-share context to route these to (unlike
    // ChatConversationScreen's onLinkClick) — a plain URL still opens, everything else is
    // display-only for this slice.
    fun onLinkClick(token: LinkToken) {
        if (token.kind != LinkToken.Kind.URL) return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(token.value)))
        } catch (error: ActivityNotFoundException) {
            // No browser installed; there is nothing sensible to fall back to.
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text((state as? RoomConversationUiState.Loaded)?.session?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) }
                },
                actions = {
                    IconButton(onClick = onOpenRoomInfo) { Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.chat_room_info)) }
                },
            )
        },
        bottomBar = {
            val loaded = state as? RoomConversationUiState.Loaded
            if (loaded != null) {
                when {
                    !loaded.session.isConnected -> DisconnectedRoomBanner(onClick = { loaded.contactId?.let(onReconnect) })
                    loaded.session.canPost -> ChatInputBar(
                        maxBytes = MessageService.MAX_DIRECT_MESSAGE_LENGTH,
                        canSendConnection = connectionState == DeviceConnectionState.READY,
                        mentionCandidates = loaded.mentionCandidates,
                        onSend = viewModel::send,
                        pendingReplyMentionName = pendingReplyMentionName,
                        onPendingReplyMentionHandled = { pendingReplyMentionName = null },
                    )
                    else -> ReadOnlyRoomBanner()
                }
            }
        },
    ) { padding ->
        when (val current = state) {
            is RoomConversationUiState.Loading -> LoadingScreen(modifier = Modifier.padding(padding))
            is RoomConversationUiState.NotFound -> FullScreenMessage(
                stringResource(R.string.chat_room_unavailable),
                modifier = Modifier.padding(padding),
            )
            is RoomConversationUiState.Loaded -> {
                // fetchMessages() returns oldest-first; reverse to newest-first so reverseLayout
                // keeps the newest message pinned at the bottom of the viewport.
                val newestFirst = current.messages.asReversed()
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    itemsIndexed(newestFirst, key = { _, it -> it.id }) { index, message ->
                        RoomMessageBubble(
                            message = message,
                            // The chronologically earlier neighbor sits at index + 1 in this
                            // newest-first list (it renders above this bubble under reverseLayout).
                            previous = newestFirst.getOrNull(index + 1),
                            selfName = current.selfName,
                            onLinkClick = ::onLinkClick,
                            onLongPress = { actionsMessage = message },
                        )
                    }
                }
            }
        }
    }

    actionsMessage?.let { message ->
        val session = (state as? RoomConversationUiState.Loaded)?.session
        if (session != null) {
            RoomMessageActionsSheet(
                message = message,
                availability = RoomMessageActionAvailability(message, session),
                onDismiss = { actionsMessage = null },
                onReply = { pendingReplyMentionName = message.authorDisplayName },
                onSendDM = { actionsMessage = null; sendDMSenderName = message.authorDisplayName },
                onCopy = { clipboardManager.setText(AnnotatedString(message.text)) },
                onSendAgain = { viewModel.send(message.text) },
            )
        }
    }

    sendDMSenderName?.let { senderName ->
        SendDMSheet(
            senderName = senderName,
            onDismiss = { sendDMSenderName = null },
            onFetchMatches = viewModel::matchingContactsForSender,
            onSelect = { contact -> sendDMSenderName = null; onOpenConversation(contact.id) },
        )
    }
}

@Composable
private fun DisconnectedRoomBanner(onClick: () -> Unit) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_warning),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.chat_disconnected_tap),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadOnlyRoomBanner() {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.chat_read_only),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Same grouping gap as [ChatConversationScreen]'s `MESSAGE_GROUPING_GAP_SECONDS`. */
private const val ROOM_MESSAGE_GROUPING_GAP_SECONDS = 300L

/**
 * Whether [message] starts a new visual block relative to [previous] — mirrors
 * [ChatConversationScreen]'s channel-branch `isNewBlock`, keyed on [RoomMessageDto
 * .authorDisplayName]/[RoomMessageDto.isFromSelf] instead of `senderNodeName`/`isOutgoing` (a room
 * is always multi-party, so this never takes that function's DM branch).
 */
private fun isNewBlock(message: RoomMessageDto, previous: RoomMessageDto?): Boolean {
    if (previous == null) return true
    if (message.isFromSelf) return false
    if (previous.isFromSelf) return true
    val gapSeconds = Duration.between(previous.date, message.date).seconds
    if (gapSeconds > ROOM_MESSAGE_GROUPING_GAP_SECONDS) return true
    return message.authorDisplayName != previous.authorDisplayName
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomMessageBubble(
    message: RoomMessageDto,
    previous: RoomMessageDto?,
    selfName: String?,
    onLinkClick: (LinkToken) -> Unit,
    onLongPress: () -> Unit,
) {
    val theme = LocalAppTheme.current
    val isDark = LocalIsDarkTheme.current
    val isOutgoing = message.isFromSelf

    val showDirectionGap = previous != null && isOutgoing != previous.isFromSelf
    val newBlock = isNewBlock(message, previous)
    val topPadding = when {
        showDirectionGap -> 14.dp
        newBlock -> 8.dp
        else -> 2.dp
    }

    val bubbleColor = when {
        !isOutgoing -> incomingBubbleColor()
        message.status == MessageStatus.FAILED -> OutgoingBubbleFailedColor
        else -> MaterialTheme.colorScheme.primary
    }
    val textColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    // The footer sits outside the bubble, so the bubble-relative color (near-white for outgoing)
    // is unreadable on the chat background; see [USE_QUIET_META_LINE].
    val timeColor = when {
        USE_QUIET_META_LINE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        isOutgoing -> textColor.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = topPadding, end = 12.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 280.dp)) {
            if (!isOutgoing && newBlock) {
                Text(
                    text = message.authorDisplayName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = identityColor(message.authorDisplayName),
                )
            }
            val linkified = remember(message.text) { MessageLinkTokenizer.tokenize(message.text) }
            val mentionNames = remember(linkified.tokens) {
                linkified.tokens.filter { it.kind == LinkToken.Kind.MENTION }.map { it.value }.distinct()
            }
            val mentionColors = mentionNames.associateWith { identityColor(it) }
            Surface(
                color = bubbleColor,
                shape = bubbleShape(isOutgoing),
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress),
            ) {
                Text(
                    text = styledMessageText(
                        text = linkified.displayText,
                        tokens = linkified.tokens,
                        textColor = textColor,
                        hashtagColor = theme.hashtagColor.resolve(isDark),
                        isOutgoing = isOutgoing,
                        mentionColors = mentionColors,
                        selfName = selfName,
                        onLinkClick = onLinkClick,
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = textColor,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatMessageTime(message.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = timeColor,
                )
                if (isOutgoing) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(statusIcon(message.status)),
                        contentDescription = message.status.name,
                        modifier = Modifier.size(if (USE_QUIET_META_LINE) 12.dp else 10.dp),
                        tint = if (message.status == MessageStatus.FAILED) MaterialTheme.colorScheme.error else if (USE_QUIET_META_LINE) timeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
