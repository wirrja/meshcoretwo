// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.onboarding

import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.android.ui.i18n.UiText
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.AppViewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.ConnectViaWiFiDialog
import com.meshcoretwo.android.ui.components.DeviceScanSheet
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.clearStalePairings
import com.meshcoretwo.services.connection.pairNewDevice
import com.meshcoretwo.services.pairing.DevicePairingError
import com.meshcoretwo.services.pairing.PairingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Ported from `DeviceScanView.swift`, using the in-app scan picker pattern
 * (`DeviceScannerSheet.swift`, iOS's macOS-only "no system pairing registry" path) as the
 * *primary* pairing UI, since Android has no AccessorySetupKit-equivalent system picker — see
 * `ConnectionManagerPairing.kt`'s class doc / PLAN.md's slice-D rationale.
 *
 * A successful pair navigates on to Region/Preset ([onPaired]), matching iOS's fixed step order
 * (`pair` -> `region` -> `preset`) — see [com.meshcoretwo.android.onboarding.OnboardingNavHost].
 * "I don’t have a device yet" still completes onboarding directly: there's no radio to pick a
 * preset for.
 *
 * WiFi pairing (`WiFiConnectionSheet.swift`) is offered as a secondary "Connect via WiFi" text
 * button, mirroring iOS's sheet with a single [AlertDialog] instead of a `NavigationStack` form —
 * this dialog is [com.meshcoretwo.android.ui.components.ConnectViaWiFiDialog], shared with
 * [com.meshcoretwo.android.settings.DeviceSelectionScreen]. It was independently duplicated under
 * a different name in both files until Phase 28 found the two were actually byte-identical (only
 * [com.meshcoretwo.android.settings.SettingsScreen]'s *edit*-existing-connection `WifiEditDialog`
 * — synchronous save, no busy/connect state of its own — is genuinely different and stays separate).
 */
@Composable
fun PairScreen(appViewModel: AppViewModel, onPaired: () -> Unit) {
    val connectionManager = appViewModel.connectionManager
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var uiState by remember { mutableStateOf<PairUiState>(PairUiState.Idle) }
    var showTroubleshooting by remember { mutableStateOf(false) }
    var showNoDeviceConfirm by remember { mutableStateOf(false) }
    var showWiFiConnection by remember { mutableStateOf(false) }
    val isPresenting by connectionManager.pairingService.isPresenting.collectAsStateWithLifecycle()

    fun startPairing() {
        scope.launch {
            uiState = PairUiState.Pairing
            try {
                connectionManager.pairNewDevice()
                onPaired()
            } catch (error: CancellationException) {
                throw error
            } catch (error: DevicePairingError.Cancelled) {
                uiState = PairUiState.Idle
            } catch (error: DevicePairingError.AlreadyInProgress) {
                uiState = PairUiState.Idle
            } catch (error: PairingError) {
                uiState = PairUiState.Error(error.toUiText(UiText.Plain(context.getString(R.string.pair_connect_failed))).resolve(context))
            } catch (error: Exception) {
                uiState = PairUiState.Error(error.toUiText(UiText.Plain(context.getString(R.string.pair_failed))).resolve(context))
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_bluetooth),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.pair_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(32.dp))

            if (uiState is PairUiState.Error) {
                Text(
                    (uiState as PairUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = ::startPairing,
                enabled = uiState !is PairUiState.Pairing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (uiState is PairUiState.Pairing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text(stringResource(R.string.pair_add_device))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = { showWiFiConnection = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(painterResource(R.drawable.ic_wifi), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.pair_connect_wifi))
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = { showTroubleshooting = true }) { Text(stringResource(R.string.pair_not_appearing)) }
            TextButton(onClick = { showNoDeviceConfirm = true }) { Text(stringResource(R.string.pair_no_device)) }
        }
    }

    if (showWiFiConnection) {
        ConnectViaWiFiDialog(
            connectionManager = connectionManager,
            onDismiss = { showWiFiConnection = false },
            onConnected = {
                showWiFiConnection = false
                onPaired()
            },
        )
    }

    if (isPresenting) {
        DeviceScanSheet(
            connectionManager = connectionManager,
            onDismiss = { connectionManager.pairingService.cancel() },
            onSelect = { device -> connectionManager.pairingService.select(device.id) },
        )
    }

    if (showTroubleshooting) {
        TroubleshootingDialog(
            connectionManager = connectionManager,
            onDismiss = { showTroubleshooting = false },
        )
    }

    if (showNoDeviceConfirm) {
        AlertDialog(
            onDismissRequest = { showNoDeviceConfirm = false },
            title = { Text(stringResource(R.string.pair_continue_without_title)) },
            text = { Text(stringResource(R.string.pair_continue_without_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showNoDeviceConfirm = false
                    appViewModel.onboardingState.completeOnboarding()
                }) { Text(stringResource(R.string.common_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showNoDeviceConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private sealed class PairUiState {
    object Idle : PairUiState()
    object Pairing : PairUiState()
    data class Error(val message: String) : PairUiState()
}

/** Mirrors `TroubleshootingSheet.swift`. */
@Composable
private fun TroubleshootingDialog(connectionManager: ConnectionManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pair_not_appearing)) },
        text = {
            Text(
                stringResource(R.string.pair_troubleshoot_body),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { connectionManager.clearStalePairings() }
                onDismiss()
            }) { Text(stringResource(R.string.pair_clear_stale)) }
        },
        dismissButton = {
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                onDismiss()
            }) { Text(stringResource(R.string.pair_bluetooth_settings)) }
        },
    )
}
