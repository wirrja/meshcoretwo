// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.android.ui.i18n.UiText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.onboarding.WiFiAddressValidation
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectViaWiFi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Connect via WiFi" — host/port entry plus the async [ConnectionManager.connectViaWiFi] call,
 * shared by [com.meshcoretwo.android.onboarding.PairScreen] (`WiFiConnectionDialog`) and
 * [com.meshcoretwo.android.settings.DeviceSelectionScreen] (`ConnectViaWiFiDialog`) — the two were
 * documented as too different to merge ("surrounding chrome/copy" differs), but were actually
 * byte-identical apart from formatting; that claim held for the *third*, genuinely different dialog
 * this app has, [com.meshcoretwo.android.settings.SettingsScreen]'s `WifiEditDialog` (edits an
 * already-connected device's saved address synchronously, no connect/busy state of its own — left
 * separate). Phase 28.
 */
@Composable
fun ConnectViaWiFiDialog(connectionManager: ConnectionManager, onDismiss: () -> Unit, onConnected: () -> Unit) {
    // The connect itself runs in a ViewModel scoped to the current back-stack entry, so an Activity
    // recreation mid-connect (rotation) neither cancels it nor loses its result; callers keep their
    // "dialog shown" flag in rememberSaveable so the dialog comes back to collect that result.
    val viewModel: WiFiConnectViewModel = viewModel(factory = WiFiConnectViewModel.Factory(connectionManager))
    val status by viewModel.status.collectAsStateWithLifecycle()
    var ipAddress by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("5000") }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val isConnecting = status is WiFiConnectStatus.Connecting
    val errorMessage = validationError ?: (status as? WiFiConnectStatus.Failed)?.error
        ?.toUiText(UiText.Plain(stringResource(R.string.wifi_connect_failed)))?.resolve(context)

    LaunchedEffect(status) {
        if (status is WiFiConnectStatus.Connected) {
            viewModel.reset()
            onConnected()
        }
    }

    val isValidInput = WiFiAddressValidation.isValidHost(ipAddress) && WiFiAddressValidation.isValidPort(port)

    fun connect() {
        val portNumber = port.toIntOrNull()
        if (portNumber == null) {
            validationError = context.getString(R.string.wifi_invalid_port)
            return
        }
        validationError = null
        viewModel.connect(WiFiAddressValidation.normalizedHost(ipAddress), portNumber)
    }

    AlertDialog(
        onDismissRequest = { if (!isConnecting) { viewModel.reset(); onDismiss() } },
        title = { Text(stringResource(R.string.pair_connect_wifi)) },
        text = {
            Column {
                Text(stringResource(R.string.wifi_dialog_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it.replace(',', '.') },
                    label = { Text(stringResource(R.string.wifi_host_label)) },
                    singleLine = true,
                    enabled = !isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.common_port)) },
                    singleLine = true,
                    enabled = !isConnecting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                errorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::connect, enabled = isValidInput && !isConnecting) {
                if (isConnecting) CircularProgressIndicator(modifier = Modifier.size(16.dp)) else Text(stringResource(R.string.common_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.reset(); onDismiss() }, enabled = !isConnecting) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

sealed interface WiFiConnectStatus {
    data object Idle : WiFiConnectStatus
    data object Connecting : WiFiConnectStatus
    data object Connected : WiFiConnectStatus
    data class Failed(val error: Exception) : WiFiConnectStatus
}

/** Owns [ConnectViaWiFiDialog]'s connect call so it outlives an Activity recreation. */
class WiFiConnectViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _status = MutableStateFlow<WiFiConnectStatus>(WiFiConnectStatus.Idle)
    val status: StateFlow<WiFiConnectStatus> = _status.asStateFlow()

    fun connect(host: String, port: Int) {
        if (_status.value is WiFiConnectStatus.Connecting) return
        _status.value = WiFiConnectStatus.Connecting
        viewModelScope.launch {
            _status.value = try {
                connectionManager.connectViaWiFi(host = host, port = port, forceFullSync = true)
                WiFiConnectStatus.Connected
            } catch (error: CancellationException) {
                _status.value = WiFiConnectStatus.Idle
                throw error
            } catch (error: Exception) {
                WiFiConnectStatus.Failed(error)
            }
        }
    }

    /** Back to idle once the dialog has consumed a result or been dismissed. */
    fun reset() {
        if (_status.value !is WiFiConnectStatus.Connecting) _status.value = WiFiConnectStatus.Idle
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WiFiConnectViewModel(connectionManager) as T
    }
}
