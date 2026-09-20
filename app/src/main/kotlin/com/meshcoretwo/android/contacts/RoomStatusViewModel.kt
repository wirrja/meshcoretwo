// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.protocol.LPPDataPoint
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.protocol.TelemetryResponse
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.nodeSnapshotService
import com.meshcoretwo.services.connection.remoteNodeService
import com.meshcoretwo.services.connection.roomAdminService
import com.meshcoretwo.services.connection.roomServerService
import com.meshcoretwo.services.persistence.NodeStatusMetrics
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.remotenode.OCVPreset
import com.meshcoretwo.services.remotenode.RemoteNodeRetry
import com.meshcoretwo.services.remotenode.RoomServerService
import com.meshcoretwo.services.remotenode.primaryLocationFix
import com.meshcoretwo.services.remotenode.telemetrySnapshotCapture
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomStatusUiState(
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
    /** Baseline captured on each status reload, for the status deltas ([NodeStatusDeltas]). */
    val previousStatusSnapshot: NodeStatusSnapshotDto? = null,
    val isBatteryCurveExpanded: Boolean = false,
    val selectedOCVPreset: OCVPreset = OCVPreset.LI_ION,
    val ocvValues: List<Int> = OCVPreset.LI_ION.ocvArray,
    val ocvError: UiText? = null,
) {
    /**
     * The node's reported position from the last telemetry response, or null when it reports nothing
     * plottable. Drives the telemetry section's "View on Map". Ported from
     * `NodeStatusViewModel.currentLocationFix`.
     */
    val currentLocationFix get() = primaryLocationFix(cachedDataPoints)
}

/**
 * Backs the room status/telemetry screen (`RoomStatusScreen.kt`) — a trimmed port of
 * `RoomStatusView`/`RoomStatusContent`/`RoomStatusViewModel`/`NodeStatusViewModel.swift`. The
 * second sub-slice of the RemoteNodes UI epic (PLAN.md), following "NodeAuthScreen"; receives the
 * session that screen already authenticated and starts writing snapshots through the already-ported
 * [com.meshcoretwo.services.nodesnapshot.NodeSnapshotService].
 *
 * Swift shares its status/telemetry logic between `RoomStatusViewModel` and
 * `RepeaterStatusViewModel` via a common `NodeStatusViewModel` helper. This port splits that
 * helper's two concerns instead of mirroring the object composition: transient-retry machinery
 * lives in [com.meshcoretwo.services.remotenode.RemoteNodeRetry] (`services`, shared with
 * `RepeaterStatusViewModel`), and role-independent display formatters live in [NodeStatusDisplay]
 * (`app`, same sharing). Status/telemetry response handling stays per-view-model (each writes a
 * differently-shaped [NodeStatusMetrics]), matching how the two already differed even in Swift's
 * shared helper (`rxAirtimeSeconds`/`receiveErrors` args).
 *
 * Battery-curve/OCV editing (`NodeBatteryCurveDisclosureSection`/`BatteryCurveSection.swift`) is
 * now ported too ([loadOCVSettings]/[saveOCVSettings], backed by the now-ported
 * `ContactService.updateContactOCVSettings`) — see [BatteryCurveSectionContent] for the Compose
 * side and [NodeStatusDisplay.resolveOCVPreset]/[com.meshcoretwo.services.persistence.ContactDto.activeOCVArray]
 * for how a contact's stored preset/custom-array fields resolve to a selection. The status
 * section's [batteryDisplay] still shows voltage only, no percentage — that reading has no
 * `LPPDataPoint` to look up an OCV percentage from, unlike the telemetry section's voltage row.
 *
 * Status deltas against the previous snapshot are ported ([NodeStatusDeltas], shared with
 * `RepeaterStatusViewModel`): [handleStatusResponse] reads
 * [com.meshcoretwo.services.nodesnapshot.NodeSnapshotService.previousStatusSnapshot] into
 * [RoomStatusUiState.previousStatusSnapshot] before recording the fresh reading, matching Swift's
 * ordering.
 *
 * Both history drill-downs are ported too: [NodeStatusHistoryScreen] from the status section's
 * "History" button and [TelemetryHistoryScreen] from the telemetry section's.
 * [handleTelemetryResponse] records the reading's GPS fix alongside its entries
 * ([telemetrySnapshotCapture]), and the telemetry section links to [NodeLocationMapScreen] whenever
 * [RoomStatusUiState.currentLocationFix] is set.
 *
 * [toggleFavorite]/[setNotificationLevel] (room favorite/mute slice) are the session-level
 * counterparts of `ChannelInfoViewModel`'s — this is the only screen with a session to toggle them
 * from (`RoomConversationScreen` has no settings surface of its own), same reasoning
 * [RoomConversationScreen] already gives for routing "info" actions here.
 *
 * Deferred, matching this port's "smallest usable slice" scoping of the epic (see class docs on
 * `NodeAuthViewModel`/`NodeSnapshotService` for the same reasoning):
 * - Push-driven status/telemetry handlers (`RoomAdminService.setStatusHandler`/
 *   `setTelemetryHandler`) — nothing on Android wires [com.meshcoretwo.services.remotenode.RoomAdminService.invokeStatusHandler]
 *   to a live event yet (confirmed by grep — only tests call it), so this screen relies solely on
 *   the direct request/response `requestStatus`/`requestTelemetry` calls, matching what Swift's
 *   own `RoomStatusViewModel.requestStatus`/`requestTelemetry` do independent of those handlers.
 */
class RoomStatusViewModel(
    private val connectionManager: ConnectionManager,
    private val sessionId: UUID,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RoomStatusUiState())
    val uiState: StateFlow<RoomStatusUiState> = _uiState.asStateFlow()

    /** The contact backing the session's OCV settings, resolved lazily by [loadOCVSettings]. */
    private var contactID: UUID? = null

    init {
        viewModelScope.launch {
            val session = connectionManager.remoteNodeService?.fetchSession(sessionId)
            _uiState.update { it.copy(session = session) }
        }
    }

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

    fun reloadStatus() {
        val session = _uiState.value.session ?: return
        val roomAdminService = connectionManager.roomAdminService ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingStatus = true, statusError = null) }
            try {
                val response = RemoteNodeRetry.performWithTransientRetries { timeoutMs -> roomAdminService.requestStatus(session.id, timeoutMs) }
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
        val roomAdminService = connectionManager.roomAdminService ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTelemetry = true, telemetryError = null) }
            try {
                val response = RemoteNodeRetry.performWithTransientRetries { timeoutMs -> roomAdminService.requestTelemetry(session.id, timeoutMs) }
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

    fun toggleFavorite() = runSessionAction { service, session -> service.setFavorite(session.id, !session.isFavorite) }

    fun setNotificationLevel(level: NotificationLevel) = runSessionAction { service, session -> service.setNotificationLevel(session.id, level) }

    /** Runs [action] against the current session, then re-fetches it — same shape as
     * `ChannelInfoViewModel.runAction`. A no-op if there's no loaded session or no live
     * [RoomServerService] (disconnected). */
    private fun runSessionAction(action: suspend (RoomServerService, RemoteNodeSessionDto) -> Unit) {
        val session = _uiState.value.session ?: return
        val roomServerService = connectionManager.roomServerService ?: return
        viewModelScope.launch {
            try {
                action(roomServerService, session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Swallowed — the refetch below still shows whatever actually stuck.
            }
            val refreshed = connectionManager.remoteNodeService?.fetchSession(session.id)
            _uiState.update { it.copy(session = refreshed ?: it.session) }
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

        // rxAirtimeSeconds/receiveErrors deliberately left null — repeater-specific metrics a room
        // server doesn't need persisted, matching RoomStatusViewModel.handleStatusResponse (Swift).
        val metrics = NodeStatusMetrics(
            batteryMillivolts = response.battery.toUShort(),
            lastSNR = response.lastSNR,
            lastRSSI = response.lastRSSI.toShort(),
            noiseFloor = response.noiseFloor.toShort(),
            uptimeSeconds = response.uptime,
            rxAirtimeSeconds = null,
            packetsSent = response.packetsSent,
            packetsReceived = response.packetsReceived,
            receiveErrors = null,
            sentDirect = response.sentDirect,
            sentFlood = response.sentFlood,
            receivedDirect = response.receivedDirect,
            receivedFlood = response.receivedFlood,
            directDuplicates = response.directDuplicates.toUInt(),
            floodDuplicates = response.floodDuplicates.toUInt(),
            postedCount = response.roomServerPostedCount,
            postPushCount = response.roomServerPostPushCount,
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

    class Factory(
        private val connectionManager: ConnectionManager,
        private val sessionId: UUID,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RoomStatusViewModel(connectionManager, sessionId) as T
    }

    /** Room-only formatters; role-shared ones live in [NodeStatusDisplay]. */
    companion object {
        private const val EM_DASH = NodeStatusDisplay.EM_DASH

        fun postsReceivedDisplay(status: StatusResponse?): String = status?.roomServerPostedCount?.toString() ?: EM_DASH

        fun postsPushedDisplay(status: StatusResponse?): String = status?.roomServerPostPushCount?.toString() ?: EM_DASH
    }
}
