// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcoretwo.android.R
import com.meshcoretwo.services.persistence.MessageDto

/**
 * Long-press message-actions menu — the container half of `MessageActionsSheet.swift`, minus its
 * preview header's sender-name row (this port's dismiss-first `onDismiss` callers already know
 * which conversation they're in) and its details/expand sections. [onReply]/[onCopy]/
 * [onSendAgain]/[onDelete]/[onReact]/[onOpenEmojiPicker]/[onSendDM]/[onBlockSender] are real — see
 * [MessageActionAvailability]'s doc for the rest of PLAN.md's "Message Actions" epic. Row order
 * (quick-react row, reply, send DM, copy, send-again, then a destructive-section divider before
 * block sender/delete) matches `ActionsEmojiSection.swift`/`ActionsButtonsSection.swift`/
 * `ActionsDestructiveSection.swift`'s relative ordering.
 *
 * React has no [MessageActionAvailability] gate (matches Swift: `react` is always available,
 * unlike the other rows) — the quick-react row always renders. Tapping a [recentEmojis] entry
 * reacts immediately; the trailing "+" calls [onOpenEmojiPicker] instead of opening a nested sheet
 * (Compose doesn't stack `ModalBottomSheet`s cleanly) — the caller (`ChatConversationScreen`)
 * dismisses this sheet and opens [EmojiPickerSheet] in its place. [onSendDM]/[onBlockSender] follow
 * the same close-then-reopen pattern for [SendDMSheet]/[BlockSenderSheet].
 *
 * [MessageDetailsSection] (send/receive metadata, and the expandable "Repeat Details"/"View Path"
 * row) sits between Send Again and the destructive divider, same relative position as
 * `ActionsDetailsSection.swift`. The whole sheet scrolls (`Modifier.verticalScroll`, unlike the
 * rest of this port's `ModalBottomSheet`s, which fit without it) since that section's expanded
 * content can push past one screen's height, same reason Swift wraps its own body in a
 * `ScrollView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    message: MessageDto,
    recentEmojis: List<String>,
    selfName: String?,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onOpenEmojiPicker: () -> Unit,
    onReply: () -> Unit,
    onSendDM: () -> Unit,
    onCopy: () -> Unit,
    onSendAgain: () -> Unit,
    onFetchDetails: (MessageDto, (MessageDetailData) -> Unit) -> Unit,
    onCopyPath: (String) -> Unit,
    onViewPathMap: () -> Unit,
    onBlockSender: () -> Unit,
    onDelete: () -> Unit,
) {
    val availability = remember(message) { MessageActionAvailability(message) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Text(
                message.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (emoji in recentEmojis) {
                    QuickReactButton(onClick = { onReact(emoji); onDismiss() }) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
                QuickReactButton(onClick = onOpenEmojiPicker) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.chat_more_reactions))
                }
            }
            HorizontalDivider()

            if (availability.canReply) {
                MessageActionRow(icon = R.drawable.ic_reply, label = stringResource(R.string.common_reply), onClick = { onReply(); onDismiss() })
            }
            if (availability.canSendDM) {
                MessageActionRow(icon = R.drawable.ic_chat, label = stringResource(R.string.chat_send_dm), onClick = onSendDM)
            }
            if (availability.canCopy) {
                MessageActionRow(icon = R.drawable.ic_content_copy, label = stringResource(R.string.common_copy), onClick = { onCopy(); onDismiss() })
            }
            if (availability.canSendAgain) {
                MessageActionRow(icon = R.drawable.ic_refresh, label = stringResource(R.string.chat_send_again), onClick = { onSendAgain(); onDismiss() })
            }

            MessageDetailsSection(
                message = message,
                availability = availability,
                selfName = selfName,
                onFetchDetails = onFetchDetails,
                onCopyPath = onCopyPath,
                onViewPathMap = { onViewPathMap(); onDismiss() },
            )

            if (availability.canBlockSender || availability.canDelete) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (availability.canBlockSender) {
                    MessageActionRow(icon = R.drawable.ic_block, label = stringResource(R.string.chat_block_sender), isDestructive = true, onClick = onBlockSender)
                }
                if (availability.canDelete) {
                    MessageActionRow(icon = R.drawable.ic_delete, label = stringResource(R.string.common_delete), isDestructive = true, onClick = { onDelete(); onDismiss() })
                }
            }
        }
    }
}

/** One circular button in the quick-react row — the recent-emoji cells and the trailing "+". */
@Composable
private fun QuickReactButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** `internal`, not `private`: [RoomMessageActionsSheet] reuses this same row. */
@Composable
internal fun MessageActionRow(icon: Int, label: String, isDestructive: Boolean = false, onClick: () -> Unit) {
    val color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.padding(end = 16.dp))
        Text(label, color = color)
    }
}
