// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.nodeConfigService
import com.meshcoretwo.services.nodeconfig.ConfigSections
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NodeConfigExportUiState(
    val sections: ConfigSections = ConfigSections(),
    val isExporting: Boolean = false,
    val errorMessage: UiText? = null,
    val pendingExport: PendingExport? = null,
) {
    data class PendingExport(val json: String, val filename: String)
}

/**
 * Backs the config export screen ([NodeConfigExportScreen]) — ported from
 * `NodeConfigExportViewModel.swift`. Writing the JSON payload to disk
 * (`ActivityResultContracts.CreateDocument` over [android.content.ContentResolver], Android's
 * counterpart to SwiftUI's `.fileExporter`) stays in the Compose screen, which owns the resulting
 * [android.net.Uri]; this ViewModel only builds the payload and a suggested filename
 * ([NodeConfigExportUiState.PendingExport]) once [export] succeeds, mirroring the
 * `NodeConfigDocument`/`showFileExporter` split in the Swift source.
 */
class NodeConfigExportViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _uiState = MutableStateFlow(NodeConfigExportUiState())
    val uiState: StateFlow<NodeConfigExportUiState> = _uiState.asStateFlow()

    fun updateSections(transform: ConfigSections.() -> Unit) {
        _uiState.update { it.copy(sections = it.sections.copy().apply(transform)) }
    }

    /** A disconnected device (`nodeConfigService == null`) is a no-op, mirroring Swift's optional-service guard. */
    fun export() {
        val service = connectionManager.nodeConfigService ?: return
        val sections = _uiState.value.sections
        val deviceNodeName = connectionManager.connectedDeviceRecord?.nodeName

        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, errorMessage = null) }
            try {
                val config = service.exportConfig(sections)
                val json = config.toJson().toString(2)

                val name = deviceNodeName ?: config.name ?: "unknown"
                val sanitized = name.replace(Regex("[^a-zA-Z0-9_-]"), "_").trim('_')
                val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
                val filename = "${sanitized}_meshcore_config_$timestamp.json"

                _uiState.update {
                    it.copy(isExporting = false, pendingExport = NodeConfigExportUiState.PendingExport(json, filename))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isExporting = false, errorMessage = e.toUiText(UiText.of(R.string.cfg_err_export))) }
            }
        }
    }

    fun clearPendingExport() {
        _uiState.update { it.copy(pendingExport = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NodeConfigExportViewModel(connectionManager) as T
    }
}
