// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.RoomMessageDto

/**
 * Long-press actions menu for a room-server message. Ported from `RoomMessageActionsSheet.swift`
 * — the room counterpart to [MessageActionsSheet], much smaller: no reactions (`ReactionService`
 * has no room variant), no block-sender/delete (a room's own server moderates membership, not this
 * client), and no repeat-details/view-path (room messages carry no [com.meshcoretwo.services
 * .persistence.MessageDto.heardRepeats]/`pathNodes` — those are DM/channel-only fields). Same
 * trimmed header as [MessageActionsSheet] (no separate author/timestamp row — the caller already
 * knows which room this is, and [RoomConversationScreen]'s bubble already shows the author name
 * for an incoming message) and same reused [MessageActionRow]/[DetailRow] building blocks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomMessageActionsSheet(
    message: RoomMessageDto,
    availability: RoomMessageActionAvailability,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onSendDM: () -> Unit,
    onCopy: () -> Unit,
    onSendAgain: () -> Unit,
) {
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

            if (availability.canReply) {
                MessageActionRow(icon = R.drawable.ic_reply, label = stringResource(R.string.common_reply), onClick = { onReply(); onDismiss() })
            }
            if (availability.canSendDM) {
                MessageActionRow(icon = R.drawable.ic_chat, label = stringResource(R.string.chat_send_dm), onClick = onSendDM)
            }
            MessageActionRow(icon = R.drawable.ic_content_copy, label = stringResource(R.string.common_copy), onClick = { onCopy(); onDismiss() })
            if (availability.canSendAgain) {
                MessageActionRow(icon = R.drawable.ic_refresh, label = stringResource(R.string.chat_send_again), onClick = { onSendAgain(); onDismiss() })
            }

            Text(
                stringResource(R.string.common_details),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                DetailRow(stringResource(R.string.chat_sent_at, detailDateFormat.format(message.date)))
                DetailRow(message.status.displayLabel())
                if (message.isFromSelf) {
                    message.roundTripTime?.let { rtt -> DetailRow(stringResource(R.string.chat_round_trip, rtt)) }
                } else {
                    DetailRow(stringResource(R.string.chat_received_at, detailDateFormat.format(message.createdAt)))
                }
            }
        }
    }
}

/** Ported from `RoomMessageDTO+Status.swift`'s `localizedStatusText`. */
@Composable
internal fun MessageStatus.displayLabel(): String = stringResource(
    when (this) {
        MessageStatus.PENDING, MessageStatus.SENDING -> R.string.chat_status_sending
        MessageStatus.SENT -> R.string.chat_status_sent
        MessageStatus.DELIVERED -> R.string.chat_status_delivered
        MessageStatus.FAILED -> R.string.chat_status_failed
        MessageStatus.RETRYING -> R.string.chat_status_retrying
    },
)
