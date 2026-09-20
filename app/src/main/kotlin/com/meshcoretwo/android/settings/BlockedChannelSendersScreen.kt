// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import com.meshcoretwo.android.ui.i18n.DatePatterns
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.EmptyState
import com.meshcoretwo.android.ui.components.LoadingScreen
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.persistence.BlockedChannelSenderDto
import java.time.ZoneId
import java.time.format.DateTimeFormatter


/**
 * "Blocked Channel Senders" — the list-management half of `BlockedChannelSendersView.swift`
 * (unblock via a per-row action, matching this port's established delete-icon convention —
 * see [RegionManagementScreen]'s doc — instead of Swift's `.onDelete` swipe/`EditButton`).
 * Reached from Settings' new "Blocking" section rather than iOS's own `SettingsSubpage
 * .blockedChannelSenders`/`BlockingSection.swift` grouping, which also lists "Blocked Contacts" —
 * that destination already lives in [com.meshcoretwo.android.contacts.ContactsListScreen]'s
 * toolbar on this port (an earlier, independent placement decision), so it isn't duplicated or
 * moved here.
 *
 * This screen only manages senders already blocked — the *block* action itself
 * (`ChatConversationView.performBlock`/`BlockSenderSheet`, a channel message's long-press "Block
 * sender") lives in [com.meshcoretwo.android.chat.BlockSenderSheet], reached from
 * [com.meshcoretwo.android.chat.MessageActionsSheet]'s "Block Sender" row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedChannelSendersScreen(connectionManager: ConnectionManager, onBack: () -> Unit) {
    val viewModel: BlockedChannelSendersViewModel = viewModel(factory = BlockedChannelSendersViewModel.Factory(connectionManager))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_blocked_senders)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            is BlockedChannelSendersUiState.Loading -> LoadingScreen(modifier = Modifier.padding(padding))
            is BlockedChannelSendersUiState.Ready -> {
                if (current.senders.isEmpty()) {
                    EmptyState(
                        icon = R.drawable.ic_block,
                        title = stringResource(R.string.blocked_senders_none),
                        description = stringResource(R.string.blocked_senders_none_desc),
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    )
                } else {
                    LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                        items(current.senders, key = { it.id }) { sender ->
                            BlockedSenderRow(sender = sender, onUnblock = { viewModel.unblock(sender) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedSenderRow(sender: BlockedChannelSenderDto, onUnblock: () -> Unit) {
    SettingsListRow(
        title = sender.name,
        value = DatePatterns.dateMedium().format(sender.dateBlocked),
        modifier = Modifier.padding(horizontal = 16.dp),
        trailing = {
            IconButton(onClick = onUnblock) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_unblock), tint = MaterialTheme.colorScheme.error)
            }
        },
    )
}
