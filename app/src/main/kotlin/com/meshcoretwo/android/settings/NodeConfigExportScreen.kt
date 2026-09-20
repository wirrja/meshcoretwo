// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.services.connection.ConnectionManager
import kotlinx.coroutines.launch

/**
 * Export screen — ported from `NodeConfigExportView.swift`. Section toggles pick which parts of
 * [com.meshcoretwo.services.nodeconfig.ConfigSections] to include; `Export` runs [NodeConfigExportViewModel.export] and, once a
 * payload is ready, this screen launches Android's document-creation picker
 * (`ActivityResultContracts.CreateDocument`, the counterpart to SwiftUI's `.fileExporter`) and
 * writes the JSON there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeConfigExportScreen(connectionManager: ConnectionManager, onBack: () -> Unit) {
    val viewModel: NodeConfigExportViewModel = viewModel(factory = NodeConfigExportViewModel.Factory(connectionManager))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = uiState.pendingExport
        if (uri != null && pending != null) {
            coroutineScope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(pending.json.toByteArray()) }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.cfg_err_save_file))
                }
            }
        }
        viewModel.clearPendingExport()
    }

    LaunchedEffect(uiState.pendingExport) {
        uiState.pendingExport?.let { createDocumentLauncher.launch(it.filename) }
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message.resolve(context))
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.cfg_export_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val sections = uiState.sections

            Column {
                SettingsListRow(
                    title = stringResource(R.string.cfg_select_all),
                    trailing = {
                        Switch(
                            checked = sections.allSelected,
                            onCheckedChange = { checked -> viewModel.updateSections { if (checked) selectAll() else deselectAll() } },
                        )
                    },
                )
                HorizontalDivider()
                ExportToggleRow(
                    stringResource(R.string.cfg_node_identity),
                    stringResource(R.string.cfg_node_identity_desc),
                    sections.nodeIdentity,
                ) { checked -> viewModel.updateSections { nodeIdentity = checked } }
                ExportToggleRow(
                    stringResource(R.string.cfg_radio_settings),
                    stringResource(R.string.cfg_radio_settings_desc),
                    sections.radioSettings,
                ) { checked -> viewModel.updateSections { radioSettings = checked } }
                ExportToggleRow(
                    stringResource(R.string.cfg_position),
                    stringResource(R.string.cfg_position_desc),
                    sections.positionSettings,
                ) { checked -> viewModel.updateSections { positionSettings = checked } }
                ExportToggleRow(
                    stringResource(R.string.cfg_other_settings),
                    stringResource(R.string.cfg_other_settings_desc),
                    sections.otherSettings,
                ) { checked -> viewModel.updateSections { otherSettings = checked } }
                ExportToggleRow(
                    stringResource(R.string.backup_kind_channels),
                    stringResource(R.string.cfg_channels_desc),
                    sections.channels,
                ) { checked -> viewModel.updateSections { channels = checked } }
                ExportToggleRow(
                    stringResource(R.string.common_contacts),
                    stringResource(R.string.cfg_contacts_desc),
                    sections.contacts,
                    showDivider = false,
                ) { checked -> viewModel.updateSections { contacts = checked } }
            }

            Button(
                onClick = viewModel::export,
                enabled = !uiState.isExporting && sections.anySectionSelected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (sections.allSelected) R.string.cfg_export_full else R.string.cfg_export_selected))
                if (uiState.isExporting) {
                    Spacer(modifier = Modifier.size(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ExportToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    showDivider: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsListRow(
        title = title,
        value = description,
        singleLineValue = false,
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
    if (showDivider) HorizontalDivider()
}
