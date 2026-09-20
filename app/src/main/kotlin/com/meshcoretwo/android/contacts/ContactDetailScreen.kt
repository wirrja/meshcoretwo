// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.ui.components.SectionCard
import androidx.compose.material3.TopAppBarDefaults
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.ConfirmDialog
import com.meshcoretwo.android.ui.components.DetailRow
import com.meshcoretwo.android.ui.components.FavoriteToggleButton
import com.meshcoretwo.android.ui.components.FullScreenMessage
import com.meshcoretwo.android.ui.components.InitialsAvatar
import com.meshcoretwo.android.ui.components.LoadingScreen
import com.meshcoretwo.android.ui.components.SettingsGroupLabel
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.android.ui.formatRelativeTimestamp
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.persistence.ContactDto
import java.util.UUID

/**
 * Contact detail — a trimmed port of `ContactDetailView.swift`. See [ContactDetailViewModel]'s
 * class doc for what's ported vs. deferred (no QR/advert sharing, path discover/edit/reset,
 * embedded map). Repeater/room contacts now get a "Connect"/"Admin Access" action to
 * [com.meshcoretwo.android.contacts.NodeAuthScreen] (see its class doc for what login does and
 * doesn't do yet); a successful login continues into
 * [com.meshcoretwo.android.contacts.RoomStatusScreen] or
 * [com.meshcoretwo.android.contacts.RepeaterStatusScreen] depending on role. Every contact type
 * links to [TelemetryHistoryOverviewScreen], its stored snapshot history, as in Swift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    connectionManager: ConnectionManager,
    contactId: UUID,
    onBack: () -> Unit,
    onOpenConversation: (UUID) -> Unit,
    onOpenNodeAuth: (UUID) -> Unit,
    onOpenTelemetryHistory: (UUID) -> Unit,
    onOpenQRShare: (name: String, publicKeyHex: String, contactTypeValue: Int) -> Unit,
) {
    val viewModel: ContactDetailViewModel = viewModel(factory = ContactDetailViewModel.Factory(connectionManager, contactId))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.deleted.collect { onBack() }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text((state as? ContactDetailUiState.Loaded)?.contact?.displayName ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    (state as? ContactDetailUiState.Loaded)?.contact?.let { contact ->
                        IconButton(onClick = {
                            onOpenQRShare(contact.displayName, contact.publicKey.hexString, contact.type.value.toInt())
                        }) { Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.contacts_share_qr)) }
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            is ContactDetailUiState.Loading -> LoadingScreen(modifier = Modifier.padding(padding))
            is ContactDetailUiState.NotFound -> FullScreenMessage(
                stringResource(R.string.contacts_unavailable),
                modifier = Modifier.padding(padding),
            )
            is ContactDetailUiState.Loaded -> ContactDetailContent(
                modifier = Modifier.padding(padding),
                state = current,
                viewModel = viewModel,
                onOpenConversation = onOpenConversation,
                onOpenNodeAuth = onOpenNodeAuth,
                onOpenTelemetryHistory = onOpenTelemetryHistory,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactDetailContent(
    modifier: Modifier,
    state: ContactDetailUiState.Loaded,
    viewModel: ContactDetailViewModel,
    onOpenConversation: (UUID) -> Unit,
    onOpenNodeAuth: (UUID) -> Unit,
    onOpenTelemetryHistory: (UUID) -> Unit,
) {
    val contact = state.contact
    val context = LocalContext.current
    var nicknameInput by remember(contact.nickname) { mutableStateOf(contact.nickname ?: "") }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(name = contact.displayName, size = 80.dp, category = AvatarCategory.fromContactType(contact.type))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(contact.displayName, style = MaterialTheme.typography.headlineSmall)
                Text(contact.type.label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (contact.isBlocked) Text(stringResource(R.string.common_blocked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = nicknameInput,
                onValueChange = { nicknameInput = it },
                label = { Text(stringResource(R.string.contacts_nickname)) },
                placeholder = { Text(contact.name) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (nicknameInput != (contact.nickname ?: "")) {
                        TextButton(onClick = { viewModel.updateNickname(nicknameInput) }) { Text(stringResource(R.string.common_save)) }
                    }
                },
            )

            SectionCard {
            // Actions are full-width rows (not pill buttons): the label gets the whole width, so long
            // translations never squeeze or break it.
            if (contact.type == ContactType.CHAT && !contact.isBlocked) {
                SettingsListRow(title = stringResource(R.string.contacts_send_message), icon = R.drawable.ic_chat, onClick = { onOpenConversation(contact.id) })
                HorizontalDivider()
            }
            if (contact.type == ContactType.REPEATER || contact.type == ContactType.ROOM) {
                SettingsListRow(
                    title = stringResource(if (contact.type == ContactType.ROOM) R.string.contacts_join_room else R.string.contacts_admin_access),
                    icon = if (contact.type == ContactType.ROOM) R.drawable.ic_meeting_room else R.drawable.ic_lock,
                    onClick = { onOpenNodeAuth(contact.id) },
                )
                HorizontalDivider()
            }
            FavoriteToggleButton(isFavorite = contact.isFavorite, onToggle = viewModel::toggleFavorite)
            HorizontalDivider()
            SettingsListRow(title = stringResource(R.string.contacts_telemetry_history), onClick = { onOpenTelemetryHistory(contact.id) })
            }
        }

        SectionCard {
            SettingsGroupLabel(stringResource(R.string.contacts_info))
            SelectionContainer {
                Text(
                    contact.publicKeyHex(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                        .padding(12.dp),
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            DetailRow(stringResource(R.string.contacts_last_heard), contact.lastHeardTimestamp.toInstantOrNull()?.let { formatRelativeTimestamp(it) } ?: stringResource(R.string.common_never))
            DetailRow(stringResource(R.string.contacts_last_advert), contact.lastAdvertTimestamp.toInstantOrNull()?.let { formatRelativeTimestamp(it) } ?: stringResource(R.string.common_never))
            DetailRow(stringResource(R.string.contacts_unread), contact.unreadCount.toString())
        }

        SectionCard {
            SettingsGroupLabel(stringResource(R.string.contacts_path))
            DetailRow(stringResource(R.string.contacts_route), contact.routeLabel())
        }

        if (contact.hasLocation) {
            SectionCard {
                SettingsGroupLabel(stringResource(R.string.contacts_location))
                DetailRow(stringResource(R.string.contacts_coordinates), "%.5f, %.5f".format(contact.latitude, contact.longitude))
                SettingsListRow(title = stringResource(R.string.contacts_open_maps), icon = R.drawable.ic_map, onClick = {
                    val uri = Uri.parse("geo:${contact.latitude},${contact.longitude}?q=${contact.latitude},${contact.longitude}")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                })
            }
        }

        if (contact.type == ContactType.CHAT || !state.isVContact) {
            SectionCard {
                if (contact.type == ContactType.CHAT) {
                    SettingsGroupLabel(stringResource(R.string.common_danger_zone))
                    SettingsListRow(
                        title = stringResource(if (contact.isBlocked) R.string.common_unblock else R.string.common_block),
                        titleColor = if (contact.isBlocked) Color.Unspecified else MaterialTheme.colorScheme.error,
                        onClick = { if (contact.isBlocked) viewModel.setBlocked(false) else showBlockConfirm = true },
                    )
                    HorizontalDivider()
                    SettingsListRow(title = stringResource(R.string.channel_info_clear_messages), titleColor = MaterialTheme.colorScheme.error, onClick = { showClearConfirm = true })
                    if (!state.isVContact) HorizontalDivider()
                }
                if (!state.isVContact) {
                    SettingsListRow(title = stringResource(R.string.contacts_delete_contact), titleColor = MaterialTheme.colorScheme.error, onClick = { showDeleteConfirm = true })
                }
            }
        }
    }

    if (showBlockConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.chats_block_title, contact.displayName),
            body = stringResource(R.string.chats_block_body),
            confirmLabel = stringResource(R.string.common_block),
            onConfirm = { viewModel.setBlocked(true) },
            onDismiss = { showBlockConfirm = false },
        )
    }
    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.channel_info_clear_title),
            body = stringResource(R.string.contacts_clear_body, contact.displayName),
            confirmLabel = stringResource(R.string.common_clear),
            onConfirm = viewModel::clearMessages,
            onDismiss = { showClearConfirm = false },
        )
    }
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.chats_delete_title, contact.displayName),
            body = stringResource(R.string.chats_delete_body),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = viewModel::delete,
            onDismiss = { showDeleteConfirm = false },
        )
    }
}


@Composable
private fun ContactType.label(): String = stringResource(
    when (this) {
        ContactType.CHAT -> R.string.map_type_contact
        ContactType.REPEATER -> R.string.map_type_repeater
        ContactType.ROOM -> R.string.contacts_type_room_server
    },
)
