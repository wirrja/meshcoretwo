// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.ui.formatRelativeTimestamp
import com.meshcoretwo.services.persistence.ReactionDto
import java.util.UUID

/**
 * "Who reacted" sheet, opened from [ReactionBadgesRow]'s long-press/overflow tap. Ported from
 * `ReactionDetailsSheet.swift`: an emoji tab strip (grouped, sorted by count descending, ties
 * broken by earliest [ReactionDto.receivedAt]) over a list of sender name + relative timestamp for
 * the selected emoji. [onFetch] is [ConversationViewModel.fetchReactionDetails] — a one-shot
 * callback-style load (same shape as that view model's `findChannelIndexForHashtag`) rather than an
 * observed `StateFlow`, matching Swift's own one-shot `.task { dataStore.fetchReactions(...) }`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionDetailsSheet(messageId: UUID, onDismiss: () -> Unit, onFetch: (UUID, (List<ReactionDto>) -> Unit) -> Unit) {
    var reactions by remember(messageId) { mutableStateOf<List<ReactionDto>>(emptyList()) }
    LaunchedEffect(messageId) { onFetch(messageId) { reactions = it } }

    val grouped = remember(reactions) {
        reactions.groupBy { it.emoji }
            .toList()
            .sortedWith(compareByDescending<Pair<String, List<ReactionDto>>> { it.second.size }.thenBy { it.second.minOf { r -> r.receivedAt } })
    }
    var selectedEmoji by remember(grouped) { mutableStateOf(grouped.firstOrNull()?.first) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((emoji, items) in grouped) {
                    val selected = emoji == selectedEmoji
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        modifier = Modifier.clickable { selectedEmoji = emoji },
                    ) {
                        Text("$emoji ${items.size}", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            val shownReactions = grouped.firstOrNull { it.first == selectedEmoji }?.second.orEmpty()
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(shownReactions, key = { it.id }) { reaction ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(reaction.senderName)
                        Text(
                            formatRelativeTimestamp(reaction.receivedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
