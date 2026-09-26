// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.AppViewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.settings.ManualRadioSettingsDialog
import com.meshcoretwo.android.settings.withManualRadioSettings
import com.meshcoretwo.android.settings.writeManualRadioSettings
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.settingsService
import com.meshcoretwo.services.connection.updateDevice
import com.meshcoretwo.services.persistence.DeviceDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Manual settings" on the Region step: opens the same radio dialog Settings uses (frequency,
 * bandwidth, SF, CR, TX power, path hash) and, once the radio accepts the values, finishes
 * onboarding via [onApplied] — the preset list is skipped, since the user just chose their own
 * parameters. No iOS counterpart: `RegionStepView.swift`/`PresetStepView.swift` only offer presets.
 */
@Composable
internal fun ManualRadioSettingsButton(appViewModel: AppViewModel, onApplied: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialogDevice by remember { mutableStateOf<DeviceDto?>(null) }
    var isApplying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                errorMessage = null
                val device = appViewModel.connectionManager.connectedDeviceRecord
                if (device == null) errorMessage = context.getString(R.string.preset_not_connected) else dialogDevice = device
            },
            enabled = !isApplying,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (isApplying) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text(stringResource(R.string.settings_manual_settings))
        }
        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }

    dialogDevice?.let { device ->
        ManualRadioSettingsDialog(
            device = device,
            onDismiss = { dialogDevice = null },
            onApply = { settings ->
                dialogDevice = null
                val settingsService = appViewModel.connectionManager.settingsService
                if (settingsService == null) {
                    errorMessage = context.getString(R.string.preset_not_connected)
                    return@ManualRadioSettingsDialog
                }
                isApplying = true
                scope.launch {
                    try {
                        settingsService.writeManualRadioSettings(settings, device)
                        val current = appViewModel.connectionManager.connectedDeviceRecord ?: device
                        appViewModel.connectionManager.updateDevice(current.withManualRadioSettings(settings))
                        onApplied()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        errorMessage = error.toUiText(UiText.Plain(context.getString(R.string.preset_apply_failed))).resolve(context)
                    } finally {
                        isApplying = false
                    }
                }
            },
        )
    }
}
