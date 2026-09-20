// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.R
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.discoveredNodeStore
import com.meshcoretwo.services.contacts.ContactServiceError
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.toMeshContact
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DiscoveryUiState {
    data object Connecting : DiscoveryUiState()
    data class Ready(val nodes: List<DiscoveredNodeDto>, val addedPublicKeyHexes: Set<String>) : DiscoveryUiState()
}

/**
 * Backs the "Discover" list screen (`DiscoveryScreen.kt`) — the app-layer analogue of
 * `DiscoveryViewModel.swift`, using this port's established
 * [com.meshcoretwo.android.contacts.ContactsListViewModel] shape: imperative refetch on
 * [DeviceConnectionState.READY] plus an explicit [refresh] the screen calls on pull-to-refresh, no
 * reactive store-change stream (see that class's doc for why — same reasoning applies here).
 * Filtering/sorting itself lives in [DiscoveryFiltering], called directly by the screen so it
 * stays plain-JUnit testable; this class only owns loaded/error/in-flight state.
 */
class DiscoveryViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _uiState = MutableStateFlow<DiscoveryUiState>(DiscoveryUiState.Connecting)
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    /** The node currently being added as a contact, or null — drives the row's spinner/disabled state. */
    private val _addingNodeID = MutableStateFlow<UUID?>(null)
    val addingNodeID: StateFlow<UUID?> = _addingNodeID.asStateFlow()

    private val _errorMessage = MutableStateFlow<UiText?>(null)
    val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

    init {
        viewModelScope.launch {
            connectionManager.connectionStateEvents.collect { state ->
                if (state == DeviceConnectionState.READY) refresh() else _uiState.value = DiscoveryUiState.Connecting
            }
        }
    }

    /** Reloads discovered nodes and the set of already-added contact keys. Ported from `loadDiscoveredNodes()`. */
    fun refresh() {
        viewModelScope.launch {
            if (connectionManager.connectionState != DeviceConnectionState.READY) return@launch
            val radioID = connectionManager.lastConnectedRadioID ?: return@launch
            val store = connectionManager.discoveredNodeStore ?: return@launch

            try {
                val nodes = store.fetchDiscoveredNodes(radioID)
                val addedKeys = connectionManager.contactService?.getContacts(radioID)
                    ?.map { it.publicKey.hexString }
                    ?.toSet()
                    ?: emptySet()
                _uiState.value = DiscoveryUiState.Ready(nodes, addedKeys)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _errorMessage.value = UiText.of(R.string.discover_err_load)
            }
        }
    }

    /** Removes a single node, optimistically (matches Swift's immediate-removal-then-persist order). */
    fun deleteNode(node: DiscoveredNodeDto) {
        val current = _uiState.value as? DiscoveryUiState.Ready ?: return
        _uiState.value = current.copy(nodes = current.nodes.filterNot { it.id == node.id })
        viewModelScope.launch {
            try {
                connectionManager.discoveredNodeStore?.deleteDiscoveredNode(node.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _errorMessage.value = UiText.of(R.string.discover_err_delete)
            }
        }
    }

    fun clearAll() {
        val radioID = connectionManager.lastConnectedRadioID ?: return
        val store = connectionManager.discoveredNodeStore ?: return
        viewModelScope.launch {
            try {
                store.clearDiscoveredNodes(radioID)
                val current = _uiState.value as? DiscoveryUiState.Ready
                _uiState.value = DiscoveryUiState.Ready(emptyList(), current?.addedPublicKeyHexes ?: emptySet())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _errorMessage.value = UiText.of(R.string.discover_err_clear)
            }
        }
    }

    /** Adds a discovered node as a contact. Ported from `DiscoveryView.swift`'s `addNode`, including its `contactTableFull` handling. */
    fun addNode(node: DiscoveredNodeDto) {
        val radioID = connectionManager.lastConnectedRadioID ?: return
        val contactService = connectionManager.contactService ?: return
        _addingNodeID.value = node.id
        viewModelScope.launch {
            try {
                contactService.addOrUpdateContact(radioID, node.toMeshContact())
                refresh()
            } catch (error: ContactServiceError.ContactTableFull) {
                val maxContacts = connectionManager.connectedDeviceRecord?.maxContacts
                _errorMessage.value = if (maxContacts != null) {
                    UiText.of(R.string.contacts_err_list_full_max, maxContacts)
                } else {
                    UiText.of(R.string.contacts_err_list_full)
                }
            } catch (error: ContactServiceError) {
                _errorMessage.value = error.toUiText(UiText.of(R.string.settings_err_generic))
            } catch (error: CancellationException) {
                throw error
            } finally {
                _addingNodeID.value = null
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DiscoveryViewModel(connectionManager) as T
    }
}
