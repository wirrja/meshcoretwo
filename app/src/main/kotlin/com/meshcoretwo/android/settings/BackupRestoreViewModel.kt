// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.services.backup.AppBackupEnvelope
import com.meshcoretwo.services.backup.AppBackupError
import com.meshcoretwo.services.backup.AppBackupService
import com.meshcoretwo.services.backup.BackupManifest
import com.meshcoretwo.services.backup.BackupZlibCodec
import com.meshcoretwo.services.backup.ImportResult
import com.meshcoretwo.services.backup.toBackupJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupRestoreUiState(
    val isExporting: Boolean = false,
    val pendingExport: PendingExport? = null,
    val exportSummary: ExportSummary? = null,
    val isParsing: Boolean = false,
    val previewEnvelope: AppBackupEnvelope? = null,
    val isImporting: Boolean = false,
    val importResult: ImportResult? = null,
    val importError: UiText? = null,
    val errorMessage: UiText? = null,
) {
    data class PendingExport(val bytes: ByteArray, val manifest: BackupManifest)
    data class ExportSummary(val filename: String, val byteCount: Int, val manifest: BackupManifest)

    /** Whether the import preview/progress/result view should be showing instead of the plain export/import rows. */
    val isImportFlowActive: Boolean
        get() = previewEnvelope != null || isImporting || importResult != null || importError != null
}

/**
 * Backs the Backup & Restore screen ([BackupRestoreScreen]) — ported from `AppBackupViewModel.swift`,
 * trimmed to drop mid-import cancellation (Swift cancels the in-flight `Task` and relies on
 * `importBackupDatabase`'s `defer` rollback; this port's import runs inside one
 * [com.meshcoretwo.services.persistence.MeshCoreDatabase.withTransaction] call with no equivalent
 * cancel-and-rollback hook, and a phone-local SQLite import is fast enough that a cancel button
 * isn't worth the extra state this would otherwise need — see [BackupRestoreUiState] for what's
 * kept instead of Swift's five-case `ImportState`/`ExportState` enums).
 *
 * Writing the exported bytes to disk (`ActivityResultContracts.CreateDocument`, Android's
 * counterpart to SwiftUI's `.fileExporter`) and reading a picked file's bytes
 * (`ActivityResultContracts.OpenDocument`) both stay in the Compose screen, which owns the
 * resulting [android.net.Uri] — this ViewModel only ever sees byte arrays, matching
 * [NodeConfigExportViewModel]/[NodeConfigImportViewModel]'s split.
 */
class BackupRestoreViewModel(private val appBackupService: AppBackupService) : ViewModel() {
    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

    // MARK: - Export

    fun performExport(appVersion: String, appBuild: String) {
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val envelope = appBackupService.exportEnvelope(appVersion, appBuild)
                val bytes = BackupZlibCodec.compress(envelope.toBackupJson().toString().toByteArray(Charsets.UTF_8))
                _uiState.update { it.copy(isExporting = false, pendingExport = BackupRestoreUiState.PendingExport(bytes, envelope.manifest)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isExporting = false, errorMessage = e.userFacingMessage()) }
            }
        }
    }

    /** Called once the system save picker returns a destination and the bytes have been written there. */
    fun onExportSaved(filename: String) {
        val pending = _uiState.value.pendingExport ?: return
        _uiState.update {
            it.copy(pendingExport = null, exportSummary = BackupRestoreUiState.ExportSummary(filename, pending.bytes.size, pending.manifest))
        }
    }

    /** Called when the save picker is dismissed without a destination, or writing to it failed. */
    fun clearPendingExport() {
        _uiState.update { it.copy(pendingExport = null) }
    }

    fun dismissExportSuccess() {
        _uiState.update { it.copy(exportSummary = null) }
    }

    // MARK: - Import

    /** Decodes and validates a picked file's bytes without touching the database, so the user can review it before committing. */
    fun parseFile(bytes: ByteArray) {
        _uiState.update { it.copy(isParsing = true, errorMessage = null) }

        viewModelScope.launch {
            val envelope = try {
                appBackupService.parseBackupFile(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isParsing = false, errorMessage = e.userFacingMessage()) }
                return@launch
            }
            _uiState.update { it.copy(isParsing = false, previewEnvelope = envelope) }
        }
    }

    fun performImport() {
        val envelope = _uiState.value.previewEnvelope ?: return
        _uiState.update { it.copy(previewEnvelope = null, isImporting = true) }

        viewModelScope.launch {
            try {
                val result = appBackupService.importEnvelope(envelope)
                _uiState.update { it.copy(isImporting = false, importResult = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isImporting = false, importError = e.userFacingMessage()) }
            }
        }
    }

    fun dismissImportFlow() {
        _uiState.update { it.copy(previewEnvelope = null, isImporting = false, importResult = null, importError = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun Exception.userFacingMessage(): UiText =
        toUiText(UiText.of(R.string.settings_err_generic))

    class Factory(private val appBackupService: AppBackupService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupRestoreViewModel(appBackupService) as T
    }
}
