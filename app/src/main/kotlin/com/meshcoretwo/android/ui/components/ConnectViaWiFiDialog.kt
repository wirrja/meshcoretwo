// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

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
import androidx.compose.runtime.rememberCoroutineScope
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
    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5000") }
    var isConnecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val isValidInput = WiFiAddressValidation.isValidHost(ipAddress) && WiFiAddressValidation.isValidPort(port)

    fun connect() {
        val portNumber = port.toIntOrNull()
        if (portNumber == null) {
            errorMessage = context.getString(R.string.wifi_invalid_port)
            return
        }
        isConnecting = true
        errorMessage = null
        scope.launch {
            try {
                connectionManager.connectViaWiFi(
                    host = WiFiAddressValidation.normalizedHost(ipAddress),
                    port = portNumber,
                    forceFullSync = true,
                )
                onConnected()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorMessage = error.toUiText(UiText.Plain(context.getString(R.string.wifi_connect_failed))).resolve(context)
                isConnecting = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isConnecting) onDismiss() },
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
            TextButton(onClick = onDismiss, enabled = !isConnecting) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
