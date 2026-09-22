// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.ExpandableSectionCard
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.location.LocationProvider
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Room settings — device info/radio/identity/contact info/security/device actions (shared with
 * [RepeaterSettingsScreen] via `SharedNodeSettingsSections.kt`) plus room-only room access (guest
 * password + read-only) and a trimmed behavior section (no repeat-mode toggle, no regions). Ported
 * from `RoomSettingsView.swift`. See [RepeaterSettingsScreen]'s class doc for the
 * separate-navigable-screen-vs-merged-tab-picker architecture decision, which applies here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSettingsScreen(
    connectionManager: ConnectionManager,
    locationProvider: LocationProvider,
    sessionId: UUID,
    onBack: () -> Unit,
) {
    val viewModel: RoomSettingsViewModel = viewModel(factory = RoomSettingsViewModel.Factory(connectionManager, sessionId))
    val settingsState by viewModel.settings.uiState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showingLocationPicker by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.nodeadmin_room_settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { NodeSettingsHeader(name = settingsState.session?.name ?: "", category = AvatarCategory.ROOM) }

            item { RoomAccessSection(uiState, viewModel) }

            item {
                NodeRadioSettingsSection(
                    state = settingsState,
                    settings = viewModel.settings,
                    onReload = { scope.launch { viewModel.settings.fetchRadioSettings() } },
                    onApply = { scope.launch { viewModel.settings.applyRadioSettings() } },
                    restartWarning = R.string.nodeadmin_restart_room,
                )
            }

            item { RoomBehaviorSection(uiState, viewModel) }

            item {
                NodeIdentitySection(
                    state = settingsState,
                    settings = viewModel.settings,
                    onReload = { scope.launch { viewModel.settings.fetchIdentity() } },
                    onApply = { scope.launch { viewModel.settings.applyIdentitySettings() } },
                    onPickLocation = { showingLocationPicker = true },
                )
            }

            item {
                NodeContactInfoSection(
                    state = settingsState,
                    settings = viewModel.settings,
                    onReload = { scope.launch { viewModel.settings.fetchContactInfo() } },
                    onApply = { scope.launch { viewModel.settings.applyContactInfoSettings() } },
                )
            }

            item {
                NodeSecuritySection(
                    state = settingsState,
                    settings = viewModel.settings,
                    onApply = { scope.launch { viewModel.settings.changePassword() } },
                )
            }

            item {
                NodeDeviceInfoSection(
                    state = settingsState,
                    settings = viewModel.settings,
                    onReload = { scope.launch { viewModel.settings.fetchDeviceInfo() } },
                )
            }

            item {
                NodeActionsSection(
                    state = settingsState,
                    onForceAdvert = { scope.launch { viewModel.settings.forceAdvert() } },
                    onSyncTime = { scope.launch { viewModel.settings.syncTime() } },
                    onReboot = { scope.launch { viewModel.settings.reboot() } },
                    rebootConfirmTitle = R.string.nodeadmin_reboot_room_title,
                    rebootMessage = R.string.nodeadmin_reboot_room_msg,
                )
            }
        }
    }

    if (settingsState.showSuccessAlert) {
        AlertDialog(
            onDismissRequest = viewModel.settings::dismissSuccessAlert,
            title = { Text(stringResource(R.string.common_success)) },
            text = { Text(settingsState.successMessage?.asString() ?: stringResource(R.string.nodeset_settings_applied)) },
            confirmButton = { TextButton(onClick = viewModel.settings::dismissSuccessAlert) { Text(stringResource(R.string.common_ok)) } },
        )
    }

    if (showingLocationPicker) {
        RemoteNodeLocationPickerScreen(
            initialLatitude = settingsState.latitude,
            initialLongitude = settingsState.longitude,
            locationProvider = locationProvider,
            onSave = { latitude, longitude -> viewModel.settings.setLocationFromPicker(latitude, longitude); showingLocationPicker = false },
            onCancel = { showingLocationPicker = false },
        )
    }
}

@Composable
private fun RoomAccessSection(uiState: RoomSettingsUiState, viewModel: RoomSettingsViewModel) {
    ExpandableSectionCard(
        title = stringResource(R.string.nodeadmin_room_settings),
        expanded = uiState.isRoomAccessExpanded,
        isLoading = uiState.isLoadingRoomAccess,
        onToggle = {
            val expand = !uiState.isRoomAccessExpanded
            viewModel.setRoomAccessExpanded(expand)
            if (expand && !uiState.roomAccessLoaded && !uiState.isLoadingRoomAccess) viewModel.fetchRoomAccess()
        },
        onReload = if (uiState.roomAccessLoaded) viewModel::fetchRoomAccess else null,
    ) {
        when {
            uiState.isLoadingRoomAccess && !uiState.roomAccessLoaded -> SectionLoadingRow()
            uiState.roomAccessError && !uiState.roomAccessLoaded -> ErrorRetryRow(onRetry = viewModel::fetchRoomAccess)
            uiState.roomAccessLoaded -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = uiState.guestPassword ?: "",
                    onValueChange = viewModel::setGuestPassword,
                    label = { Text(stringResource(R.string.nodeadmin_guest_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nodeadmin_allow_readonly))
                    Switch(checked = uiState.allowReadOnly ?: false, onCheckedChange = viewModel::setAllowReadOnly)
                }
                Text(
                    stringResource(R.string.nodeadmin_allow_readonly_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AsyncApplyButton(
                    stringResource(R.string.nodeadmin_apply_room),
                    isLoading = uiState.isApplyingRoomAccess,
                    showSuccess = uiState.roomAccessApplySuccess,
                    enabled = uiState.roomAccessModified,
                    onClick = viewModel::applyRoomAccess,
                )
            }
        }
    }
}

@Composable
private fun RoomBehaviorSection(uiState: RoomSettingsUiState, viewModel: RoomSettingsViewModel) {
    ExpandableSectionCard(
        title = stringResource(R.string.nodeadmin_behavior),
        expanded = uiState.isBehaviorExpanded,
        isLoading = uiState.isLoadingBehavior,
        onToggle = {
            val expand = !uiState.isBehaviorExpanded
            viewModel.setBehaviorExpanded(expand)
            if (expand && !uiState.behaviorLoaded && !uiState.isLoadingBehavior) viewModel.fetchBehaviorSettings()
        },
        onReload = if (uiState.behaviorLoaded) viewModel::fetchBehaviorSettings else null,
    ) {
        when {
            uiState.isLoadingBehavior && !uiState.behaviorLoaded -> SectionLoadingRow()
            uiState.behaviorError && !uiState.behaviorLoaded -> ErrorRetryRow(onRetry = viewModel::fetchBehaviorSettings)
            uiState.behaviorLoaded -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IntFieldRow("Advert Interval (0-hop, min)", uiState.advertIntervalMinutes, viewModel::setAdvertIntervalMinutes, uiState.advertIntervalError)
                IntFieldRow("Advert Interval (flood, hrs)", uiState.floodAdvertIntervalHours, viewModel::setFloodAdvertIntervalHours, uiState.floodAdvertIntervalError)
                IntFieldRow("Max Flood Hops", uiState.floodMaxHops, viewModel::setFloodMaxHops, uiState.floodMaxHopsError)
                AsyncApplyButton(
                    stringResource(R.string.nodeadmin_apply_behavior),
                    isLoading = uiState.isApplyingBehavior,
                    showSuccess = uiState.behaviorApplySuccess,
                    enabled = uiState.behaviorModified,
                    onClick = viewModel::applyBehaviorSettings,
                )
            }
        }
    }
}
