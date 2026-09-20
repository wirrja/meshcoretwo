// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.ConfirmDialog
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.notifications.NotificationPreferencesStore
import com.meshcoretwo.services.settings.DevicePreferenceStore
import com.meshcoretwo.services.settings.StaleNodeCleanupPreferencesStore

/**
 * Settings → Danger zone: reboot, remove non-favorite nodes, factory reset, forget device. Split
 * out of the main Settings list (where [DangerZoneEntry] now links here). Ported from
 * `DangerZoneSection.swift`. Pops back on its own once the radio drops out of the ready state —
 * after a factory reset or forgetting the device there is nothing left to act on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DangerZoneScreen(
    connectionManager: ConnectionManager,
    notificationPreferencesStore: NotificationPreferencesStore,
    staleNodeCleanupPreferencesStore: StaleNodeCleanupPreferencesStore,
    devicePreferenceStore: DevicePreferenceStore,
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(connectionManager, notificationPreferencesStore, staleNodeCleanupPreferencesStore, devicePreferenceStore),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var wasReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(uiState) {
        if (uiState is SettingsUiState.Ready) wasReady = true else if (wasReady) onBack()
    }

    val context = LocalContext.current
    LaunchedEffect(errorMessage, statusMessage) {
        val message = errorMessage ?: statusMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message.resolve(context))
            viewModel.clearMessages()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.common_danger_zone)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (uiState is SettingsUiState.Ready) {
                DangerZoneActions(isBusy = isBusy, viewModel = viewModel)
            } else {
                Text(stringResource(R.string.settings_connecting))
            }
        }
    }
}

@Composable
private fun DangerZoneActions(isBusy: Boolean, viewModel: SettingsViewModel) {
    var confirmAction by remember { mutableStateOf<DangerAction?>(null) }
    var showForgetConfirmation by remember { mutableStateOf(false) }

    DangerRow(stringResource(R.string.danger_reboot_row), isBusy) { confirmAction = DangerAction.REBOOT }
    HorizontalDivider()
    DangerRow(stringResource(R.string.danger_remove_nonfav_row), isBusy) { confirmAction = DangerAction.REMOVE_UNFAVORITED }
    HorizontalDivider()
    DangerRow(stringResource(R.string.danger_factory_reset_row), isBusy) { confirmAction = DangerAction.FACTORY_RESET }
    HorizontalDivider()
    DangerRow(stringResource(R.string.danger_forget_row), isBusy) { showForgetConfirmation = true }

    if (isBusy) {
        Spacer(modifier = Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.danger_working), style = MaterialTheme.typography.bodySmall)
        }
    }

    confirmAction?.let { action ->
        ConfirmDialog(
            title = stringResource(action.title),
            body = stringResource(action.body),
            confirmLabel = stringResource(action.confirmLabel),
            onDismiss = { confirmAction = null },
            onConfirm = {
                when (action) {
                    DangerAction.REBOOT -> viewModel.reboot()
                    DangerAction.REMOVE_UNFAVORITED -> viewModel.removeUnfavoritedNodes()
                    DangerAction.FACTORY_RESET -> viewModel.factoryReset()
                }
            },
        )
    }

    if (showForgetConfirmation) {
        ForgetDeviceDialog(
            onDismiss = { showForgetConfirmation = false },
            onKeepData = {
                viewModel.forgetDevice(deleteData = false)
                showForgetConfirmation = false
            },
            onDeleteData = {
                viewModel.forgetDevice(deleteData = true)
                showForgetConfirmation = false
            },
        )
    }
}

private enum class DangerAction(@StringRes val title: Int, @StringRes val body: Int, @StringRes val confirmLabel: Int) {
    REBOOT(R.string.danger_reboot_title, R.string.danger_reboot_body, R.string.nodeadmin_reboot),
    REMOVE_UNFAVORITED(R.string.danger_remove_title, R.string.danger_remove_body, R.string.common_remove),
    FACTORY_RESET(R.string.danger_factory_title, R.string.danger_factory_body, R.string.danger_reset_confirm),
}

/**
 * The "Forget device" confirmation — unlike [DangerAction]'s single-confirm [ConfirmDialog], this
 * mirrors `DangerZoneSection.swift`'s `.confirmationDialog` (title/message copy from
 * `Settings.strings`'s `dangerZone.dialog.forget.*` keys), which offers two destructive choices
 * instead of one: keep the device's data (ghost-demote it, see
 * [com.meshcoretwo.services.connection.forgetDevice]'s doc) or delete it outright. Built from
 * [AlertDialog]'s two button slots — [confirmButton] holds the more destructive "Delete data", and
 * [dismissButton] holds both "Cancel" and "Keep data" side by side — since Compose's `AlertDialog`
 * has no third slot the way SwiftUI's `.confirmationDialog` action sheet does.
 */
@Composable
private fun ForgetDeviceDialog(onDismiss: () -> Unit, onKeepData: () -> Unit, onDeleteData: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.danger_forget_title)) },
        text = { Text(stringResource(R.string.danger_forget_body)) },
        confirmButton = {
            TextButton(onClick = onDeleteData) { Text(stringResource(R.string.danger_delete_data), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                TextButton(onClick = onKeepData) { Text(stringResource(R.string.danger_keep_data), color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}

@Composable
private fun DangerRow(label: String, isBusy: Boolean, onClick: () -> Unit) {
    SettingsListRow(
        title = label,
        titleColor = MaterialTheme.colorScheme.error,
        trailing = { TextButton(onClick = onClick, enabled = !isBusy) { Text(stringResource(R.string.common_continue), color = MaterialTheme.colorScheme.error) } },
    )
}
