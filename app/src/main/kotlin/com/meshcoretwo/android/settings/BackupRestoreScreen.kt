// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.BuildConfig
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.DatePatterns
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.services.backup.AppBackupEnvelope
import com.meshcoretwo.services.backup.AppBackupService
import com.meshcoretwo.services.backup.BackupModelKind
import com.meshcoretwo.services.backup.ImportResult
import com.meshcoretwo.services.connection.ConnectionManager
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup & Restore screen — ported from `BackupRestoreView.swift`/`ImportPreviewSheet.swift`/
 * `ImportSuccessContent.swift`/`ExportSuccessContent.swift`, collapsed into one file: this port's
 * export success is a snackbar (matching [NodeConfigExportScreen]'s existing convention) rather
 * than a dedicated summary sheet, and the import success view drops Swift's expandable
 * "already here"/"couldn't be restored" disclosure groups in favor of always-visible count rows —
 * see [BackupRestoreViewModel]'s doc for why mid-import cancellation is dropped too.
 *
 * File picking uses `ActivityResultContracts.CreateDocument`/`OpenDocument`, Android's counterpart
 * to SwiftUI's `.fileExporter`/`.fileImporter` (see [NodeConfigExportScreen]/[NodeConfigImportScreen]
 * for the same pattern applied to device-config files) — a generic `application/octet-stream` MIME
 * type is used on both sides since `.mc1backup` has no registered Android MIME type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(connectionManager: ConnectionManager, appBackupService: AppBackupService, onBack: () -> Unit) {
    val viewModel: BackupRestoreViewModel = viewModel(factory = BackupRestoreViewModel.Factory(appBackupService))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connectionState by connectionManager.connectionStateEvents.collectAsStateWithLifecycle()
    val isRadioConnected = connectionState.isConnected
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showExportConfirmation by remember { mutableStateOf(false) }

    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = uiState.pendingExport
        when {
            uri == null -> viewModel.clearPendingExport()
            pending != null -> coroutineScope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(pending.bytes) }
                    viewModel.onExportSaved(defaultExportFilename())
                    snackbarHostState.showSnackbar(context.getString(R.string.backup_saved))
                } catch (e: Exception) {
                    viewModel.clearPendingExport()
                    snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.backup_err_save))
                }
            }
        }
    }

    LaunchedEffect(uiState.pendingExport) {
        uiState.pendingExport?.let { createDocumentLauncher.launch(defaultExportFilename()) }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null) viewModel.parseFile(bytes) else snackbarHostState.showSnackbar(context.getString(R.string.backup_err_read_file))
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message.resolve(context))
            viewModel.clearError()
        }
    }

    if (showExportConfirmation) {
        AlertDialog(
            onDismissRequest = { showExportConfirmation = false },
            title = { Text(stringResource(R.string.backup_security_title)) },
            text = { Text(stringResource(R.string.backup_security_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showExportConfirmation = false
                    viewModel.performExport(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString())
                }) { Text(stringResource(R.string.common_export)) }
            },
            dismissButton = { TextButton(onClick = { showExportConfirmation = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.settings_backup_restore)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (uiState.isImportFlowActive) {
                ImportFlowContent(uiState = uiState, viewModel = viewModel)
            } else {
                BackupRows(
                    uiState = uiState,
                    isRadioConnected = isRadioConnected,
                    onExportClick = { showExportConfirmation = true },
                    onImportClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
                )
            }
        }
    }
}

@Composable
private fun BackupRows(
    uiState: BackupRestoreUiState,
    isRadioConnected: Boolean,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
) {
    val isBusy = uiState.isExporting || uiState.isParsing
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackupRow(
            title = stringResource(R.string.backup_export_title),
            subtitle = stringResource(R.string.backup_export_sub),
            isBusy = uiState.isExporting,
            busyLabel = stringResource(R.string.backup_preparing),
            enabled = !isBusy,
            onClick = onExportClick,
        )
        BackupRow(
            title = stringResource(R.string.backup_import_title),
            subtitle = stringResource(R.string.backup_import_sub),
            isBusy = uiState.isParsing,
            busyLabel = stringResource(R.string.backup_reading),
            enabled = !isBusy && !isRadioConnected,
            onClick = onImportClick,
        )
        if (isRadioConnected) {
            Text(
                stringResource(R.string.backup_disconnect_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.backup_overview),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackupRow(title: String, subtitle: String, isBusy: Boolean, busyLabel: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (isBusy) busyLabel else title)
                if (isBusy) {
                    Spacer(modifier = Modifier.size(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                }
            }
            if (!isBusy) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ImportFlowContent(uiState: BackupRestoreUiState, viewModel: BackupRestoreViewModel) {
    when {
        uiState.isImporting -> ImportingContent()
        uiState.importResult != null -> ImportResultContent(result = uiState.importResult, onDone = viewModel::dismissImportFlow)
        uiState.importError != null -> ImportErrorContent(message = uiState.importError, onDismiss = viewModel::dismissImportFlow)
        uiState.previewEnvelope != null -> ImportPreviewContent(
            envelope = uiState.previewEnvelope,
            onImport = viewModel::performImport,
            onCancel = viewModel::dismissImportFlow,
        )
    }
}

@Composable
private fun ImportingContent() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.backup_importing), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImportPreviewContent(envelope: AppBackupEnvelope, onImport: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.backup_details), style = MaterialTheme.typography.titleMedium)
        LabeledRow(stringResource(R.string.backup_export_date), formatExportDate(envelope.exportDate))
        LabeledRow(stringResource(R.string.backup_app_version), "${envelope.appVersion} (${envelope.appBuild})")

        Spacer(modifier = Modifier.size(4.dp))
        Text(stringResource(R.string.backup_contents), style = MaterialTheme.typography.titleMedium)
        for (kind in BackupModelKind.entries) {
            val count = envelope.manifest.count(kind)
            if (count > 0) LabeledRow(stringResource(kind.labelRes), "$count")
        }

        Spacer(modifier = Modifier.size(4.dp))
        Text(
            stringResource(R.string.backup_merge_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.size(8.dp))
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_import_data)) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
    }
}

@Composable
private fun ImportResultContent(result: ImportResult, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (result.hasRestoredChanges) stringResource(R.string.backup_import_complete) else stringResource(R.string.backup_all_here),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            when {
                result.totalInserted > 0 -> stringResource(R.string.backup_items_added, result.totalInserted)
                result.totalMerged > 0 -> stringResource(R.string.backup_items_refreshed, result.totalMerged)
                else -> stringResource(R.string.backup_already_on_device)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (result.totalInserted > 0) {
            Spacer(modifier = Modifier.size(4.dp))
            Text(stringResource(R.string.backup_added_to_device), style = MaterialTheme.typography.titleSmall)
            for (kind in BackupModelKind.entries) {
                val inserted = result.counts(kind).inserted
                if (inserted > 0) LabeledRow(stringResource(kind.labelRes), "$inserted")
            }
        }
        if (result.totalSkipped > 0) {
            Spacer(modifier = Modifier.size(4.dp))
            Text(stringResource(R.string.backup_already_on), style = MaterialTheme.typography.titleSmall)
            for (kind in BackupModelKind.entries) {
                val skipped = result.counts(kind).skipped
                if (skipped > 0) LabeledRow(stringResource(kind.labelRes), "$skipped")
            }
            Text(
                stringResource(R.string.backup_kept_as_is),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.totalDropped > 0) {
            Spacer(modifier = Modifier.size(4.dp))
            Text(stringResource(R.string.backup_cant_restore), style = MaterialTheme.typography.titleSmall)
            for (kind in BackupModelKind.entries) {
                val dropped = result.counts(kind).dropped
                if (dropped > 0) LabeledRow(stringResource(kind.labelRes), "$dropped")
            }
            Text(
                stringResource(R.string.backup_cant_restore_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.size(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_done)) }
    }
}

@Composable
private fun ImportErrorContent(message: UiText, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.backup_import_failed), style = MaterialTheme.typography.titleMedium)
        Text(message.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Button(onClick = onDismiss) { Text(stringResource(R.string.common_dismiss)) }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@get:StringRes
private val BackupModelKind.labelRes: Int
    get() = when (this) {
        BackupModelKind.MESSAGES -> R.string.backup_kind_messages
        BackupModelKind.CONTACTS -> R.string.common_contacts
        BackupModelKind.CHANNELS -> R.string.backup_kind_channels
        BackupModelKind.DEVICES -> R.string.backup_kind_devices
        BackupModelKind.ROOM_MESSAGES -> R.string.backup_kind_room_messages
        BackupModelKind.REACTIONS -> R.string.settings_notif_reactions
        BackupModelKind.MESSAGE_REPEATS -> R.string.backup_kind_repeats
        BackupModelKind.SAVED_TRACE_PATHS -> R.string.backup_kind_paths
        BackupModelKind.REMOTE_NODE_SESSIONS -> R.string.backup_kind_sessions
        BackupModelKind.BLOCKED_CHANNEL_SENDERS -> R.string.backup_kind_blocked
        BackupModelKind.NODE_STATUS_SNAPSHOTS -> R.string.backup_kind_snapshots
        BackupModelKind.DISCOVERED_NODES -> R.string.backup_kind_discovered
    }

private fun defaultExportFilename(): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
    return "MeshCoreTwo_Backup_$timestamp.mc1backup"
}

private fun formatExportDate(instant: java.time.Instant): String =
    DatePatterns.dateTimeMedium().format(instant)
