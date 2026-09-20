// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.services.persistence.ContactDto

/**
 * Contact picker for starting a DM with a channel sender — the message-actions menu's "Send DM".
 * Ported from `SendDMSheet.swift`, same [SenderContactMatcher]-backed match list as
 * [BlockSenderSheet] (`excludeBlocked: true`). No `unverifiedNickname`-aware title: this port has
 * no nickname-claim feature yet (`SendDMSheet.swift`'s `titleName` falls back to the plain sender
 * name whenever there's no nickname anyway, which is this port's only case).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendDMSheet(
    senderName: String,
    onDismiss: () -> Unit,
    onFetchMatches: (String, (List<ContactDto>) -> Unit) -> Unit,
    onSelect: (ContactDto) -> Unit,
) {
    var isLoading by remember { mutableStateOf(true) }
    var matchingContacts by remember { mutableStateOf<List<ContactDto>>(emptyList()) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(senderName) {
        onFetchMatches(senderName) { matchingContacts = it; isLoading = false }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.chat_send_dm_title, senderName), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            when {
                isLoading -> Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                matchingContacts.isEmpty() -> Text(
                    stringResource(R.string.chat_send_dm_no_contacts, senderName),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(matchingContacts, key = { it.id }) { contact ->
                        ContactMatchRow(contact = contact, style = ContactMatchStyle.Tap, onClick = { onSelect(contact) })
                    }
                }
            }
        }
    }
}
