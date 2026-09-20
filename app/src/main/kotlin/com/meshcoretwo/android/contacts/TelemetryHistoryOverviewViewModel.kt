// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.pathediting.NeighborNameResolver
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.discoveredNodeStore
import com.meshcoretwo.services.connection.nodeSnapshotService
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.remotenode.OCVPreset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TelemetryHistoryOverviewUiState(
    val contact: ContactDto? = null,
    val isLoading: Boolean = true,
    val snapshots: List<NodeStatusSnapshotDto> = emptyList(),
    /** The contact's battery curve, for the voltage charts' Y-axis domain. */
    val ocvValues: List<Int> = OCVPreset.LI_ION.ocvArray,
    /** Name-resolution sources for the neighbor charts. */
    val contacts: List<ContactDto> = emptyList(),
    val discoveredNodes: List<DiscoveredNodeDto> = emptyList(),
    val timeRange: HistoryTimeRange = HistoryTimeRange.DEFAULT,
    val radioExpanded: Boolean = true,
    val sensorsExpanded: Boolean = false,
    val neighborsExpanded: Boolean = false,
) {
    val hasSnapshots: Boolean get() = snapshots.isNotEmpty()

    /**
     * Swift's `showNeighbors` route flag: `ContactDetailView` passes `false` for chat contacts and
     * leaves the `true` default for repeaters and rooms.
     */
    val showNeighbors: Boolean get() = contact?.type != ContactType.CHAT
}

/**
 * Backs the per-contact telemetry history overview (`TelemetryHistoryOverviewScreen.kt`) — a port of
 * `TelemetryHistoryOverviewViewModel.swift`, the twelfth sub-slice of the RemoteNodes UI epic
 * (PLAN.md). Unlike [NodeStatusHistoryViewModel], it needs no remote-node session: it is keyed by
 * contact, so it covers nodes the user never logged into on this install as long as snapshots exist.
 *
 * Swift opens the overview offline, reading `appState.offlineDataStore` directly. Every store in the
 * port sits behind the active connection's service graph (`connectionManager.contactService` and
 * friends are null while disconnected), the same limit [ContactDetailScreen] — the only entry point —
 * already has; disconnected, the overview shows its empty state.
 *
 * As in Swift the loads are best-effort and independent: a failed snapshot fetch shows the empty
 * state, a failed contact lookup keeps the default battery curve, and failed name-resolution loads
 * leave neighbor charts titled by their hex prefix.
 */
class TelemetryHistoryOverviewViewModel(
    private val connectionManager: ConnectionManager,
    private val contactId: UUID,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TelemetryHistoryOverviewUiState())
    val uiState: StateFlow<TelemetryHistoryOverviewUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { loadData() }
    }

    fun selectTimeRange(range: HistoryTimeRange) {
        _uiState.update { it.copy(timeRange = range) }
    }

    fun toggleRadioExpanded() {
        _uiState.update { it.copy(radioExpanded = !it.radioExpanded) }
    }

    fun toggleSensorsExpanded() {
        _uiState.update { it.copy(sensorsExpanded = !it.sensorsExpanded) }
    }

    fun toggleNeighborsExpanded() {
        _uiState.update { it.copy(neighborsExpanded = !it.neighborsExpanded) }
    }

    /** A neighbor's display name from its public-key prefix, or null when nothing matches. */
    fun resolveNeighborName(prefix: ByteArray): String? =
        NeighborNameResolver.resolveName(prefix, _uiState.value.contacts, _uiState.value.discoveredNodes, userLocation = null)

    private suspend fun loadData() {
        val contact = bestEffort { connectionManager.contactService?.getContactById(contactId) }
        if (contact == null) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        // NodeSnapshotService already swallows persistence errors into an empty list.
        val snapshots = connectionManager.nodeSnapshotService?.fetchSnapshots(contact.publicKey) ?: emptyList()
        val contacts = bestEffort { connectionManager.contactService?.getContacts(contact.radioID) } ?: emptyList()
        val discoveredNodes = bestEffort { connectionManager.discoveredNodeStore?.fetchDiscoveredNodes(contact.radioID) } ?: emptyList()

        _uiState.update {
            val loaded = it.copy(
                contact = contact,
                snapshots = snapshots,
                ocvValues = contact.activeOCVArray,
                contacts = contacts,
                discoveredNodes = discoveredNodes,
                isLoading = false,
            )
            // Swift starts the sensors section expanded only when there's no neighbors section below it.
            loaded.copy(sensorsExpanded = !loaded.showNeighbors)
        }
    }

    private suspend fun <T> bestEffort(block: suspend () -> T?): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    companion object {
        /**
         * Whether any snapshot carries a radio metric, gating the Radio section. Ported from
         * `hasRadioData(in:)` — including the plain sent/received totals no chart plots, as in Swift.
         */
        fun hasRadioData(snapshots: List<NodeStatusSnapshotDto>): Boolean = snapshots.any {
            it.batteryMillivolts != null || it.lastSNR != null || it.lastRSSI != null || it.noiseFloor != null ||
                it.packetsSent != null || it.packetsReceived != null || it.receiveErrors != null ||
                it.sentDirect != null || it.sentFlood != null || it.receivedDirect != null || it.receivedFlood != null ||
                it.directDuplicates != null || it.floodDuplicates != null ||
                it.postedCount != null || it.postPushCount != null
        }

        /** Ported from `hasTelemetryData(in:)`. */
        fun hasTelemetryData(snapshots: List<NodeStatusSnapshotDto>): Boolean =
            snapshots.any { !it.telemetryEntries.isNullOrEmpty() }

        /** Ported from `hasNeighborData(in:)`. */
        fun hasNeighborData(snapshots: List<NodeStatusSnapshotDto>): Boolean =
            snapshots.any { !it.neighborSnapshots.isNullOrEmpty() }
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val contactId: UUID,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TelemetryHistoryOverviewViewModel(connectionManager, contactId) as T
    }
}
