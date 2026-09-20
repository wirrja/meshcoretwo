// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.tracePathService
import com.meshcoretwo.services.persistence.TracePathDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI state for [SavedPathsScreen]. Ported from `SavedPathsViewModel.swift`'s `@Observable` property set. */
data class SavedPathsUiState(
    val savedPaths: List<TracePathDto> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: UiText? = null,
)

/**
 * Loads/renames/deletes saved trace paths for [SavedPathsScreen]. Ported from
 * `SavedPathsViewModel.swift`; Swift's `configure(dataStore:connectedDevice:)` provider pair
 * collapses into a single [connectionManager] constructor dependency, the same shape
 * [TracePathViewModel] already uses for its own [ConnectionManager] dependency.
 */
class SavedPathsViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _uiState = MutableStateFlow(SavedPathsUiState())
    val uiState: StateFlow<SavedPathsUiState> = _uiState.asStateFlow()

    /** Loads every saved path for the connected device. Ported from `loadSavedPaths`. */
    suspend fun loadSavedPaths() {
        val radioID = connectionManager.connectedDeviceRecord?.radioID ?: return
        val service = connectionManager.tracePathService ?: return

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val paths = service.fetchSavedTracePaths(radioID)
            _uiState.update { it.copy(savedPaths = paths, isLoading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, errorMessage = UiText.of(R.string.saved_err_load)) }
        }
    }

    /** Renames a saved path and reloads the list. Ported from `renamePath(_:to:)`. */
    suspend fun renamePath(path: TracePathDto, newName: String) {
        val service = connectionManager.tracePathService ?: return
        try {
            service.updateSavedTracePathName(path.id, newName)
            loadSavedPaths()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = UiText.of(R.string.saved_err_rename)) }
        }
    }

    /** Deletes a saved path, removing it from local state on success. Ported from `deletePath`. */
    suspend fun deletePath(path: TracePathDto): Boolean {
        val service = connectionManager.tracePathService ?: return false
        return try {
            service.deleteSavedTracePath(path.id)
            _uiState.update { it.copy(savedPaths = it.savedPaths.filterNot { p -> p.id == path.id }) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = UiText.of(R.string.saved_err_delete)) }
            false
        }
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SavedPathsViewModel(connectionManager) as T
    }
}
