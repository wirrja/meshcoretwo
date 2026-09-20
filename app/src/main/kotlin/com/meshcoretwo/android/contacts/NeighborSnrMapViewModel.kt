// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.map.MapFilterState
import com.meshcoretwo.protocol.Neighbour
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.discoveredNodeStore
import com.meshcoretwo.services.connection.remoteNodeService
import com.meshcoretwo.services.connection.repeaterAdminService
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.remotenode.RemoteNodeRetry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NeighborSnrMapUiState(
    val session: RemoteNodeSessionDto? = null,
    val isLoading: Boolean = false,
    val errorMessage: UiText? = null,
    val filter: MapFilterState = MapFilterState(),
    val plotted: PlottedNeighbors? = null,
)

/**
 * Backs the neighbor SNR map (`NeighborSnrMapScreen.kt`) pushed from
 * [RepeaterStatusScreen]'s "View on Map" link — a trimmed port of `NeighborSNRMapView.swift`.
 * Unlike the Swift screen, which receives the already-fetched neighbours/contacts/discovered-nodes
 * from its host in scope, this screen owns its own fetch keyed by [sessionId] (same
 * fetch-by-nav-arg shape as every other pushed screen in this port, e.g. [RepeaterStatusViewModel]):
 * [com.meshcoretwo.services.remotenode.RepeaterAdminService.fetchAllNeighbors] is safe to reissue
 * since it's the same request [RepeaterStatusViewModel] already made to populate the section this
 * screen was pushed from.
 *
 * Deliberately trimmed relative to Swift's `NeighborSNRMapView`: no `AppStorage`-persisted filter
 * (resets on screen entry, matching [com.meshcoretwo.android.map.MapViewModel]'s documented gap),
 * no offline-map-pack awareness, no north-lock/label-toggle/style-selection persistence — this
 * port's [com.meshcoretwo.android.map.MapScreen] doesn't have those either. [MapFilterState]'s
 * `showChat`/`showRepeater`/`showRoom` type axis is unused here (Swift's `neighborSNR` filter host
 * excludes it too — only `favoritesOnly`/`showDiscovered` govern which contacts/discovered nodes
 * feed [NeighborSnrMapBuilder]) — no `MapFilterHost`/capabilities abstraction was ported for that
 * single fact, see [MapFilterState]'s class doc for the same trim on the main map.
 */
class NeighborSnrMapViewModel(
    private val connectionManager: ConnectionManager,
    private val sessionId: UUID,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NeighborSnrMapUiState())
    val uiState: StateFlow<NeighborSnrMapUiState> = _uiState.asStateFlow()

    private var neighbors: List<Neighbour> = emptyList()
    private var contacts: List<ContactDto> = emptyList()
    private var discoveredNodes: List<DiscoveredNodeDto> = emptyList()

    /** Opt-in only, same pattern as [RepeaterStatusViewModel.setCurrentLocation]. */
    private var currentLocation: LocationFix? = null

    init {
        viewModelScope.launch { load() }
    }

    fun setCurrentLocation(fix: LocationFix?) {
        currentLocation = fix
        rebuildPlotted()
    }

    fun setFavoritesOnly(value: Boolean) = updateFilter { withFavoritesOnly(value) }
    fun setShowDiscovered(value: Boolean) = updateFilter { withShowDiscovered(value) }

    private inline fun updateFilter(mutate: MapFilterState.() -> MapFilterState) {
        _uiState.update { it.copy(filter = it.filter.mutate()) }
        rebuildPlotted()
    }

    private suspend fun load() {
        val session = connectionManager.remoteNodeService?.fetchSession(sessionId) ?: return
        _uiState.update { it.copy(session = session, isLoading = true, errorMessage = null) }

        contacts = connectionManager.contactService?.getContacts(session.radioID).orEmpty()
        discoveredNodes = connectionManager.discoveredNodeStore?.fetchDiscoveredNodes(session.radioID).orEmpty()

        val repeaterAdminService = connectionManager.repeaterAdminService
        if (repeaterAdminService == null) {
            _uiState.update { it.copy(isLoading = false, errorMessage = UiText.of(R.string.add_channel_err_not_connected)) }
            return
        }
        try {
            val response =
                RemoteNodeRetry.performWithTransientRetries { timeoutMs -> repeaterAdminService.fetchAllNeighbors(session.id, timeoutMs = timeoutMs) }
            neighbors = response.neighbours
            _uiState.update { it.copy(isLoading = false) }
            rebuildPlotted()
        } catch (error: CancellationException) {
            _uiState.update { it.copy(isLoading = false) }
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(isLoading = false, errorMessage = error.toUiText(UiText.of(R.string.neighbors_err_failed))) }
        }
    }

    private fun rebuildPlotted() {
        val session = _uiState.value.session ?: return
        val plotted = NeighborSnrMapBuilder.build(
            session = session,
            neighbors = neighbors,
            contacts = contacts,
            discoveredNodes = discoveredNodes,
            userLocation = currentLocation,
            filter = _uiState.value.filter,
            keyDisplayByteCount = NEIGHBOR_KEY_DISPLAY_BYTES,
        )
        _uiState.update { it.copy(plotted = plotted) }
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val sessionId: UUID,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NeighborSnrMapViewModel(connectionManager, sessionId) as T
    }

    private companion object {
        /** Same fixed width as [RepeaterStatusViewModel]'s neighbor key display — see that class's doc. */
        const val NEIGHBOR_KEY_DISPLAY_BYTES = 2
    }
}
