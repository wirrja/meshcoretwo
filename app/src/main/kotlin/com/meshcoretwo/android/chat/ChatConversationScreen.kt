// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.chat.linkify.LinkToken
import com.meshcoretwo.android.chat.linkify.MessageLinkTokenizer
import com.meshcoretwo.android.chat.linkify.styledMessageText
import com.meshcoretwo.android.ui.components.FullScreenMessage
import com.meshcoretwo.android.ui.components.InitialsAvatar
import com.meshcoretwo.android.ui.components.LoadingScreen
import com.meshcoretwo.android.ui.components.RouteChip
import com.meshcoretwo.android.ui.theme.LocalAppTheme
import com.meshcoretwo.android.ui.theme.LocalIsDarkTheme
import com.meshcoretwo.android.ui.theme.identityColor
import com.meshcoretwo.android.ui.theme.incomingBubbleColor
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.rxlog.RegionScopeSemantics
import com.meshcoretwo.services.utilities.MentionUtilities
import java.time.Duration
import java.time.Instant
import com.meshcoretwo.android.ui.i18n.DatePatterns
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * Single-conversation screen — text messages in, text messages out. Ported from
 * `ChatConversationView.swift`/`ChatConversationMessagesContent.swift`/`Components/ChatInputBar.swift`,
 * trimmed to a first MVP slice: plain-text bubbles only (no images/link previews/map previews), no
 * translation, no backward pagination beyond [ConversationViewModel]'s fixed load limit. Long-press
 * opens [MessageActionsSheet] (copy/send-again/delete/reply/react/send DM/block sender/repeat
 * details/view path — see PLAN.md's "Message Actions" epic for what that menu still defers) — see
 * that class's doc and PLAN.md's Phase 5 chat slice for the full deferral list.
 *
 * The byte-limit counter and 1-second post-send cooldown are ported from `ChatInputBar.swift`
 * (`shouldShowCharacterCount`/`isCoolingDown`); connection gating mirrors its
 * `appState.connectionState != .ready` check.
 *
 * Since "Message text linkify", each bubble also runs [MessageLinkTokenizer] over its text and
 * renders the result via [styledMessageText] — this port's counterpart to iOS's `ChatLinkRouter`,
 * split four ways instead of one shared router since Android has no single `OpenURLAction` to
 * intercept: a plain `http(s)` URL opens the system browser locally (no callback needed), a
 * `meshcore://channel/add` link goes through [onOpenChannelLink] (the same [ConnectionManager]-
 * backed join screen `MainActivity`'s deep-link intent-filter also lands on, see
 * `AddChannelViewModel`'s doc), a `#hashtag` is looked up via [ConversationViewModel
 * .findChannelIndexForHashtag] — an existing match calls [onOpenExistingChannel], no match falls
 * back to [onJoinHashtag] — and a `<pubkeyHex:type:name>` contact-share token (normalized to its
 * display name by [MessageLinkTokenizer] before this screen ever sees it, see that object's doc)
 * goes through [onOpenContactShareLink] with the same `meshcore://contact/add` URI shape
 * `AddContactScreen` already parses for a scanned QR or a pasted link. A `@[name]` mention token is
 * highlighted the same way (identity color, self-mention background) but has no [onLinkClick]
 * branch — tap-to-navigate is a separate, unported feature, see [LinkToken]'s doc.
 *
 * `ChatInputBar`'s @mention autocomplete (type `@` to search [ConversationUiState.Loaded
 * .mentionCandidates]) is likewise ported — see that composable's doc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatConversationScreen(
    connectionManager: ConnectionManager,
    target: ConversationTarget,
    prefs: SharedPreferences,
    onBack: () -> Unit,
    onOpenChannelInfo: (() -> Unit)? = null,
    onOpenChannelLink: (String) -> Unit = {},
    onOpenExistingChannel: (UByte) -> Unit = {},
    onJoinHashtag: (String) -> Unit = {},
    onOpenContactShareLink: (String) -> Unit = {},
    onOpenConversation: (UUID) -> Unit = {},
    onOpenPathMap: (UUID) -> Unit = {},
) {
    val viewModel: ConversationViewModel = viewModel(factory = ConversationViewModel.Factory(connectionManager, target, prefs))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val connectionState by connectionManager.connectionStateEvents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var actionsMessage by remember { mutableStateOf<MessageDto?>(null) }
    var emojiPickerMessage by remember { mutableStateOf<MessageDto?>(null) }
    var reactionDetailsMessage by remember { mutableStateOf<MessageDto?>(null) }
    var blockSenderName by remember { mutableStateOf<String?>(null) }
    var sendDMSenderName by remember { mutableStateOf<String?>(null) }
    var pendingReplyMentionName by remember { mutableStateOf<String?>(null) }
    val recentEmojis = (state as? ConversationUiState.Loaded)?.recentEmojis ?: RecentEmojiStore.DEFAULTS
    val selfName = (state as? ConversationUiState.Loaded)?.selfName
    val showIncomingPath = remember { ChatDisplayPreferences.isIncomingPathEnabled(prefs) }
    val showIncomingHopCount = remember { ChatDisplayPreferences.isIncomingHopCountEnabled(prefs) }
    val showIncomingRegion = remember { ChatDisplayPreferences.isIncomingRegionEnabled(prefs) }

    // Ported from `ChatConversationView.swift`'s `scrollToBottomRequest` counter: bumped on send
    // and on the composer gaining focus, observed below to snap the (reverseLayout, so index 0 is
    // the newest message) list back to the bottom.
    val listState = rememberLazyListState()
    var scrollToBottomRequest by remember { mutableStateOf(0) }
    LaunchedEffect(scrollToBottomRequest) {
        if (scrollToBottomRequest > 0) listState.animateScrollToItem(0)
    }
    // The send-time bump above fires before the new message reaches the list, so it only scrolls to
    // the previous newest item. Follow the list itself: when a newer message appears, scroll to it if
    // we just sent one or the user is already at the bottom (so reading history isn't yanked away).
    val newestMessageId = (state as? ConversationUiState.Loaded)?.messages?.lastOrNull()?.id
    var lastSeenMessageId by remember { mutableStateOf<Any?>(null) }
    var sendPending by remember { mutableStateOf(false) }
    LaunchedEffect(newestMessageId) {
        val previous = lastSeenMessageId
        lastSeenMessageId = newestMessageId
        if (previous != null && newestMessageId != previous && (sendPending || listState.firstVisibleItemIndex <= 1)) {
            sendPending = false
            listState.animateScrollToItem(0)
        }
    }

    // One-shot: on this screen's first loaded snapshot, jump straight to the first unread message
    // instead of the bottom — ported from `ChatInitialScrollPolicy`'s divider target, trimmed to a
    // plain scroll (no baked "New Messages" row machinery, just the divider line below). Keyed on
    // "have messages arrived yet" so it fires exactly once, even though reload() re-runs on every
    // incoming event afterward.
    val initialMessages = (state as? ConversationUiState.Loaded)?.messages
    var newMessagesDividerId by remember { mutableStateOf<UUID?>(null) }
    LaunchedEffect(initialMessages != null) {
        val messages = initialMessages ?: return@LaunchedEffect
        val unread = viewModel.initialUnreadCount
        if (unread <= 0 || messages.isEmpty()) return@LaunchedEffect
        val dividerIndex = (messages.size - unread).coerceIn(0, messages.size - 1)
        newMessagesDividerId = messages[dividerIndex].id
        listState.scrollToItem(messages.size - 1 - dividerIndex)
    }

    // Loads another page once the user scrolls near the top (the oldest end, under reverseLayout).
    LaunchedEffect(listState, (state as? ConversationUiState.Loaded)?.hasMoreOlderMessages) {
        val loaded = state as? ConversationUiState.Loaded ?: return@LaunchedEffect
        if (!loaded.hasMoreOlderMessages) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex >= listState.layoutInfo.totalItemsCount - 5) {
                    viewModel.loadOlderMessages()
                }
            }
    }

    fun onLinkClick(token: LinkToken) {
        when (token.kind) {
            LinkToken.Kind.URL -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(token.value)))
            } catch (error: ActivityNotFoundException) {
                // No browser installed; there is nothing sensible to fall back to.
            }
            LinkToken.Kind.MESHCORE_CHANNEL_LINK -> onOpenChannelLink(token.value)
            LinkToken.Kind.HASHTAG -> viewModel.findChannelIndexForHashtag(token.value) { index ->
                if (index != null) onOpenExistingChannel(index) else onJoinHashtag(token.value)
            }
            LinkToken.Kind.CONTACT_SHARE -> onOpenContactShareLink(token.value)
            // Highlighted only — [styledMessageText] never attaches a tap target to a mention span,
            // so this is unreachable; kept for `when` exhaustiveness. See LinkToken's doc.
            LinkToken.Kind.MENTION -> {}
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    val loaded = state as? ConversationUiState.Loaded
                    Column {
                        Text(loaded?.title ?: "")
                        loaded?.subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) }
                },
                actions = {
                    if (target is ConversationTarget.Channel && onOpenChannelInfo != null) {
                        IconButton(onClick = onOpenChannelInfo) { Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.chat_channel_info)) }
                    }
                },
            )
        },
        bottomBar = {
            val loaded = state as? ConversationUiState.Loaded
            if (loaded != null) {
                ChatInputBar(
                    maxBytes = loaded.maxMessageBytes,
                    canSendConnection = connectionState == DeviceConnectionState.READY,
                    mentionCandidates = loaded.mentionCandidates,
                    onSend = { text -> viewModel.send(text); sendPending = true; scrollToBottomRequest++ },
                    onFocus = { scrollToBottomRequest++ },
                    pendingReplyMentionName = pendingReplyMentionName,
                    onPendingReplyMentionHandled = { pendingReplyMentionName = null },
                )
            }
        },
    ) { padding ->
        when (val current = state) {
            is ConversationUiState.Loading -> LoadingScreen(modifier = Modifier.padding(padding))
            is ConversationUiState.SlotReassigned -> {
                LaunchedEffect(Unit) { onBack() }
                LoadingScreen(modifier = Modifier.padding(padding))
            }
            is ConversationUiState.NotFound -> FullScreenMessage(
                stringResource(R.string.chat_conv_unavailable),
                modifier = Modifier.padding(padding),
            )
            is ConversationUiState.Loaded -> {
                val isChannel = target is ConversationTarget.Channel
                // getMessages() returns oldest-first; reverse to newest-first so reverseLayout
                // keeps the newest message pinned at the bottom of the viewport.
                val newestFirst = current.messages.asReversed()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    itemsIndexed(newestFirst, key = { _, it -> it.id }) { index, message ->
                        // The chronologically earlier neighbor sits at index + 1 in this
                        // newest-first list (it renders above this bubble under reverseLayout).
                        Column {
                            // Sits above this bubble in the cell's own top-down layout, which reads
                            // as "above" on screen even under the list's reverseLayout — same visual
                            // position Swift's NewMessagesDividerView renders at.
                            if (message.id == newMessagesDividerId) {
                                NewMessagesDivider(modifier = Modifier.padding(vertical = 8.dp))
                            }
                            MessageBubble(
                                message = message,
                                previous = newestFirst.getOrNull(index + 1),
                                isChannel = isChannel,
                                selfName = current.selfName,
                                showIncomingPath = showIncomingPath,
                                showIncomingHopCount = showIncomingHopCount,
                                showIncomingRegion = showIncomingRegion,
                                sendRegion = current.sendRegion,
                                onLinkClick = ::onLinkClick,
                                onLongPress = { actionsMessage = message },
                                onReact = { emoji -> viewModel.sendReaction(emoji, message) },
                                onShowReactionDetails = { reactionDetailsMessage = message },
                            )
                        }
                    }
                }
            }
        }
    }

    actionsMessage?.let { message ->
        MessageActionsSheet(
            message = message,
            recentEmojis = recentEmojis,
            selfName = selfName,
            onDismiss = { actionsMessage = null },
            onReact = { emoji -> viewModel.sendReaction(emoji, message) },
            onOpenEmojiPicker = { actionsMessage = null; emojiPickerMessage = message },
            onReply = { pendingReplyMentionName = viewModel.mentionNameForReply(message) },
            onSendDM = { actionsMessage = null; sendDMSenderName = message.senderNodeName },
            onCopy = { clipboardManager.setText(AnnotatedString(message.text)) },
            onSendAgain = { viewModel.sendAgain(message) },
            onFetchDetails = viewModel::fetchMessageDetails,
            onCopyPath = { pathHex -> clipboardManager.setText(AnnotatedString(pathHex)) },
            onViewPathMap = { onOpenPathMap(message.id) },
            onBlockSender = { actionsMessage = null; blockSenderName = message.senderNodeName },
            onDelete = { viewModel.delete(message) },
        )
    }

    emojiPickerMessage?.let { message ->
        EmojiPickerSheet(
            recentEmojis = recentEmojis,
            onDismiss = { emojiPickerMessage = null },
            onSelect = { emoji ->
                emojiPickerMessage = null
                viewModel.sendReaction(emoji, message)
            },
        )
    }

    reactionDetailsMessage?.let { message ->
        ReactionDetailsSheet(
            messageId = message.id,
            onDismiss = { reactionDetailsMessage = null },
            onFetch = viewModel::fetchReactionDetails,
        )
    }

    blockSenderName?.let { senderName ->
        BlockSenderSheet(
            senderName = senderName,
            onDismiss = { blockSenderName = null },
            onFetchMatches = viewModel::matchingContactsForSender,
            onBlock = { contactIDs -> viewModel.blockSender(senderName, contactIDs); blockSenderName = null },
        )
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

/** Time gap (seconds) beyond which a same-sender channel run breaks into a new named block. Ported
 * from `ChatMessageBakeState.messageGroupingGapSeconds`. */
private const val MESSAGE_GROUPING_GAP_SECONDS = 300L

/**
 * Whether [message] starts a new visual block relative to [previous] (the chronologically earlier
 * neighbor) — wider top spacing and, in a channel, a repainted sender name. Ported from
 * `ChatMessageBakeState.computeDisplayFlags`'s `showSenderName`, minus its `showTimestamp`/
 * `showDayDivider` outputs (day dividers are out of this slice's scope) and keyed on [MessageDto.sortDate]
 * rather than iOS's separate `senderDate` — this port has no field distinct from the sort key.
 * DM rows always start a new block (iOS: `message.contactID != nil` branch is unconditionally
 * `true`); outgoing channel rows never do on their own (no name to break on), only a direction
 * switch does.
 */
private fun isNewBlock(message: MessageDto, previous: MessageDto?, isChannel: Boolean): Boolean {
    if (previous == null) return true
    if (!isChannel) return true
    if (message.isOutgoing) return false
    if (previous.isOutgoing) return true
    val gapSeconds = Duration.between(previous.sortDate, message.sortDate).seconds
    if (gapSeconds > MESSAGE_GROUPING_GAP_SECONDS) return true
    val currentName = message.senderNodeName
    val previousName = previous.senderNodeName
    if (currentName == null || previousName == null) return true
    return currentName != previousName
}

/** Ported from `AppColors.Message.outgoingBubbleFailed(highContrast: false)` — the `highContrast:
 * true` branch (opaque system red) has no port yet, since this app doesn't read the
 * increased-contrast accessibility setting. Also reused by [RoomConversationScreen]. */
internal val OutgoingBubbleFailedColor = Color(0xFFFF3B30).copy(alpha = 0.8f)

/** Horizontal rule with a centered "New Messages" label — ported from `NewMessagesDividerView.swift`. */
@Composable
private fun NewMessagesDivider(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.chat_new_messages_divider),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageDto,
    previous: MessageDto?,
    isChannel: Boolean,
    selfName: String?,
    showIncomingPath: Boolean,
    showIncomingHopCount: Boolean,
    showIncomingRegion: Boolean,
    sendRegion: String?,
    onLinkClick: (LinkToken) -> Unit,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
    onShowReactionDetails: () -> Unit,
) {
    val theme = LocalAppTheme.current
    val isDark = LocalIsDarkTheme.current
    val isOutgoing = message.isOutgoing

    // Ported from `UnifiedMessageBubble.paddingTop`: a direction switch is the strongest visual
    // break, then a new named block; same-cluster follow-ups stay tightly stacked.
    val showDirectionGap = previous != null && isOutgoing != previous.isOutgoing
    val newBlock = isNewBlock(message, previous, isChannel)
    val topPadding = when {
        showDirectionGap -> 14.dp
        newBlock -> 8.dp
        else -> 2.dp
    }

    val bubbleColor = when {
        !isOutgoing -> incomingBubbleColor()
        message.hasFailed -> OutgoingBubbleFailedColor
        else -> MaterialTheme.colorScheme.primary
    }
    val textColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = if (isOutgoing) textColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = topPadding, end = 12.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 280.dp)) {
            val senderNodeName = message.senderNodeName
            if (isChannel && !isOutgoing && newBlock && senderNodeName != null) {
                Text(
                    text = senderNodeName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = identityColor(senderNodeName),
                )
            }
            val linkified = remember(message.text) { MessageLinkTokenizer.tokenize(message.text) }
            val mentionNames = remember(linkified.tokens) {
                linkified.tokens.filter { it.kind == LinkToken.Kind.MENTION }.map { it.value }.distinct()
            }
            val mentionColors = mentionNames.associateWith { identityColor(it) }
            val gradientBubble = isOutgoing && !message.hasFailed
            val primary = MaterialTheme.colorScheme.primary
            val bubbleBrush = remember(primary) {
                Brush.linearGradient(listOf(lerp(primary, Color.White, 0.10f), lerp(primary, Color.Black, 0.12f)))
            }
            Surface(
                color = if (gradientBubble) Color.Transparent else bubbleColor,
                shape = bubbleShape(isOutgoing),
                shadowElevation = if (isOutgoing) 0.dp else 1.dp,
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
                    modifier = (if (gradientBubble) Modifier.background(bubbleBrush) else Modifier).padding(horizontal = 14.dp, vertical = 9.dp),
                    color = textColor,
                )
            }
            val reactionSummary = message.reactionSummary
            if (!reactionSummary.isNullOrEmpty()) {
                ReactionBadgesRow(
                    summary = reactionSummary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                    onTapReaction = onReact,
                    onOpenDetails = onShowReactionDetails,
                )
            }
            // Channel messages never get an ACK, so a repeater re-broadcasting our packet (counted in
            // heardRepeats via RxLog) is the only delivery evidence there is — show it as a double tick.
            val heardByRepeater = isChannel && isOutgoing && message.status == MessageStatus.SENT && message.heardRepeats > 0
            if (USE_QUIET_META_LINE) {
                QuietMessageMeta(
                    message = message,
                    isOutgoing = isOutgoing,
                    heardByRepeater = heardByRepeater,
                    showIncomingPath = showIncomingPath,
                    showIncomingHopCount = showIncomingHopCount,
                    showIncomingRegion = showIncomingRegion,
                    outgoingRegion = sendRegion.takeIf { showIncomingRegion },
                )
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatMessageTime(message.sortDate),
                    style = MaterialTheme.typography.labelSmall,
                    color = timeColor,
                )
                if (isOutgoing) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(if (heardByRepeater) R.drawable.ic_done_all else statusIcon(message.status)),
                        contentDescription = if (heardByRepeater) stringResource(R.string.chat_heard_by_repeater) else message.status.displayLabel(),
                        modifier = Modifier.size(10.dp),
                        tint = if (message.status == MessageStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (message.heardRepeats > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        RouteChip(label = "\u21BB ${message.heardRepeats}")
                    }
                } else {
                    if (showIncomingPath) {
                        Spacer(modifier = Modifier.width(4.dp))
                        RouteChip(label = MessagePathFormatter.format(message))
                    }
                    if (showIncomingHopCount && message.isFloodRouted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        RouteChip(label = "${message.hopCount} hop${if (message.hopCount == 1) "" else "s"}")
                    }
                    if (showIncomingRegion && message.isFloodRouted) {
                        val regionMatch = RegionScopeSemantics.coalesce(message.regionScope, message.regionScopeMatches)
                        val regionLabel = RegionScopeSemantics.chipLabel(regionMatch)
                        if (regionLabel != null) {
                            Spacer(modifier = Modifier.width(4.dp))
                            val candidates = RegionScopeSemantics.matchNames(regionMatch)
                            if (candidates.size > 1) {
                                var showExplanation by remember { mutableStateOf(false) }
                                RouteChip(label = regionLabel, onClick = { showExplanation = true })
                                if (showExplanation) {
                                    AmbiguousRegionDialog(candidates, onDismiss = { showExplanation = false })
                                }
                            } else {
                                RouteChip(label = regionLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Switch between the quiet one-line message footer (`true`) and the original row of tonal chips
 * (`false`, kept intact right below the call site in [MessageBubble]). Flip this to roll back.
 * Also read by [RoomConversationScreen]'s footer, which only takes the readable-color part.
 */
internal const val USE_QUIET_META_LINE = true

/**
 * Message footer as one muted line — `23:24 · 6 hops · mow` — instead of a chip per fact. The
 * routing path is hidden behind a tap (when the "Incoming Path" setting is on), along with the
 * candidate regions for an ambiguous region match. Colors come from the surface, never the bubble:
 * the footer sits outside the bubble, so the bubble-relative time color was unreadable there.
 */
@Composable
private fun QuietMessageMeta(
    message: MessageDto,
    isOutgoing: Boolean,
    heardByRepeater: Boolean,
    showIncomingPath: Boolean,
    showIncomingHopCount: Boolean,
    showIncomingRegion: Boolean,
    outgoingRegion: String?,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val regionMatch = if (!isOutgoing && showIncomingRegion && message.isFloodRouted) {
        RegionScopeSemantics.coalesce(message.regionScope, message.regionScopeMatches)
    } else {
        null
    }
    val regionLabel = regionMatch?.let(RegionScopeSemantics::chipLabel)
    val regionCandidates = regionMatch?.let(RegionScopeSemantics::matchNames).orEmpty()
    val parts = buildList {
        add(formatMessageTime(message.sortDate))
        if (isOutgoing) {
            // Region isn't stored per outgoing message: this is the channel's current send region.
            outgoingRegion?.let(::add)
            if (message.heardRepeats > 0) add("\u21BB ${message.heardRepeats}")
        } else {
            if (showIncomingHopCount && message.isFloodRouted) {
                add("${message.hopCount} hop${if (message.hopCount == 1) "" else "s"}")
            }
            regionLabel?.let(::add)
        }
    }
    val expandable = !isOutgoing && (showIncomingPath || regionCandidates.size > 1)
    var expanded by remember(message.id) { mutableStateOf(false) }

    Column(
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = parts.joinToString(" \u00B7 "), style = MaterialTheme.typography.labelSmall, color = color)
            if (expandable) {
                // Affordance: a bare text line doesn't look tappable.
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = stringResource(if (expanded) R.string.chat_route_hide else R.string.chat_route_show),
                    modifier = Modifier.size(14.dp),
                    tint = color,
                )
            }
            if (isOutgoing) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    painter = painterResource(if (heardByRepeater) R.drawable.ic_done_all else statusIcon(message.status)),
                    contentDescription = if (heardByRepeater) stringResource(R.string.chat_heard_by_repeater) else message.status.displayLabel(),
                    modifier = Modifier.size(12.dp),
                    tint = if (message.status == MessageStatus.FAILED) MaterialTheme.colorScheme.error else color,
                )
            }
        }
        if (expanded) {
            if (showIncomingPath) {
                Text(text = stringResource(R.string.chat_path_line, MessagePathFormatter.format(message)), style = MaterialTheme.typography.labelSmall, color = color)
            }
            if (regionCandidates.size > 1) {
                Text(text = stringResource(R.string.chat_regions_line, regionCandidates.joinToString(", ")), style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

/** Explains a multi-match region chip: which regions' keys fit the packet's transport code. Ported from `FallbackMatchIndicatorView`'s region popover. */
@Composable
private fun AmbiguousRegionDialog(candidates: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_multi_regions_title)) },
        text = {
            Text(
                stringResource(R.string.chat_multi_regions_body, candidates.joinToString("\n")),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
    )
}

/**
 * Bubble corner shape — mockup's "bubble, tail corner" shape token: 19dp on three corners, a tight
 * 6dp corner on the side that points at its sender (bottom-end for outgoing, bottom-start for
 * incoming). Also reused by [RoomConversationScreen]'s `RoomMessageBubble`.
 */
internal fun bubbleShape(isOutgoing: Boolean): RoundedCornerShape = if (isOutgoing) {
    RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp, bottomStart = 19.dp, bottomEnd = 6.dp)
} else {
    RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp, bottomStart = 6.dp, bottomEnd = 19.dp)
}

/** Also reused by [RoomConversationScreen]'s `RoomMessageBubble` — same status vocabulary. */
@DrawableRes
internal fun statusIcon(status: MessageStatus): Int = when (status) {
    MessageStatus.PENDING, MessageStatus.SENDING -> R.drawable.ic_schedule
    MessageStatus.SENT -> R.drawable.ic_check
    MessageStatus.DELIVERED -> R.drawable.ic_done_all
    MessageStatus.FAILED -> R.drawable.ic_warning
    MessageStatus.RETRYING -> R.drawable.ic_refresh
}

/** Also reused by [RoomConversationScreen]'s `RoomMessageBubble`. */
internal fun formatMessageTime(date: Instant): String =
    DatePatterns.timeShort().format(date)

/** Corner radius of the pill-shaped compose field. Ported from `ChatInputMetrics.fieldCornerRadius`. */
private val ChatInputFieldCornerRadius = 24.dp

/**
 * Ported from `Components/ChatInputBar.swift`, minus its leading-accessory slot (no attachments
 * yet). Also reused by [RoomConversationScreen], passing [RoomConversationUiState.Loaded
 * .mentionCandidates] for [mentionCandidates] and its own [RoomMessageActionsSheet]'s "Reply" hand-off
 * for [pendingReplyMentionName]/[onPendingReplyMentionHandled] — same one-shot mechanism as below.
 *
 * [pendingReplyMentionName]/[onPendingReplyMentionHandled] are the message-actions menu's "Reply"
 * hand-off: [text] is local `remember` state private to this composable (unlike Swift's
 * `ChatViewModel`-owned `composingText`, there's no shared draft this port's ViewModel could set
 * directly), so a reply request comes in as a one-shot mention name instead — applied via
 * [com.meshcoretwo.services.utilities.MentionUtilities.appendMention] the moment it's set, then
 * immediately cleared by the caller so the next reply (even to the same sender) triggers a fresh
 * transition. Only [MentionUtilities.appendMention]'s append-to-current-draft behavior is reachable
 * this way — Swift's alternate `buildReplyText`-and-replace "Reply with Quote" mode needs a
 * `ChatSettingsView`-equivalent toggle this port doesn't have yet (see `MentionUtilities`' doc).
 * Also not ported: refocusing the compose field after the actions sheet's dismiss animation
 * (`inputFocusRequest`) — the inserted text is visible either way, refocus is a minor nicety.
 */
@Composable
internal fun ChatInputBar(
    maxBytes: Int,
    canSendConnection: Boolean,
    mentionCandidates: List<ContactDto>,
    onSend: (String) -> Unit,
    onFocus: () -> Unit = {},
    pendingReplyMentionName: String? = null,
    onPendingReplyMentionHandled: () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    var isCoolingDown by remember { mutableStateOf(false) }

    LaunchedEffect(isCoolingDown) {
        if (isCoolingDown) {
            delay(1_000)
            isCoolingDown = false
        }
    }

    LaunchedEffect(pendingReplyMentionName) {
        val name = pendingReplyMentionName ?: return@LaunchedEffect
        text = MentionUtilities.appendMention(name, text)
        onPendingReplyMentionHandled()
    }

    val byteCount = text.toByteArray(Charsets.UTF_8).size
    val isOverLimit = byteCount > maxBytes
    val canSend = canSendConnection && !isCoolingDown && text.isNotBlank() && !isOverLimit

    // @mention autocomplete. Ported from `ChatConversationView`'s `activeMentionQuery`/
    // `mentionSuggestions`/`insertMention` — this port has no long-lived `ChatViewModel` for the
    // composer to read those off of, so all three live here alongside the `text` state they react to.
    val activeMentionQuery = remember(text) { MentionUtilities.detectActiveMention(text) }
    val mentionSuggestions = remember(activeMentionQuery, mentionCandidates) {
        activeMentionQuery?.let { MentionUtilities.filterContacts(mentionCandidates, it) } ?: emptyList()
    }
    fun insertMention(contact: ContactDto) {
        val query = activeMentionQuery ?: return
        val searchPattern = "@$query"
        val index = text.lastIndexOf(searchPattern)
        if (index < 0) return
        text = text.substring(0, index) + MentionUtilities.createMention(contact.name) + " " + text.substring(index + searchPattern.length)
    }

    Surface(color = Color.Transparent) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (mentionSuggestions.isNotEmpty()) {
                MentionSuggestionsList(mentionSuggestions, onSelect = ::insertMention)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) onFocus() },
                    placeholder = { Text(stringResource(if (canSendConnection) R.string.common_message else R.string.chat_reconnecting)) },
                    enabled = canSendConnection,
                    maxLines = 4,
                    shape = RoundedCornerShape(ChatInputFieldCornerRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                    ),
                )
                FilledIconButton(
                    enabled = canSend,
                    modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    onClick = {
                        val trimmed = text.trim()
                        if (trimmed.isEmpty()) return@FilledIconButton
                        text = ""
                        isCoolingDown = true
                        onSend(trimmed)
                    },
                ) {
                    Icon(painter = painterResource(R.drawable.ic_send), contentDescription = stringResource(R.string.chat_send))
                }
            }
            if (byteCount >= maxBytes - 20) {
                Text(
                    text = "$byteCount/$maxBytes",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOverLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Maximum rows shown at once, matching Swift's `MentionSuggestionView.maxSuggestions`. */
private const val MAX_MENTION_SUGGESTIONS = 20

/**
 * Floating popup of @mention candidates above the compose field. Ported from
 * `MentionSuggestionView.swift`/`MentionSuggestionRow.swift`, using [InitialsAvatar] (the same
 * per-name-colored circle every other contact row in this port already uses) in place of iOS's
 * `ContactAvatar`.
 */
@Composable
private fun MentionSuggestionsList(contacts: List<ContactDto>, onSelect: (ContactDto) -> Unit) {
    val suggestions = contacts.take(MAX_MENTION_SUGGESTIONS)
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(bottom = 8.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        LazyColumn {
            itemsIndexed(suggestions, key = { _, contact -> contact.id }) { index, contact ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(contact) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InitialsAvatar(name = contact.displayName, size = 32.dp)
                    Text(contact.displayName, maxLines = 1)
                }
                if (index != suggestions.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp))
                }
            }
        }
    }
}
