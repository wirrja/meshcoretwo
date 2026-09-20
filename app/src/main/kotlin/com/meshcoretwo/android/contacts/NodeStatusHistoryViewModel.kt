// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.nodeSnapshotService
import com.meshcoretwo.services.connection.remoteNodeService
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.remotenode.OCVPreset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NodeStatusHistoryUiState(
    val session: RemoteNodeSessionDto? = null,
    val snapshots: List<NodeStatusSnapshotDto> = emptyList(),
    val isLoading: Boolean = true,
    val timeRange: HistoryTimeRange = HistoryTimeRange.DEFAULT,
    /** The node contact's battery curve, for the battery chart's Y-axis domain. */
    val ocvValues: List<Int> = OCVPreset.LI_ION.ocvArray,
)

/**
 * Backs the status-history drill-down (`NodeStatusHistoryScreen.kt`) — a port of
 * `NodeStatusHistoryView.swift`, the eleventh sub-slice of the RemoteNodes UI epic (PLAN.md) and
 * the first consumer of [com.meshcoretwo.services.nodesnapshot.NodeSnapshotService.fetchSnapshots],
 * which the snapshot slice ported with no caller.
 *
 * Where Swift's view takes a `fetchSnapshots` closure and an `ocvArray` from whichever status view
 * pushed it, this takes only the session id and loads both itself (the port navigates by route, so
 * a screen can be recreated without the pushing screen's state). The OCV lookup is the same
 * contact-by-session-public-key resolution [RoomStatusViewModel]/[RepeaterStatusViewModel] use for
 * their battery-curve sections.
 *
 * The whole snapshot history is fetched once and [HistoryTimeRange] filters it in memory, as in
 * Swift — switching range then costs nothing, and the store's own `since` parameter stays unused.
 * Snapshots are pruned to one year by `ServiceContainer.startEventMonitoring`, so the unbounded
 * fetch is bounded in practice.
 *
 * [TelemetryHistoryScreen] (`TelemetryHistoryView.swift`) reuses this view model as-is: Swift hands
 * that view the same `fetchSnapshots`/`ocvArray` pair, so the two drill-downs load identical state
 * and differ only in which charts they build from it. [NeighborSnrChartScreen] and
 * [LocationHistoryMapScreen]'s node-session route reuse it the same way, each with a fresh instance
 * (and so a fresh default [HistoryTimeRange]) rather than sharing the pushing screen's selection —
 * the same trim the neighbor chart already made.
 */
class NodeStatusHistoryViewModel(
    private val connectionManager: ConnectionManager,
    private val sessionId: UUID,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NodeStatusHistoryUiState())
    val uiState: StateFlow<NodeStatusHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = connectionManager.remoteNodeService?.fetchSession(sessionId)
            _uiState.update { it.copy(session = session) }
            if (session == null) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            loadOCVSettings(session)
            loadSnapshots(session)
        }
    }

    fun selectTimeRange(range: HistoryTimeRange) {
        _uiState.update { it.copy(timeRange = range) }
    }

    private suspend fun loadSnapshots(session: RemoteNodeSessionDto) {
        val snapshots = connectionManager.nodeSnapshotService?.fetchSnapshots(session.publicKey) ?: emptyList()
        _uiState.update { it.copy(snapshots = snapshots, isLoading = false) }
    }

    private suspend fun loadOCVSettings(session: RemoteNodeSessionDto) {
        try {
            val contact = connectionManager.contactService?.getContact(session.radioID, session.publicKey) ?: return
            _uiState.update { it.copy(ocvValues = contact.activeOCVArray) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The battery chart just falls back to the default curve for its Y-axis domain.
        }
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val sessionId: UUID,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NodeStatusHistoryViewModel(connectionManager, sessionId) as T
    }
}
