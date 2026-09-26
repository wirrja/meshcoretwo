// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.addKnownRegion
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.discoverRegions
import com.meshcoretwo.services.connection.removeKnownRegion
import com.meshcoretwo.services.settings.RegionDiscoveryService
import com.meshcoretwo.services.settings.RegionNameValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a running discovery is doing right now, for the progress line under the region list. */
sealed class RegionDiscoveryProgress {
    data class Listening(val responders: Int) : RegionDiscoveryProgress()
    data class Querying(val answered: Int, val total: Int, val newRegions: Int) : RegionDiscoveryProgress()
}

sealed class RegionManagementUiState {
    data object Connecting : RegionManagementUiState()
    data class Ready(val regions: List<String>) : RegionManagementUiState()
}

/**
 * Backs [RegionManagementScreen] — "Manage Regions" (add/remove/discover a device's known
 * flood-routing regions), reached from Settings' `DefaultFloodScopeSection` (PLAN.md's Phase 5
 * slice 32). Ported from `RegionManagementView.swift`, minus the `ChannelInfoSheet` call site
 * (per-channel region management + its `RegionDiscoveryResultsView` picker step) — a separate,
 * not-yet-wired follow-up since `ChannelInfoScreen` doesn't offer flood-scope editing at all yet.
 *
 * Owns its own `isDiscovering`/`discoveryMessage`/known-regions state rather than sharing
 * [SettingsViewModel]'s: Swift's `NavigationStack` keeps `DefaultFloodScopeSection` and
 * `RegionManagementView` alive together over one `@State` tree, but Compose Navigation gives each
 * destination its own `ViewModel` scoped to its own back-stack entry, so this screen's "Discover"
 * button (mirroring the one Swift's `RegionManagementView` also carries directly, alongside
 * `DefaultFloodScopeSection`'s own) runs independently of Settings'.
 */
class RegionManagementViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _uiState = MutableStateFlow<RegionManagementUiState>(RegionManagementUiState.Connecting)
    val uiState: StateFlow<RegionManagementUiState> = _uiState.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveryProgress = MutableStateFlow<RegionDiscoveryProgress?>(null)
    val discoveryProgress: StateFlow<RegionDiscoveryProgress?> = _discoveryProgress.asStateFlow()

    private val _discoveryMessage = MutableStateFlow<UiText?>(null)
    val discoveryMessage: StateFlow<UiText?> = _discoveryMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<UiText?>(null)
    val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

    private var discoveryJob: Job? = null

    init {
        viewModelScope.launch {
            connectionManager.connectionStateEvents.collect { state ->
                if (state == DeviceConnectionState.READY) refresh() else _uiState.value = RegionManagementUiState.Connecting
            }
        }
        refresh()
    }

    fun refresh() {
        val device = connectionManager.connectedDeviceRecord
        _uiState.value = if (connectionManager.connectionState == DeviceConnectionState.READY && device != null) {
            RegionManagementUiState.Ready(device.knownRegions)
        } else {
            RegionManagementUiState.Connecting
        }
    }

    /**
     * Validates then adds [name], ported from `RegionManagementView`'s add-alert confirm action.
     * Returns a user-facing message when validation fails, so the caller can keep its dialog open;
     * `null` means the name was valid and the add was dispatched.
     */
    fun addRegion(name: String): UiText? {
        val existing = (uiState.value as? RegionManagementUiState.Ready)?.regions ?: emptyList()
        val trimmed = name.trim()
        val error = RegionNameValidator.validate(trimmed, existing)
        if (error == null) {
            viewModelScope.launch {
                connectionManager.addKnownRegion(trimmed)
                refresh()
            }
            return null
        }
        return validationText(error)
    }

    fun removeRegion(region: String) {
        viewModelScope.launch {
            connectionManager.removeKnownRegion(region)
            refresh()
        }
    }

    /**
     * Ported from `DefaultFloodScopeSection.runDiscovery`/`RegionManagementView`'s shared discover
     * action. Unlike Swift, which adds everything at the end, each repeater's new regions are added
     * to the list as soon as it answers, with [discoveryProgress] driving a progress line, and
     * [stopDiscovery] can end the run early — regions added up to that point are kept.
     */
    fun runDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            _isDiscovering.value = true
            _discoveryMessage.value = null
            _discoveryProgress.value = null
            var addedCount = 0
            try {
                val outcome = connectionManager.discoverRegions { progress ->
                    when (progress) {
                        is RegionDiscoveryService.Progress.Listening ->
                            _discoveryProgress.value = RegionDiscoveryProgress.Listening(progress.responders)
                        is RegionDiscoveryService.Progress.Querying -> {
                            if (progress.newRegions.isNotEmpty()) {
                                progress.newRegions.forEach { connectionManager.addKnownRegion(it) }
                                addedCount += progress.newRegions.size
                                refresh()
                            }
                            _discoveryProgress.value = RegionDiscoveryProgress.Querying(progress.answered, progress.total, addedCount)
                        }
                    }
                }
                when (outcome) {
                    RegionDiscoveryService.Outcome.SendFailed -> {}
                    RegionDiscoveryService.Outcome.NoRepeatersResponded ->
                        _discoveryMessage.value = UiText.of(R.string.regions_msg_no_repeaters)
                    RegionDiscoveryService.Outcome.ErrorLoadingRepeaters ->
                        _discoveryMessage.value = UiText.of(R.string.regions_msg_load_failed)
                    is RegionDiscoveryService.Outcome.Completed -> {
                        if (outcome.newRegions.isEmpty() && outcome.allRepeatersTableFull) {
                            _discoveryMessage.value = UiText.of(R.string.regions_msg_table_full)
                        } else if (outcome.newRegions.isEmpty()) {
                            _discoveryMessage.value = UiText.of(R.string.regions_msg_none_new)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _errorMessage.value = error.toUiText(UiText.of(R.string.settings_err_generic))
            } finally {
                _isDiscovering.value = false
                _discoveryProgress.value = null
            }
        }
    }

    /** Ends a running discovery; regions it already added stay in the list. */
    fun stopDiscovery() {
        val job = discoveryJob ?: return
        if (!job.isActive) return
        job.cancel()
        _discoveryMessage.value = UiText.of(R.string.regions_msg_stopped)
    }

    override fun onCleared() {
        discoveryJob?.cancel()
    }

    private fun validationText(error: RegionNameValidator.ValidationError): UiText? = when (error) {
        is RegionNameValidator.ValidationError.Empty -> UiText.Plain("")
        is RegionNameValidator.ValidationError.InvalidCharacters -> UiText.of(R.string.regions_v_chars)
        is RegionNameValidator.ValidationError.TooLong -> UiText.of(R.string.regions_v_too_long, error.maxBytes)
        is RegionNameValidator.ValidationError.Duplicate -> UiText.of(R.string.regions_v_duplicate)
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RegionManagementViewModel(connectionManager) as T
    }
}
