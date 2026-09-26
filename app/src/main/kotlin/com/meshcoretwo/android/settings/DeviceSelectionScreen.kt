// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import android.provider.Settings
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.android.ui.i18n.UiText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.meshcoretwo.android.ui.components.ConnectViaWiFiDialog
import com.meshcoretwo.android.ui.components.DeviceScanSheet
import com.meshcoretwo.android.ui.components.EmptyState
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.pairNewDevice
import com.meshcoretwo.services.pairing.DevicePairingError
import com.meshcoretwo.services.pairing.PairingError
import com.meshcoretwo.services.persistence.DeviceDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Saved Devices" — switch between previously paired/connected radios, scan for a new one, or
 * connect a new one over WiFi. Ported from `DeviceSelectionSheet.swift`, reached from Settings'
 * `DeviceSection` (see [SettingsScreen]'s doc) rather than the connection-status popover
 * `RadioStatusIcon` flags as still missing this — this port has no such popover menu at all yet,
 * so a full Settings sub-screen is the reachable equivalent instead of a sheet over the status
 * icon. Unlike most Settings sub-screens, this one is also reachable from `SettingsUiState
 * .Connecting`, matching Swift, where the sheet is available regardless of live connection state
 * — switching away from a stuck "Connecting…" device is exactly the situation this screen exists
 * for.
 *
 * A row's "Connect" button dismisses the screen only after a successful connect (or immediately
 * for the already-connected row, labeled "Done" — see [DeviceSelectionViewModel.connect]'s doc); a
 * failure keeps the screen open with an inline snackbar so the user can pick a different device,
 * rather than Swift's dismiss-then-global-alert (`connectionUI.presentSavedDeviceConnectFailure`)
 * — this port has no such shared post-dismiss alert channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionScreen(connectionManager: ConnectionManager, onBack: () -> Unit) {
    val viewModel: DeviceSelectionViewModel = viewModel(factory = DeviceSelectionViewModel.Factory(connectionManager))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connectingDeviceId by viewModel.connectingDeviceId.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isScanPresenting by connectionManager.pairingService.isPresenting.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showWifiDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DeviceDto?>(null) }
    val unpairFailedDeviceName by viewModel.unpairFailedDeviceName.collectAsStateWithLifecycle()
    var isPairing by remember { mutableStateOf(false) }

    val context = LocalContext.current
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it.resolve(context))
            viewModel.clearError()
        }
    }

    fun startScan() {
        isPairing = true
        scope.launch {
            try {
                connectionManager.pairNewDevice()
                onBack()
            } catch (error: CancellationException) {
                throw error
            } catch (error: DevicePairingError.Cancelled) {
                // User backed out of the scan sheet.
            } catch (error: DevicePairingError.AlreadyInProgress) {
                // No-op — a scan/pair is already running.
            } catch (error: PairingError) {
                snackbarHostState.showSnackbar(error.toUiText(UiText.Plain(context.getString(R.string.wifi_connect_failed))).resolve(context))
            } catch (error: Exception) {
                snackbarHostState.showSnackbar(error.toUiText(UiText.Plain(context.getString(R.string.pair_failed))).resolve(context))
            } finally {
                isPairing = false
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.settings_saved_devices)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val state = uiState) {
                is DeviceSelectionUiState.Loading -> Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is DeviceSelectionUiState.Loaded -> {
                    if (state.devices.isEmpty()) {
                        EmptyState(
                            icon = R.drawable.ic_bluetooth,
                            title = stringResource(R.string.devices_none),
                            description = stringResource(R.string.devices_none_desc),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            items(state.devices, key = { it.id }) { device ->
                                DeviceRow(
                                    device = device,
                                    isCurrentDevice = device.id == state.connectedDeviceId,
                                    isConnectedElsewhere = state.connectedElsewhere.contains(device.id),
                                    isConnecting = connectingDeviceId == device.id,
                                    onConnect = { viewModel.connect(device, onConnected = onBack) },
                                    onDelete = { pendingDelete = device },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedButton(onClick = { showWifiDialog = true }, enabled = !isPairing, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_wifi), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.pair_connect_wifi))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = ::startScan, enabled = !isPairing, modifier = Modifier.fillMaxWidth()) {
                    if (isPairing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.devices_scan))
                    }
                }
            }
        }
    }

    if (showWifiDialog) {
        ConnectViaWiFiDialog(
            connectionManager = connectionManager,
            onDismiss = { showWifiDialog = false },
            onConnected = {
                showWifiDialog = false
                onBack()
            },
        )
    }

    pendingDelete?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.devices_remove_title, device.nodeName)) },
            text = {
                Text(stringResource(if (device.wifiHost != null) R.string.devices_remove_body_wifi else R.string.devices_remove_body_ble))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(device)
                }) { Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    unpairFailedDeviceName?.let { name ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUnpairFailed,
            title = { Text(stringResource(R.string.devices_unpair_failed_title)) },
            text = { Text(stringResource(R.string.devices_unpair_failed_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissUnpairFailed()
                    try {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (error: ActivityNotFoundException) {
                        // No Bluetooth settings screen on this build; nothing else to offer.
                    }
                }) { Text(stringResource(R.string.devices_open_bt_settings)) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissUnpairFailed) { Text(stringResource(R.string.common_close)) } },
        )
    }

    if (isScanPresenting) {
        DeviceScanSheet(
            connectionManager = connectionManager,
            onDismiss = { connectionManager.pairingService.cancel() },
            onSelect = { device -> connectionManager.pairingService.select(device.id) },
        )
    }
}

@Composable
private fun DeviceRow(
    device: DeviceDto,
    isCurrentDevice: Boolean,
    isConnectedElsewhere: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDelete: () -> Unit,
) {
    val extended = LocalMeshExtendedColors.current
    val isWifi = device.wifiHost != null

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(if (isWifi) R.drawable.ic_wifi else R.drawable.ic_bluetooth),
                contentDescription = null,
                tint = if (isWifi) extended.info else extended.success,
                modifier = Modifier.size(28.dp).padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(device.nodeName, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        isConnectedElsewhere -> stringResource(R.string.devices_connected_elsewhere)
                        isWifi -> "${device.wifiHost}:${device.wifiPort ?: 5000}"
                        else -> stringResource(R.string.permissions_bluetooth_title)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnectedElsewhere) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isCurrentDevice) {
                Icon(
                    painterResource(R.drawable.ic_check_circle),
                    contentDescription = stringResource(R.string.devices_connected),
                    tint = extended.radioReady,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (isConnecting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            Row {
                TextButton(onClick = onConnect, enabled = !isConnectedElsewhere) { Text(if (isCurrentDevice) stringResource(R.string.common_done) else stringResource(R.string.common_connect)) }
                IconButton(onClick = onDelete) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_remove), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

