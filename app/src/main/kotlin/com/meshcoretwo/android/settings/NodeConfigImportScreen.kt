// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.nodeconfig.MeshCoreNodeConfig
import java.util.Locale

/**
 * Import screen — ported from `NodeConfigImportView.swift`. File picking uses Android's
 * `ActivityResultContracts.OpenDocument` (the counterpart to SwiftUI's `.fileImporter`); the rest
 * mirrors the Swift source's two-state layout (select-file vs. preview-and-apply) driven by
 * [NodeConfigImportUiState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeConfigImportScreen(connectionManager: ConnectionManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: NodeConfigImportViewModel =
        viewModel(factory = NodeConfigImportViewModel.Factory(connectionManager, context.contentResolver))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceName = connectionManager.connectedDeviceRecord?.nodeName ?: stringResource(R.string.cfg_this_device)

    DisposableEffect(Unit) {
        onDispose { viewModel.handleDismissal() }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.parseFile(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cfg_import_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        val config = uiState.importedConfig
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (config == null) {
                SelectFileList(
                    isParsing = uiState.isParsing,
                    errorMessage = uiState.errorMessage,
                    onSelectFile = { openDocumentLauncher.launch(arrayOf("application/json")) },
                )
            } else {
                ImportPreviewList(viewModel = viewModel, uiState = uiState, config = config)
            }
        }
    }

    if (uiState.showConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(uiState.confirmTitle.asString()) },
            text = { Text(uiState.confirmMessage(deviceName).asString()) },
            confirmButton = { TextButton(onClick = viewModel::applyConfig) { Text(uiState.applyButtonLabel.asString()) } },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirmation) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun SelectFileList(isParsing: Boolean, errorMessage: UiText?, onSelectFile: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSelectFile, enabled = !isParsing) { Text(stringResource(R.string.cfg_select_json)) }
            if (isParsing) {
                Spacer(modifier = Modifier.size(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
        }
        errorMessage?.let {
            Spacer(modifier = Modifier.size(8.dp))
            Text(it.asString(), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ImportPreviewList(viewModel: NodeConfigImportViewModel, uiState: NodeConfigImportUiState, config: MeshCoreNodeConfig) {
    val enabled = !uiState.isPreparingConfirmation

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (config.name != null || config.privateKey != null) {
            NodeIdentitySection(uiState, config, enabled) { checked -> viewModel.updateSections { nodeIdentity = checked } }
            HorizontalDivider()
        }
        config.radioSettings?.let { radio ->
            RadioSettingsSection(uiState, radio, enabled) { checked -> viewModel.updateSections { radioSettings = checked } }
            HorizontalDivider()
        }
        config.positionSettings?.let { position ->
            PositionSection(uiState, position, enabled) { checked -> viewModel.updateSections { positionSettings = checked } }
            HorizontalDivider()
        }
        if (config.otherSettings != null) {
            ToggleRow(stringResource(R.string.cfg_other_settings), uiState.sections.otherSettings, enabled) { checked -> viewModel.updateSections { otherSettings = checked } }
            HorizontalDivider()
        }
        config.channels?.let { channels ->
            ToggleRow(
                title = stringResource(R.string.backup_kind_channels),
                checked = uiState.sections.channels,
                enabled = enabled,
                subtitle = stringResource(R.string.cfg_will_add_channels, channels.size),
            ) { checked -> viewModel.updateSections { this.channels = checked } }
            HorizontalDivider()
        }
        config.contacts?.let { contacts ->
            ToggleRow(
                title = stringResource(R.string.common_contacts),
                checked = uiState.sections.contacts,
                enabled = enabled,
                subtitle = stringResource(R.string.cfg_will_add_contacts, contacts.size),
            ) { checked -> viewModel.updateSections { this.contacts = checked } }
            HorizontalDivider()
        }

        Text(
            stringResource(R.string.cfg_keep_close),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ApplySection(viewModel, uiState)

        uiState.errorMessage?.let {
            Text(it.asString(), color = MaterialTheme.colorScheme.error)
        }

        if (uiState.importComplete && !uiState.isApplying) {
            Text(stringResource(R.string.cfg_import_success), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ApplySection(viewModel: NodeConfigImportViewModel, uiState: NodeConfigImportUiState) {
    when {
        uiState.isApplying -> Column {
            LinearProgressIndicator(progress = { uiState.applyProgress }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.size(4.dp))
            Text(uiState.applyStepDescription.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.size(4.dp))
            TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.common_cancel)) }
        }
        !uiState.importComplete -> Button(
            onClick = viewModel::prepareConfirmation,
            enabled = !uiState.isPreparingConfirmation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(uiState.applyButtonLabel.asString())
            if (uiState.isPreparingConfirmation) {
                Spacer(modifier = Modifier.size(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
        }
        else -> Unit
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, enabled: Boolean, subtitle: String? = null, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.8f)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun NodeIdentitySection(
    uiState: NodeConfigImportUiState,
    config: MeshCoreNodeConfig,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cfg_node_identity))
            Switch(checked = uiState.sections.nodeIdentity, onCheckedChange = onToggle, enabled = enabled)
        }
        config.name?.let { DiffRow(current = uiState.currentName ?: "—", new = it) }
        if (config.privateKey != null) {
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                stringResource(R.string.cfg_replace_identity_warn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RadioSettingsSection(
    uiState: NodeConfigImportUiState,
    radio: MeshCoreNodeConfig.RadioSettings,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cfg_radio_settings))
            Switch(checked = uiState.sections.radioSettings, onCheckedChange = onToggle, enabled = enabled)
        }
        DiffRow(current = uiState.currentRadio?.let(::formatRadio) ?: "—", new = formatRadio(radio))
        if (uiState.currentRadio != null && uiState.currentRadio != radio) {
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                stringResource(R.string.cfg_radio_disconnect_warn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PositionSection(
    uiState: NodeConfigImportUiState,
    position: MeshCoreNodeConfig.PositionSettings,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cfg_position))
            Switch(checked = uiState.sections.positionSettings, onCheckedChange = onToggle, enabled = enabled)
        }
        val current = uiState.currentPosition?.let { "${it.latitude}, ${it.longitude}" } ?: "—"
        DiffRow(current = current, new = "${position.latitude}, ${position.longitude}")
    }
}

@Composable
private fun DiffRow(current: String, new: String) {
    val changed = current != new
    Column {
        Text(stringResource(R.string.cfg_current, current), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.cfg_new, new),
            style = MaterialTheme.typography.bodySmall,
            color = if (changed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatRadio(radio: MeshCoreNodeConfig.RadioSettings): String {
    val freqMHz = radio.frequency.toDouble() / 1000.0
    val bwKHz = radio.bandwidth.toDouble() / 1000.0
    return String.format(Locale.US, "%.3f MHz, BW %.1f kHz, SF %d, CR %d", freqMHz, bwKHz, radio.spreadingFactor.toInt(), radio.codingRate.toInt())
}
