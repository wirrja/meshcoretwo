// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.pathediting.NeighborNameResolver
import com.meshcoretwo.protocol.LPPDataPoint
import com.meshcoretwo.protocol.Neighbour
import com.meshcoretwo.protocol.OwnerInfoResponse
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.protocol.TelemetryResponse
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.discoveredNodeStore
import com.meshcoretwo.services.connection.nodeSnapshotService
import com.meshcoretwo.services.connection.remoteNodeService
import com.meshcoretwo.services.connection.repeaterAdminService
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.NeighborSnapshotEntry
import com.meshcoretwo.services.persistence.NodeStatusMetrics
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.remotenode.OCVPreset
import com.meshcoretwo.services.remotenode.RemoteNodeRetry
import com.meshcoretwo.services.remotenode.primaryLocationFix
import com.meshcoretwo.services.remotenode.telemetrySnapshotCapture
import com.meshcoretwo.services.rendering.NodeNameResolution
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RepeaterStatusUiState(
    val session: RemoteNodeSessionDto? = null,
    val status: StatusResponse? = null,
    val telemetry: TelemetryResponse? = null,
    val cachedDataPoints: List<LPPDataPoint> = emptyList(),
    val isLoadingStatus: Boolean = false,
    val isLoadingTelemetry: Boolean = false,
    val statusLoaded: Boolean = false,
    val statusExpanded: Boolean = false,
    val telemetryLoaded: Boolean = false,
    val telemetryExpanded: Boolean = false,
    val statusError: UiText? = null,
    val telemetryError: UiText? = null,
    val neighbors: List<Neighbour> = emptyList(),
    val isLoadingNeighbors: Boolean = false,
    val neighborsLoaded: Boolean = false,
    val neighborsExpanded: Boolean = false,
    val neighborsError: UiText? = null,
    val ownerInfo: OwnerInfoResponse? = null,
    val isLoadingOwnerInfo: Boolean = false,
    val ownerInfoExpanded: Boolean = false,
    val ownerInfoError: UiText? = null,
    val isDiscovering: Boolean = false,
    val discoverySecondsRemaining: Int = 0,
    /** Loaded once the session's `radioID` is known, for [RepeaterStatusViewModel.resolveNeighborName]. */
    val contacts: List<ContactDto> = emptyList(),
    val discoveredNodes: List<DiscoveredNodeDto> = emptyList(),
    /** The session's own contact, for [NodeRoutePathSection] at the bottom of the screen. Null hides that section. */
    val routePathContact: ContactDto? = null,
    /** Baseline captured on each neighbor reload, for the "New" badge, the SNR delta and disappeared-neighbor rows. */
    val previousNeighborSnapshot: NodeStatusSnapshotDto? = null,
    val seenNeighborPrefixes: Set<String> = emptySet(),
    /** Baseline captured on each status reload, for the status deltas ([NodeStatusDeltas]). */
    val previousStatusSnapshot: NodeStatusSnapshotDto? = null,
    val isBatteryCurveExpanded: Boolean = false,
    val selectedOCVPreset: OCVPreset = OCVPreset.LI_ION,
    val ocvValues: List<Int> = OCVPreset.LI_ION.ocvArray,
    val ocvError: UiText? = null,
) {
    /** See [RoomStatusUiState.currentLocationFix]. */
    val currentLocationFix get() = primaryLocationFix(cachedDataPoints)
}

/**
 * Backs the repeater status/telemetry/neighbors screen (`RepeaterStatusScreen.kt`) — a trimmed
 * port of `RepeaterStatusView`/`RepeaterStatusContent`/`RepeaterStatusViewModel`/
 * `NodeStatusViewModel.swift`. Third sub-slice of the RemoteNodes UI epic (PLAN.md), the "second
 * consumer" [RoomStatusViewModel]'s class doc named as the point to reconsider a shared helper —
 * see that doc for how the split landed: transient retries in
 * [com.meshcoretwo.services.remotenode.RemoteNodeRetry], role-independent formatters in
 * [NodeStatusDisplay], telemetry-entry mapping in
 * [com.meshcoretwo.services.remotenode.telemetrySnapshotEntries], all shared with
 * [RoomStatusViewModel]; status handling stays per-view-model since it writes a differently-shaped
 * [NodeStatusMetrics] (`rxAirtimeSeconds`/`receiveErrors` here, `postedCount`/`postPushCount` for
 * Room).
 *
 * Adds sections Room doesn't have:
 * - Owner info: a plain request/response section (`RepeaterAdminService.requestOwnerInfo`), no
 *   snapshot persistence on the Swift side either.
 * - Neighbors: fetched via the already-ported `RepeaterAdminService.fetchAllNeighbors` and
 *   persisted through `NodeSnapshotService.recordSnapshot(neighbors = ...)`, plus active discovery
 *   (`startDiscovery`/`stopDiscovery`, ported from Swift's `RepeaterStatusViewModel`): sends the
 *   `discover.neighbors` CLI command, then re-fetches every 5 of 60 one-second ticks so freshly
 *   discovered neighbors appear while the countdown runs, using [Job.cancel] where Swift cancels
 *   its `Task`; name resolution ([resolveNeighborName], ported from `NeighborNameResolver.resolve`):
 *   contacts/discovered nodes for the connected session's `radioID` are loaded once in [init], and
 *   neighbor rows show the resolved display name plus a "possible match" marker on a fallback
 *   (prefix-collision) match, falling back to the raw hex key prefix when nothing matches — same
 *   as Swift; and the "New" badge/disappeared-neighbor rows ([isNewNeighbor]/[disappearedNeighbors],
 *   ported from Swift's `enrichNeighbors`/`NeighborRow`'s `isNew`/`NeighborsSection`'s disappeared
 *   `ForEach`): each neighbor reload captures [NodeSnapshotService.neighborBaseline] — the prior
 *   neighbor-bearing snapshot plus every prefix seen across history — *before* persisting the new
 *   reading, so the badge/disappeared rows reflect history rather than the capture that just ran.
 *   Still trimmed relative to Swift's `NeighborsSection`/`NeighborRow`:
 *     - "View on Map" now pushes [NeighborSnrMapScreen] (backed by [NeighborSnrMapViewModel]/
 *       [NeighborSnrMapBuilder], ported from `NeighborSNRMapView.swift`/`NeighborSNRMapBuilder.swift`),
 *       and current rows now carry the per-neighbor SNR delta against the baseline reading
 *       ([neighborSnrDelta], rendered by [StatusDeltaLabel]) — but there is still no per-neighbor
 *       SNR *chart* drill-down (`NeighborSNRChartView.swift`); it would now be a short hop on top
 *       of [MetricChart], but nothing links to it and neighbor history has no other consumer yet.
 *       Disappeared rows carry no delta in Swift either.
 *     - [currentLocation] is never populated by the screen (no `LocationProvider` request there
 *       yet, unlike `DiscoveryScreen`'s opt-in fetch) — proximity only breaks ties among several
 *       same-prefix candidates, which resolves fine on recency/name alone in the common case.
 * - Push-driven status/neighbours/telemetry handlers (`RepeaterAdminService.setStatusHandler`/
 *   `setNeighboursHandler`/`setTelemetryHandler`) — same gap as [RoomStatusViewModel]'s doc: no
 *   Android call site invokes these outside tests.
 *
 * Battery-curve/OCV editing ([loadOCVSettings]/[saveOCVSettings]) is ported too, sharing
 * [NodeStatusDisplay.resolveOCVPreset] with [RoomStatusViewModel] — see that class's doc. See it
 * also for the telemetry section's location recording and "View on Map" link, shared with this
 * screen, as are both history drill-downs ([NodeStatusHistoryScreen], [TelemetryHistoryScreen]).
 * Tapping a neighbor row opens its SNR history ([NeighborSnrChartScreen]), as Swift's
 * `NodeStatusRoute.neighborChart` does; disappeared-neighbor rows don't link, as in Swift.
 *
 * Status deltas are ported ([NodeStatusDeltas], shared with [RoomStatusViewModel]):
 * [handleStatusResponse] reads [com.meshcoretwo.services.nodesnapshot.NodeSnapshotService.previousStatusSnapshot]
 * into [RepeaterStatusUiState.previousStatusSnapshot] before recording the fresh reading, the same
 * baseline-before-write ordering [handleNeighboursResponse] already used for the neighbor baseline.
 *
 * Route-path display ([loadRoutePathContact], `NodeRoutePathSection` at the bottom of the screen):
 * ported from `NodeRoutePathSection.swift`/`RepeaterStatusContent.swift`'s `routePathContact`,
 * resolving the session's own stored path (repeater hops only, since only repeaters relay) against
 * the same [contacts]/[discoveredNodes] the neighbors section already loads. Deliberately not kept
 * live across contact updates — see [loadRoutePathContact]'s doc.
 */
class RepeaterStatusViewModel(
    private val connectionManager: ConnectionManager,
    private val sessionId: UUID,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RepeaterStatusUiState())
    val uiState: StateFlow<RepeaterStatusUiState> = _uiState.asStateFlow()

    private var discoveryJob: Job? = null

    /**
     * Set by the screen from [com.meshcoretwo.services.location.LocationProvider], same
     * opt-in-only pattern as [com.meshcoretwo.android.tools.TracePathViewModel.setCurrentLocation]
     * — this view model never requests a location fix itself. Only used to break proximity ties
     * between same-prefix contacts/discovered nodes in [resolveNeighborName]; `null` still
     * resolves correctly via the recency/name tiebreakers [com.meshcoretwo.android.pathediting.RepeaterResolver]
     * falls back to.
     */
    private var currentLocation: LocationFix? = null

    /** The contact backing the session's OCV settings, resolved lazily by [loadOCVSettings]. */
    private var contactID: UUID? = null

    init {
        viewModelScope.launch {
            val session = connectionManager.remoteNodeService?.fetchSession(sessionId)
            _uiState.update { it.copy(session = session) }
            if (session != null) {
                loadNameResolutionSources(session.radioID)
                loadRoutePathContact(session)
            }
        }
    }

    fun setCurrentLocation(fix: LocationFix?) {
        currentLocation = fix
    }

    private suspend fun loadNameResolutionSources(radioID: UUID) {
        val contacts = connectionManager.contactService?.getContacts(radioID) ?: emptyList()
        val discoveredNodes = connectionManager.discoveredNodeStore?.fetchDiscoveredNodes(radioID) ?: emptyList()
        _uiState.update { it.copy(contacts = contacts, discoveredNodes = discoveredNodes) }
    }

    /**
     * Loads the session's own contact for [NodeRoutePathSection], matching Swift's `refreshRouteContact`
     * — minus its live refresh on contact-table changes, since nothing else on this screen is
     * reactive either (a "smallest usable slice" trim, same as elsewhere in this class).
     */
    private suspend fun loadRoutePathContact(session: RemoteNodeSessionDto) {
        val contact = connectionManager.contactService?.getContact(session.radioID, session.publicKey) ?: return
        _uiState.update { it.copy(routePathContact = contact) }
    }

    /** Resolves a neighbor's public-key prefix to a display name, or `null` when nothing matches. */
    fun resolveNeighborName(publicKeyPrefix: ByteArray): NodeNameResolution? =
        NeighborNameResolver.resolve(publicKeyPrefix, _uiState.value.contacts, _uiState.value.discoveredNodes, currentLocation)

    fun toggleStatusExpanded() {
        val expanded = !_uiState.value.statusExpanded
        _uiState.update { it.copy(statusExpanded = expanded) }
        if (expanded && !_uiState.value.statusLoaded && !_uiState.value.isLoadingStatus) reloadStatus()
    }

    fun toggleTelemetryExpanded() {
        val expanded = !_uiState.value.telemetryExpanded
        _uiState.update { it.copy(telemetryExpanded = expanded) }
        if (expanded && !_uiState.value.telemetryLoaded && !_uiState.value.isLoadingTelemetry) reloadTelemetry()
    }

    fun toggleNeighborsExpanded() {
        val expanded = !_uiState.value.neighborsExpanded
        _uiState.update { it.copy(neighborsExpanded = expanded) }
        if (expanded && !_uiState.value.neighborsLoaded && !_uiState.value.isLoadingNeighbors) reloadNeighbors()
    }

    fun toggleOwnerInfoExpanded() {
        val expanded = !_uiState.value.ownerInfoExpanded
        _uiState.update { it.copy(ownerInfoExpanded = expanded) }
        if (expanded && _uiState.value.ownerInfo == null && !_uiState.value.isLoadingOwnerInfo) reloadOwnerInfo()
    }

    fun reloadStatus() {
        val session = _uiState.value.session ?: return
        val repeaterAdminService = connectionManager.repeaterAdminService ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingStatus = true, statusError = null) }
            try {
                val response =
                    RemoteNodeRetry.performWithTransientRetries { timeoutMs -> repeaterAdminService.requestStatus(session.id, timeoutMs) }
                handleStatusResponse(session, response)
                _uiState.update { it.copy(isLoadingStatus = false) }
            } catch (error: CancellationException) {
                _uiState.update { it.copy(isLoadingStatus = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoadingStatus = false, statusError = error.toUiText(UiText.of(R.string.status_err_status))) }
            }
        }
    }

    fun reloadTelemetry() {
        val session = _uiState.value.session ?: return
        val repeaterAdminService = connectionManager.repeaterAdminService ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTelemetry = true, telemetryError = null) }
            try {
                val response =
                    RemoteNodeRetry.performWithTransientRetries { timeoutMs -> repeaterAdminService.requestTelemetry(session.id, timeoutMs) }
                handleTelemetryResponse(session, response)
                _uiState.update { it.copy(isLoadingTelemetry = false) }
            } catch (error: CancellationException) {
                _uiState.update { it.copy(isLoadingTelemetry = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoadingTelemetry = false, telemetryError = error.toUiText(UiText.of(R.string.status_err_telemetry))) }
            }
        }
    }

    fun reloadNeighbors() {
        if (_uiState.value.isDiscovering) return
        viewModelScope.launch { fetchNeighborsOnce() }
    }

    /** Shared by [reloadNeighbors] and the discovery loop's periodic re-fetch. */
    private suspend fun fetchNeighborsOnce() {
        val session = _uiState.value.session ?: return
        val repeaterAdminService = connectionManager.repeaterAdminService ?: return
        _uiState.update { it.copy(isLoadingNeighbors = true, neighborsError = null) }
        try {
            val response =
                RemoteNodeRetry.performWithTransientRetries { timeoutMs -> repeaterAdminService.fetchAllNeighbors(session.id, timeoutMs = timeoutMs) }
            handleNeighboursResponse(session, response.neighbours)
            _uiState.update { it.copy(isLoadingNeighbors = false) }
        } catch (error: CancellationException) {
            _uiState.update { it.copy(isLoadingNeighbors = false) }
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(isLoadingNeighbors = false, neighborsError = error.toUiText(UiText.of(R.string.neighbors_err_failed))) }
        }
    }

    /**
     * Sends `discover.neighbors`, then re-polls [fetchNeighborsOnce] every [DISCOVERY_POLL_TICKS]
     * of a [DISCOVERY_DURATION_SECONDS]-second countdown so newly discovered neighbors show up
     * without waiting for the whole window. Ported from Swift's `startDiscovery`.
     */
    fun startDiscovery() {
        val session = _uiState.value.session ?: return
        val repeaterAdminService = connectionManager.repeaterAdminService ?: return
        if (_uiState.value.isDiscovering) return

        _uiState.update { it.copy(isDiscovering = true, discoverySecondsRemaining = DISCOVERY_DURATION_SECONDS) }
        discoveryJob = viewModelScope.launch {
            try {
                repeaterAdminService.sendCommand(session.id, DISCOVER_COMMAND)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isDiscovering = false, discoverySecondsRemaining = 0, neighborsError = error.toUiText(UiText.of(R.string.status_err_discovery)))
                }
                discoveryJob = null
                return@launch
            }

            var tick = 0
            while (isActive) {
                delay(1_000L)
                if (!isActive) break
                tick += 1
                val remaining = (DISCOVERY_DURATION_SECONDS - tick).coerceAtLeast(0)
                _uiState.update { it.copy(discoverySecondsRemaining = remaining) }
                if (tick % DISCOVERY_POLL_TICKS == 0) fetchNeighborsOnce()
                if (remaining <= 0) break
            }
            _uiState.update { it.copy(isDiscovering = false, discoverySecondsRemaining = 0) }
            discoveryJob = null
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _uiState.update { it.copy(isDiscovering = false, discoverySecondsRemaining = 0) }
    }

    fun toggleDiscovery() {
        if (_uiState.value.isDiscovering) stopDiscovery() else startDiscovery()
    }

    fun reloadOwnerInfo() {
        val session = _uiState.value.session ?: return
        val repeaterAdminService = connectionManager.repeaterAdminService ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingOwnerInfo = true, ownerInfoError = null) }
            try {
                val response =
                    RemoteNodeRetry.performWithTransientRetries { timeoutMs -> repeaterAdminService.requestOwnerInfo(session.id, timeoutMs) }
                _uiState.update { it.copy(ownerInfo = response, isLoadingOwnerInfo = false) }
            } catch (error: CancellationException) {
                _uiState.update { it.copy(isLoadingOwnerInfo = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoadingOwnerInfo = false, ownerInfoError = error.toUiText(UiText.of(R.string.status_err_owner_info))) }
            }
        }
    }

    fun toggleBatteryCurveExpanded() {
        val expanded = !_uiState.value.isBatteryCurveExpanded
        _uiState.update { it.copy(isBatteryCurveExpanded = expanded) }
        if (expanded) loadOCVSettings()
    }

    /** Loads the session contact's OCV settings once (skips reload if [contactID] is already known). */
    private fun loadOCVSettings() {
        if (contactID != null) return
        val session = _uiState.value.session ?: return
        val contactService = connectionManager.contactService ?: return
        viewModelScope.launch {
            try {
                val contact = contactService.getContact(session.radioID, session.publicKey) ?: return@launch
                contactID = contact.id
                _uiState.update {
                    it.copy(selectedOCVPreset = NodeStatusDisplay.resolveOCVPreset(contact), ocvValues = contact.activeOCVArray)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(ocvError = UiText.of(R.string.status_err_ocv_load)) }
            }
        }
    }

    fun saveOCVSettings(preset: OCVPreset, values: List<Int>) {
        val contactService = connectionManager.contactService
        val id = contactID
        if (contactService == null || id == null) {
            _uiState.update { it.copy(ocvError = UiText.of(R.string.status_err_ocv_no_contact)) }
            return
        }
        _uiState.update { it.copy(ocvError = null) }
        viewModelScope.launch {
            try {
                val customArray = if (preset == OCVPreset.CUSTOM) values.joinToString(",") else null
                contactService.updateContactOCVSettings(id, preset.rawValue, customArray)
                _uiState.update { it.copy(selectedOCVPreset = preset, ocvValues = values) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(ocvError = UiText.of(R.string.status_err_ocv_save, error.message.orEmpty())) }
            }
        }
    }

    private suspend fun handleStatusResponse(session: RemoteNodeSessionDto, response: StatusResponse) {
        _uiState.update { it.copy(status = response, statusLoaded = true, statusError = null) }

        val nodeSnapshotService = connectionManager.nodeSnapshotService ?: return

        // Capture the prior baseline before persisting this reading, so the deltas compare against
        // history rather than against the row this call is about to write (as in Swift).
        val previous = nodeSnapshotService.previousStatusSnapshot(session.publicKey, Instant.now())
        _uiState.update { it.copy(previousStatusSnapshot = previous) }

        val metrics = NodeStatusMetrics(
            batteryMillivolts = response.battery.toUShort(),
            lastSNR = response.lastSNR,
            lastRSSI = response.lastRSSI.toShort(),
            noiseFloor = response.noiseFloor.toShort(),
            uptimeSeconds = response.uptime,
            rxAirtimeSeconds = response.rxAirtime,
            packetsSent = response.packetsSent,
            packetsReceived = response.packetsReceived,
            receiveErrors = response.receiveErrors,
            sentDirect = response.sentDirect,
            sentFlood = response.sentFlood,
            receivedDirect = response.receivedDirect,
            receivedFlood = response.receivedFlood,
            directDuplicates = response.directDuplicates.toUInt(),
            floodDuplicates = response.floodDuplicates.toUInt(),
            // postedCount/postPushCount are room-only, matching RoomStatusViewModel leaving
            // rxAirtimeSeconds/receiveErrors null for the same reason in reverse.
            postedCount = null,
            postPushCount = null,
        )
        nodeSnapshotService.recordSnapshot(nodePublicKey = session.publicKey, status = metrics)
    }

    private suspend fun handleTelemetryResponse(session: RemoteNodeSessionDto, response: TelemetryResponse) {
        val dataPoints = response.dataPoints.filter { it.channel != UByte.MIN_VALUE }
        _uiState.update {
            it.copy(telemetry = response, cachedDataPoints = dataPoints, telemetryLoaded = true, telemetryError = null)
        }

        val capture = telemetrySnapshotCapture(dataPoints) ?: return
        val nodeSnapshotService = connectionManager.nodeSnapshotService ?: return
        nodeSnapshotService.recordSnapshot(nodePublicKey = session.publicKey, telemetry = capture.telemetry, location = capture.location)
    }

    private suspend fun handleNeighboursResponse(session: RemoteNodeSessionDto, neighbours: List<Neighbour>) {
        _uiState.update { it.copy(neighbors = neighbours, neighborsLoaded = true, neighborsError = null) }

        val nodeSnapshotService = connectionManager.nodeSnapshotService ?: return

        // Capture the prior baseline before persisting this reading, so the "New" badge and the
        // disappeared-neighbor rows reflect history rather than the capture that just ran.
        val (previous, seenPrefixes) = nodeSnapshotService.neighborBaseline(session.publicKey)
        _uiState.update { it.copy(previousNeighborSnapshot = previous, seenNeighborPrefixes = seenPrefixes) }

        val entries = neighbours.map { NeighborSnapshotEntry(publicKeyPrefix = it.publicKeyPrefix, snr = it.snr, secondsAgo = it.secondsAgo) }
        nodeSnapshotService.recordSnapshot(nodePublicKey = session.publicKey, neighbors = entries)
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val sessionId: UUID,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RepeaterStatusViewModel(connectionManager, sessionId) as T
    }

    /** Repeater-only formatters; role-shared ones live in [NodeStatusDisplay]. */
    companion object {
        private const val EM_DASH = NodeStatusDisplay.EM_DASH

        /** Hidden when zero, matching `receiveErrorsDisplay` (Swift) hiding an all-firmware-supported-but-zero row. */
        fun receiveErrorsDisplay(status: StatusResponse?): String? =
            status?.receiveErrors?.takeIf { it > 0u }?.toString()

        /**
         * Uppercase hex of a neighbor's public-key prefix — the row's fallback display when
         * [resolveNeighborName] finds no contact/discovered-node match, same as Swift's
         * `NeighborNameResolver.fallbackName`. Fixed at [NEIGHBOR_KEY_DISPLAY_BYTES] (2) rather
         * than Swift's device-hash-size-derived width — see this class's doc.
         */
        fun neighborKeyDisplay(publicKeyPrefix: ByteArray): String =
            NeighborNameResolver.fallbackName(publicKeyPrefix, NEIGHBOR_KEY_DISPLAY_BYTES)

        fun lastSeenDisplay(secondsAgo: Int): String = when {
            secondsAgo < 60 -> "${secondsAgo}s ago"
            secondsAgo < 3600 -> "${secondsAgo / 60}m ago"
            else -> "${secondsAgo / 3600}h ago"
        }

        fun neighborSNRDisplay(snr: Double): String = "%.1f dB".format(Locale.US, snr)

        /**
         * Whether [publicKeyPrefix] is first-seen: there is a neighbor-bearing baseline to compare
         * against, and this prefix isn't in it. With no baseline yet (first-ever neighbor load for
         * this node), nothing can be claimed new. Ported from Swift's `NeighborRow`-construction
         * `isNew` expression in `NeighborsSection`.
         */
        fun isNewNeighbor(publicKeyPrefix: ByteArray, previousNeighborSnapshot: NodeStatusSnapshotDto?, seenNeighborPrefixes: Set<String>): Boolean =
            previousNeighborSnapshot != null && publicKeyPrefix.hexString !in seenNeighborPrefixes

        /**
         * Neighbors present in [previousNeighborSnapshot] but absent from [currentNeighbors] — the
         * baseline reading's entries that dropped out. Ported from `NeighborsSection`'s disappeared
         * `ForEach`.
         */
        fun disappearedNeighbors(currentNeighbors: List<Neighbour>, previousNeighborSnapshot: NodeStatusSnapshotDto?): List<NeighborSnapshotEntry> {
            val previousNeighbors = previousNeighborSnapshot?.neighborSnapshots ?: return emptyList()
            val currentPrefixes = currentNeighbors.map { it.publicKeyPrefix.hexString }.toSet()
            return previousNeighbors.filter { it.publicKeyPrefix.hexString !in currentPrefixes }
        }

        /**
         * A neighbor's SNR change against the baseline reading, or `null` when the neighbor is
         * absent from the baseline or the change is below [NEIGHBOR_SNR_DELTA_THRESHOLD]. Ported
         * from the `previousNeighbor`/`snrDelta` branch of `NeighborRow` (Swift), which hides
         * sub-0.1 dB noise the same way.
         */
        fun neighborSnrDelta(neighbor: Neighbour, previousNeighborSnapshot: NodeStatusSnapshotDto?): Double? {
            val previousEntry = previousNeighborSnapshot?.neighborSnapshots
                ?.firstOrNull { it.publicKeyPrefix.hexString == neighbor.publicKeyPrefix.hexString }
                ?: return null
            val delta = neighbor.snr - previousEntry.snr
            return delta.takeIf { abs(it) >= NEIGHBOR_SNR_DELTA_THRESHOLD }
        }

        private const val NEIGHBOR_SNR_DELTA_THRESHOLD = 0.1

        private const val NEIGHBOR_KEY_DISPLAY_BYTES = 2

        private const val DISCOVERY_DURATION_SECONDS = 60
        private const val DISCOVERY_POLL_TICKS = 5
        private const val DISCOVER_COMMAND = "discover.neighbors"
    }
}
