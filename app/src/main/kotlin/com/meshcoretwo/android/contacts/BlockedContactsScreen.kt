// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.EmptyState
import com.meshcoretwo.android.ui.components.LoadingScreen
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.persistence.ContactDto

/**
 * Blocked-contacts management screen. Ported from `BlockedContactsView.swift` — a filtered read of
 * the same contacts list, reachable from [ContactsListScreen]'s toolbar. Tapping a row pushes
 * [ContactDetailScreen], where the existing block/unblock toggle already lives; this screen offers
 * no inline unblock action, matching iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedContactsScreen(connectionManager: ConnectionManager, onOpenContact: (ContactDto) -> Unit, onBack: () -> Unit) {
    val viewModel: BlockedContactsViewModel = viewModel(factory = BlockedContactsViewModel.Factory(connectionManager))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.contacts_blocked_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        when (val current = state) {
            is BlockedContactsUiState.Loading -> LoadingScreen(modifier = Modifier.padding(padding))
            is BlockedContactsUiState.Ready -> {
                if (current.contacts.isEmpty()) {
                    EmptyState(
                        icon = R.drawable.ic_block,
                        title = stringResource(R.string.contacts_blocked_empty),
                        description = stringResource(R.string.contacts_blocked_empty_desc),
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    )
                } else {
                    LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                        items(current.contacts, key = { it.id }) { contact ->
                            ContactRow(contact = contact, userLocation = null, onClick = { onOpenContact(contact) })
                        }
                    }
                }
            }
        }
    }
}
