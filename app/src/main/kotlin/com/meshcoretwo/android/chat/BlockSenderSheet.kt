// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.services.persistence.ContactDto
import java.util.UUID

/**
 * Confirmation sheet for blocking a channel sender name — the message-actions menu's "Block
 * Sender". Ported from `BlockSenderSheet.swift`, minus its `NavigationStack`/toolbar chrome (a
 * `ModalBottomSheet` already supplies drag-to-dismiss) and `CLLocation` distance display — see
 * [ContactMatchRow]'s doc for why.
 *
 * [onFetchMatches] loads contacts sharing [senderName]'s name, already-blocked ones excluded
 * (`excludeBlocked: true`, same as Swift's [SenderContactMatcher] call) — same one-shot callback
 * shape as [ConversationViewModel.fetchReactionDetails]. [onBlock] receives the set of
 * additionally-selected contact ids to block by identity, same as Swift's `onBlock(_
 * blockedContactIDs:)`; the sender name itself is always blocked regardless of any contact
 * selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockSenderSheet(
    senderName: String,
    onDismiss: () -> Unit,
    onFetchMatches: (String, (List<ContactDto>) -> Unit) -> Unit,
    onBlock: (Set<UUID>) -> Unit,
) {
    var matchingContacts by remember { mutableStateOf<List<ContactDto>>(emptyList()) }
    var selectedContactIDs by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(senderName) {
        onFetchMatches(senderName) { matchingContacts = it }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.chat_block_sender_title, senderName), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.chat_block_sender_body),
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (matchingContacts.isNotEmpty()) {
                Text(
                    stringResource(R.string.chat_block_matching_contacts),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(matchingContacts, key = { it.id }) { contact ->
                        ContactMatchRow(
                            contact = contact,
                            style = ContactMatchStyle.Toggle(isSelected = contact.id in selectedContactIDs),
                            onClick = {
                                selectedContactIDs = if (contact.id in selectedContactIDs) {
                                    selectedContactIDs - contact.id
                                } else {
                                    selectedContactIDs + contact.id
                                }
                            },
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                TextButton(onClick = { onBlock(selectedContactIDs) }) {
                    Text(stringResource(R.string.chat_block_anyway), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
