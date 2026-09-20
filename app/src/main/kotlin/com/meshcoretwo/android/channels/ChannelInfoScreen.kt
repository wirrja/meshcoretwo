// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.channels

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.ConfirmDialog
import com.meshcoretwo.android.ui.components.FavoriteToggleButton
import com.meshcoretwo.android.ui.components.FullScreenMessage
import com.meshcoretwo.android.ui.components.InitialsAvatar
import com.meshcoretwo.android.ui.components.LoadingScreen
import com.meshcoretwo.android.ui.components.QRSharePanel
import com.meshcoretwo.android.ui.components.SettingsGroupLabel
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.android.ui.components.generateQRCodeBitmap
import com.meshcoretwo.android.ui.i18n.label
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ChannelFloodScope
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.settings.isPrivateRegion

/** Ported from `ChannelInfoSheet.swift` — see [ChannelInfoViewModel]'s class doc for what's deferred. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelInfoScreen(connectionManager: ConnectionManager, index: UByte, onBack: () -> Unit, onOpenRegionManagement: () -> Unit) {
    val viewModel: ChannelInfoViewModel = viewModel(factory = ChannelInfoViewModel.Factory(connectionManager, index))
    // Re-reads the connected device (knownRegions in particular) whenever this screen re-enters
    // composition — e.g. returning from "Manage Regions" after a discovery run, which mutates
    // ConnectionManager.connectedDevice out from under this screen's own cached [ChannelInfoUiState].
    LaunchedEffect(Unit) { viewModel.refresh() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.left.collect { onBack() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? ChannelInfoUiState.Loaded)?.channel?.name ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        when (val current = state) {
            is ChannelInfoUiState.Loading -> LoadingScreen(modifier = Modifier.padding(padding))
            is ChannelInfoUiState.NotFound -> FullScreenMessage(
                stringResource(R.string.channel_info_unavailable),
                modifier = Modifier.padding(padding),
            )
            is ChannelInfoUiState.Loaded -> ChannelInfoContent(
                modifier = Modifier.padding(padding),
                channel = current.channel,
                device = current.device,
                viewModel = viewModel,
                onOpenRegionManagement = onOpenRegionManagement,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelInfoContent(
    modifier: Modifier,
    channel: ChannelDto,
    device: DeviceDto?,
    viewModel: ChannelInfoViewModel,
    onOpenRegionManagement: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var showClearConfirm by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(name = channel.name, size = 72.dp, category = AvatarCategory.CHANNEL, isPublicChannel = channel.isPublicChannel)
            Spacer(modifier = Modifier.size(16.dp))
            Column {
                Text(channel.name, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(channel.typeLabelRes()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        FavoriteToggleButton(isFavorite = channel.isFavorite, onToggle = viewModel::toggleFavorite)

        Column {
            SettingsGroupLabel(stringResource(R.string.common_notifications))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotificationLevel.entries.forEach { level ->
                    FilterChip(
                        selected = channel.notificationLevel == level,
                        onClick = { viewModel.setNotificationLevel(level) },
                        label = { Text(level.label()) },
                    )
                }
            }
        }

        Column {
            ChannelRegionSection(
                channel = channel,
                device = device,
                onFloodScopeSelected = viewModel::setFloodScope,
                onOpenRegionManagement = onOpenRegionManagement,
            )
        }

        Column {
            ChannelShareQRSection(channel = channel)
        }

        if (channel.hasSecret && !channel.isPublicChannel) {
            Column {
                SettingsGroupLabel(stringResource(R.string.channel_info_secret))
                SelectionContainer {
                    Text(channel.secretHex(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.size(4.dp))
                SettingsListRow(title = stringResource(R.string.channel_info_copy_invite), icon = R.drawable.ic_content_copy, onClick = {
                    val link = ChannelService.exportChannelURI(channel.name, channel.secret, channel.floodScope)
                    clipboard.setText(AnnotatedString(link))
                })
            }
        }

        Column {
            SettingsGroupLabel(stringResource(R.string.common_danger_zone))
            SettingsListRow(title = stringResource(R.string.channel_info_clear_messages), titleColor = MaterialTheme.colorScheme.error, onClick = { showClearConfirm = true })
            HorizontalDivider()
            SettingsListRow(title = stringResource(R.string.channel_info_leave_channel), titleColor = MaterialTheme.colorScheme.error, onClick = { showLeaveConfirm = true })
        }
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.channel_info_clear_title),
            body = stringResource(R.string.channel_info_clear_body, channel.name),
            confirmLabel = stringResource(R.string.common_clear),
            onConfirm = viewModel::clearMessages,
            onDismiss = { showClearConfirm = false },
        )
    }
    if (showLeaveConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.channel_info_leave_title, channel.name),
            body = stringResource(R.string.channel_info_leave_body),
            confirmLabel = stringResource(R.string.common_leave),
            onConfirm = viewModel::leave,
            onDismiss = { showLeaveConfirm = false },
        )
    }
}

/**
 * "Scan to join" QR code, ported from `ChannelInfoSheet.swift`'s `ChannelInfoQRCodeSection`.
 * Regenerated whenever [ChannelDto.floodScope] changes (Swift: `.task(id: floodScope)`), matching
 * `remember`'s natural key-based recompute here. Shown for every channel, including the public one
 * (unlike the raw-secret section below it, which Swift and this port both hide for the public
 * channel — a public join needs no secret).
 */
@Composable
private fun ChannelShareQRSection(channel: ChannelDto) {
    val link = remember(channel.name, channel.secret, channel.floodScope) {
        ChannelService.exportChannelURI(channel.name, channel.secret, channel.floodScope)
    }
    val qrBitmap = remember(link) { generateQRCodeBitmap(link, CHANNEL_QR_SIZE_PX) }

    SettingsGroupLabel(stringResource(R.string.channel_info_share))
    QRSharePanel(modifier = Modifier.fillMaxWidth()) {
        qrBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.channel_info_qr_description, channel.name),
                modifier = Modifier.size(180.dp),
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(stringResource(R.string.channel_info_scan_to_join), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val CHANNEL_QR_SIZE_PX = 600

/**
 * Per-channel flood-scope picker. Ported from `ChannelInfoSheet.swift`'s `ChannelInfoRegionSection`
 * / `ChannelInfoRegionPickerContent`, folded into a single dropdown row — the same simplification
 * [com.meshcoretwo.android.settings.SettingsScreen]'s `DefaultFloodScopeSection` already applies to
 * `DefaultFloodScopeSection.swift`'s menu-style picker (see that composable's doc for why: this
 * screen's own `NavigationDestination` to region management doesn't need to keep this row's picker
 * state alive the way Swift's shared `@State` does). "Manage Regions" navigates to the existing
 * [com.meshcoretwo.android.settings.RegionManagementScreen] instead of duplicating its discovery/
 * add-manually flow inline.
 *
 * Private regions ([isPrivateRegion]) are listed but disabled — matching Swift's comment "can't
 * scope to these": a per-channel override needs a plain region name the firmware can hash into a
 * scope key, not a pre-shared-key-gated private region.
 */
@Composable
private fun ChannelRegionSection(
    channel: ChannelDto,
    device: DeviceDto?,
    onFloodScopeSelected: (ChannelFloodScope) -> Unit,
    onOpenRegionManagement: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val knownRegions = device?.knownRegions.orEmpty()
    val defaultName = device?.defaultFloodScopeName?.takeIf { it.isNotEmpty() }
    val sortedPublic = remember(knownRegions) { knownRegions.filterNot { it.isPrivateRegion }.sorted() }
    val sortedPrivate = remember(knownRegions) { knownRegions.filter { it.isPrivateRegion }.sorted() }

    val valueLabel = when {
        knownRegions.isEmpty() -> stringResource(R.string.channel_info_not_configured)
        channel.floodScope is ChannelFloodScope.Inherit && defaultName != null -> defaultName
        channel.floodScope is ChannelFloodScope.Inherit -> stringResource(R.string.common_all_regions)
        channel.floodScope is ChannelFloodScope.AllRegions -> stringResource(R.string.common_all_regions)
        else -> (channel.floodScope as ChannelFloodScope.Region).name
    }
    /** When there's no device default, `.inherit` and `.allRegions` collapse into one row —
     * selecting it writes `.inherit` so a later default change flows through naturally. */
    val allRegionsSelection = if (defaultName != null) ChannelFloodScope.AllRegions else ChannelFloodScope.Inherit

    SettingsGroupLabel(stringResource(R.string.common_region))
    Box {
        SettingsListRow(
            title = stringResource(R.string.channel_info_scope),
            value = valueLabel,
            onClick = if (knownRegions.isNotEmpty()) { { expanded = true } } else null,
            trailingIcon = R.drawable.ic_expand_more,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (defaultName != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.channel_info_use_default, defaultName)) },
                    onClick = { expanded = false; onFloodScopeSelected(ChannelFloodScope.Inherit) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_all_regions)) },
                onClick = { expanded = false; onFloodScopeSelected(allRegionsSelection) },
            )
            sortedPublic.forEach { region ->
                DropdownMenuItem(
                    text = { Text(region) },
                    onClick = { expanded = false; onFloodScopeSelected(ChannelFloodScope.Region(region)) },
                )
            }
            sortedPrivate.forEach { region ->
                DropdownMenuItem(text = { Text(region) }, enabled = false, onClick = {}, trailingIcon = {
                    Text(stringResource(R.string.common_private), style = MaterialTheme.typography.labelSmall)
                })
            }
        }
    }
    SettingsListRow(title = stringResource(R.string.common_manage_regions), onClick = onOpenRegionManagement)
}


