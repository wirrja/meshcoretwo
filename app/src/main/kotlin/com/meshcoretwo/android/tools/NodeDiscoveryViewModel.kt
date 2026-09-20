// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.NodeScanFilter
import com.meshcoretwo.services.connection.NodeScanResponse
import com.meshcoretwo.services.connection.addScannedNode
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.discoveredNodeStore
import com.meshcoretwo.services.connection.isContactNodeType
import com.meshcoretwo.services.connection.scanNodes
import com.meshcoretwo.services.contacts.ContactServiceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NodeDiscoverySortOrder(@StringRes val labelRes: Int) {
    SIGNAL(R.string.nd_sort_signal),
    NAME(R.string.sort_name),
}

/** One responder. [publicKeyHex] identifies it across rescans (a repeat response replaces the row). */
class NodeDiscoveryResult(
    val name: String,
    val publicKey: ByteArray,
    val nodeType: UByte,
    val snr: Double,
    val snrIn: Double,
    val rssi: Int,
    val filter: NodeScanFilter,
) {
    val publicKeyHex: String = publicKey.hexString
    val canAdd: Boolean get() = isContactNodeType(nodeType)
}

/**
 * Backs the Node Discovery tool (`NodeDiscoveryScreen.kt`). Ported from `NodeDiscoveryViewModel.swift`:
 * an active zero-hop scan for repeaters or sensors ([scanNodes]), results per filter kept until that
 * filter is rescanned, sortable by signal or name, with an "Add" that stores the node as a contact.
 *
 * Names come from contacts first, then previously heard Discover-list nodes; anything else shows
 * as `Unknown node (XXXXXXXX)`. Like every other view model here, thrown service errors are caught
 * and surfaced as [errorMessage] rather than crashing the process.
 */
class NodeDiscoveryViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _results = MutableStateFlow<List<NodeDiscoveryResult>>(emptyList())
    val results: StateFlow<List<NodeDiscoveryResult>> = _results.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _filter = MutableStateFlow(NodeScanFilter.REPEATERS)
    val filter: StateFlow<NodeScanFilter> = _filter.asStateFlow()

    private val _sortOrder = MutableStateFlow(NodeDiscoverySortOrder.SIGNAL)
    val sortOrder: StateFlow<NodeDiscoverySortOrder> = _sortOrder.asStateFlow()

    private val _errorMessage = MutableStateFlow<UiText?>(null)
    val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

    private val _isConnected = MutableStateFlow(connectionManager.connectionState == DeviceConnectionState.READY)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** Public-key hex of every scanned node already in the contact list. */
    private val _addedKeys = MutableStateFlow<Set<String>>(emptySet())
    val addedKeys: StateFlow<Set<String>> = _addedKeys.asStateFlow()

    private val _addingKey = MutableStateFlow<String?>(null)
    val addingKey: StateFlow<String?> = _addingKey.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            connectionManager.connectionStateEvents.collect { state ->
                val ready = state == DeviceConnectionState.READY
                _isConnected.value = ready
                if (!ready) stopScan()
            }
        }
    }

    fun setFilter(filter: NodeScanFilter) {
        if (_isScanning.value) return
        _filter.value = filter
    }

    fun setSortOrder(order: NodeDiscoverySortOrder) {
        _sortOrder.value = order
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun scan() {
        if (!_isConnected.value) return
        val scanFilter = _filter.value
        stopScan()
        _results.update { rows -> rows.filterNot { it.filter == scanFilter } }
        _errorMessage.value = null
        _isScanning.value = true
        scanJob = viewModelScope.launch {
            try {
                val names = loadNames()
                connectionManager.scanNodes(scanFilter).collect { response ->
                    upsert(response, scanFilter, names)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _errorMessage.value = error.toUiText(UiText.of(R.string.nd_err_scan))
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    fun addNode(result: NodeDiscoveryResult) {
        if (!result.canAdd || _addingKey.value != null) return
        _addingKey.value = result.publicKeyHex
        viewModelScope.launch {
            try {
                connectionManager.addScannedNode(result.publicKey, result.nodeType, result.name)
                _addedKeys.update { it + result.publicKeyHex }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ContactServiceError.ContactTableFull) {
                val max = connectionManager.connectedDeviceRecord?.maxContacts
                _errorMessage.value = if (max != null) UiText.of(R.string.nd_err_full_max, max) else UiText.of(R.string.nd_err_full)
            } catch (error: Exception) {
                _errorMessage.value = error.toUiText(UiText.of(R.string.nd_err_add))
            } finally {
                _addingKey.value = null
            }
        }
    }

    /** Contacts win over discovered-node names, as in Swift; also seeds [addedKeys]. */
    private suspend fun loadNames(): Map<String, String> {
        val radioID = connectionManager.lastConnectedRadioID ?: return emptyMap()
        val names = mutableMapOf<String, String>()
        try {
            connectionManager.discoveredNodeStore?.fetchDiscoveredNodes(radioID)?.forEach { names.putIfAbsent(it.publicKey.hexString, it.name) }
            val contacts = connectionManager.contactService?.getContacts(radioID).orEmpty()
            contacts.forEach { names[it.publicKey.hexString] = it.displayName }
            _addedKeys.value = contacts.mapTo(mutableSetOf()) { it.publicKey.hexString }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Names are cosmetic; fall back to "Unknown node".
        }
        return names
    }

    private fun upsert(response: NodeScanResponse, scanFilter: NodeScanFilter, names: Map<String, String>) {
        val key = response.publicKey.hexString
        val name = names[key] ?: "Unknown node (${key.take(8).uppercase()})"
        val result = NodeDiscoveryResult(name, response.publicKey, response.nodeType, response.snr, response.snrIn, response.rssi, scanFilter)
        _results.update { rows ->
            val existing = rows.indexOfFirst { it.publicKeyHex == key && it.filter == scanFilter }
            if (existing >= 0) rows.toMutableList().also { it[existing] = result } else rows + result
        }
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NodeDiscoveryViewModel(connectionManager) as T
    }
}
