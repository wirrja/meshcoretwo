// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.discoveredNodeStore
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.location.LocationProviderError
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Which store a [MapPoint] was built from — governs which sheet/actions a tap on it offers. */
enum class MapPointKind { CONTACT, DISCOVERED }

/**
 * One mappable node — either a [ContactDto] or a [DiscoveredNodeDto] with a stored location,
 * reduced to what a map marker/callout need. For [MapPointKind.DISCOVERED] points, [pointId] is
 * the discovered-node row id (not a contact id): [isFavorite]/[isBlocked] don't apply (always
 * false) and [canMessage] is always false, matching that a discovered node is not yet a contact.
 */
data class MapPoint(
    val pointId: UUID,
    val kind: MapPointKind,
    val name: String,
    val type: ContactType,
    val latitude: Double,
    val longitude: Double,
    val isFavorite: Boolean,
    val isBlocked: Boolean,
    val hopCount: Int? = null,
) {
    val canMessage: Boolean get() = kind == MapPointKind.CONTACT && type == ContactType.CHAT && !isBlocked
}

sealed class MapUiState {
    data object Connecting : MapUiState()
    data class Ready(val points: List<MapPoint>) : MapUiState()
}

/**
 * Which pin groups the map shows — ported from `MapFilterState.swift`, trimmed to a single shape
 * shared by every host that uses it in this port (this screen's `mainMap`, and
 * [com.meshcoretwo.android.contacts.NeighborSnrMapViewModel]'s neighbor SNR map, which only reads
 * [favoritesOnly]/[effectiveShowDiscovered] from it) rather than porting Swift's
 * `MapFilterHost`/`MapFilterCapabilities` host-scoped capability system — see
 * [com.meshcoretwo.android.contacts.NeighborSnrMapViewModel]'s class doc for why that single fact
 * didn't justify the abstraction. Also not ported: `storageString`/`MapFilterPreferences`'
 * `UserDefaults` persistence/migration — this resets to defaults on every app launch, the same gap
 * already documented on `DiscoveryViewModel`'s missing sort-order persistence.
 */
data class MapFilterState(
    val favoritesOnly: Boolean = false,
    val showDiscovered: Boolean = false,
    val showChat: Boolean = true,
    val showRepeater: Boolean = true,
    val showRoom: Boolean = true,
) {
    /** Discovered layer for pin algebra (the stored flag stays frozen while Favorites is on). */
    val effectiveShowDiscovered: Boolean get() = !favoritesOnly && showDiscovered

    /** True when this differs from the default (drives the filter button's active indicator). */
    val isActive: Boolean get() = this != MapFilterState()

    /** Main Map type-axis gate. Favorites bypasses type filtering. */
    fun allowsContactType(type: ContactType): Boolean {
        if (favoritesOnly) return true
        return when (type) {
            ContactType.CHAT -> showChat
            ContactType.REPEATER -> showRepeater
            ContactType.ROOM -> showRoom
        }
    }

    fun withFavoritesOnly(value: Boolean) = copy(favoritesOnly = value)

    /** No-op while Favorites is on (discovered toggle freezes under Favorites). */
    fun withShowDiscovered(value: Boolean) = if (favoritesOnly) this else copy(showDiscovered = value)

    fun withShowChat(value: Boolean) = withType { copy(showChat = value) }
    fun withShowRepeater(value: Boolean) = withType { copy(showRepeater = value) }
    fun withShowRoom(value: Boolean) = withType { copy(showRoom = value) }

    /** Refuses to turn off the last enabled type, same guard as `MapFilterState.setType`. */
    private inline fun withType(mutate: MapFilterState.() -> MapFilterState): MapFilterState {
        if (favoritesOnly) return this
        val next = mutate()
        return if (!next.hasAtLeastOneEnabledType) this else next
    }

    private val hasAtLeastOneEnabledType: Boolean get() = showChat || showRepeater || showRoom
}

/**
 * The main Map tab's starting filter: everything with coordinates, including discovered
 * (not-yet-added) nodes. Deliberately differs from [MapFilterState]'s own default, which the
 * neighbor-SNR map also builds on.
 */
val MAIN_MAP_DEFAULT_FILTER = MapFilterState(showDiscovered = true)

/**
 * Backs the Map screen (`MapScreen.kt`) — PLAN.md's Phase 5 item 5, a deliberately trimmed port of
 * `MapViewModel.swift`: contacts/repeaters/rooms with a stored location, plus (since the
 * "Discovered nodes" series, see PLAN.md) not-yet-added nodes from the "Discover" list, filterable
 * by [MapFilterState]. Unlike iOS — SNR/hop trails, offline map packs, camera persistence across
 * launches, a dropped focus pin, and coalesced/debounced reloads on live store changes — none of
 * that is ported here (see PLAN.md's Phase 5 slice status for the deferred list); this is the
 * map-tab analogue of how Contacts/Channels shipped as MVPs first. Same imperative-refetch shape
 * as [com.meshcoretwo.android.contacts.ContactsListViewModel]: reload on entering
 * [DeviceConnectionState.READY] plus an explicit [refresh] (pull-to-refresh / toolbar button).
 * Filter toggles re-derive [uiState] from the cached unfiltered fetch instead of re-querying, the
 * same "warm re-filter" shape as `MapViewModel.swift`'s `applyFilter`.
 */
class MapViewModel(
    private val connectionManager: ConnectionManager,
    private val locationProvider: LocationProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Connecting)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow(MAIN_MAP_DEFAULT_FILTER)
    val filter: StateFlow<MapFilterState> = _filter.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Unfiltered located contacts / plottable discovered nodes from the last [refresh]. */
    private var allLocatedContacts: List<ContactDto> = emptyList()
    private var allLocatedDiscovered: List<DiscoveredNodeDto> = emptyList()
    private var hasCompletedInitialLoad = false

    init {
        viewModelScope.launch {
            connectionManager.connectionStateEvents.collect { state ->
                if (state == DeviceConnectionState.READY) refresh() else _uiState.value = MapUiState.Connecting
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (connectionManager.connectionState != DeviceConnectionState.READY) return@launch
            val radioID = connectionManager.lastConnectedRadioID ?: return@launch
            val contacts = connectionManager.contactService?.getContacts(radioID) ?: return@launch
            val discovered = connectionManager.discoveredNodeStore?.fetchDiscoveredNodes(radioID).orEmpty()

            val contactKeys = contacts.map { it.publicKey.hexString }.toSet()
            allLocatedContacts = contacts.filter { it.hasLocation }
            allLocatedDiscovered = discovered.filter { it.hasLocation && it.publicKey.hexString !in contactKeys }
            hasCompletedInitialLoad = true
            rebuildDisplayPins()
        }
    }

    fun setFavoritesOnly(value: Boolean) = updateFilter { withFavoritesOnly(value) }
    fun setShowDiscovered(value: Boolean) = updateFilter { withShowDiscovered(value) }
    fun setShowChat(value: Boolean) = updateFilter { withShowChat(value) }
    fun setShowRepeater(value: Boolean) = updateFilter { withShowRepeater(value) }
    fun setShowRoom(value: Boolean) = updateFilter { withShowRoom(value) }

    private inline fun updateFilter(mutate: MapFilterState.() -> MapFilterState) {
        _filter.value = _filter.value.mutate()
        rebuildDisplayPins()
    }

    /** Re-filter the cached unfiltered fetch; a no-op before the first [refresh] completes. */
    private fun rebuildDisplayPins() {
        if (!hasCompletedInitialLoad) return
        val filter = _filter.value
        val contacts = if (filter.favoritesOnly) {
            allLocatedContacts.filter { it.isFavorite }
        } else {
            allLocatedContacts.filter { filter.allowsContactType(it.type) }
        }
        val discovered = if (filter.effectiveShowDiscovered) {
            allLocatedDiscovered.filter { filter.allowsContactType(it.nodeType) }
        } else {
            emptyList()
        }
        _uiState.value = MapUiState.Ready(contacts.map { it.toMapPoint() } + discovered.map { it.toMapPoint() })
    }

    /**
     * One-shot device location fix for "center on me". Reports failure (permission not granted,
     * no positioning provider, timeout) via [errorMessage] instead of throwing — this screen has
     * no other error-surfacing UI. Unlike onboarding's Region step, this never triggers the
     * runtime permission prompt itself (see [LocationProvider]'s doc on why that's the UI layer's
     * job, not this one's) — if the user denied location during onboarding, this just fails.
     */
    fun requestMyLocation(onFix: (LocationFix) -> Unit) {
        viewModelScope.launch {
            try {
                onFix(locationProvider.requestCurrentLocation())
            } catch (e: LocationProviderError) {
                _errorMessage.value = e.message
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun ContactDto.toMapPoint() = MapPoint(
        pointId = id,
        kind = MapPointKind.CONTACT,
        name = displayName,
        type = type,
        latitude = latitude,
        longitude = longitude,
        isFavorite = isFavorite,
        isBlocked = isBlocked,
    )

    private fun DiscoveredNodeDto.toMapPoint() = MapPoint(
        pointId = id,
        kind = MapPointKind.DISCOVERED,
        name = name,
        type = nodeType,
        latitude = latitude,
        longitude = longitude,
        isFavorite = false,
        isBlocked = false,
        hopCount = displayedHopCount,
    )

    class Factory(
        private val connectionManager: ConnectionManager,
        private val locationProvider: LocationProvider,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MapViewModel(connectionManager, locationProvider) as T
    }
}
