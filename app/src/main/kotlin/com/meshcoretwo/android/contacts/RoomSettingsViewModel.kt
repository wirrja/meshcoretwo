// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.remoteNodeService
import com.meshcoretwo.services.connection.roomAdminService
import com.meshcoretwo.services.remotenode.CLIResponse
import com.meshcoretwo.services.remotenode.RemoteNodeError
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomSettingsUiState(
    // Room Access (guest password + read-only)
    val guestPassword: String? = null,
    val allowReadOnly: Boolean? = null,
    val originalGuestPassword: String? = null,
    val originalAllowReadOnly: Boolean? = null,
    val isLoadingRoomAccess: Boolean = false,
    val roomAccessError: Boolean = false,
    val isApplyingRoomAccess: Boolean = false,
    val roomAccessApplySuccess: Boolean = false,
    val isRoomAccessExpanded: Boolean = false,

    // Behavior (advert intervals + flood)
    val advertIntervalMinutes: Int? = null,
    val floodAdvertIntervalHours: Int? = null,
    val floodMaxHops: Int? = null,
    val originalAdvertIntervalMinutes: Int? = null,
    val originalFloodAdvertIntervalHours: Int? = null,
    val originalFloodMaxHops: Int? = null,
    val isLoadingBehavior: Boolean = false,
    val behaviorError: Boolean = false,
    val isApplyingBehavior: Boolean = false,
    val behaviorApplySuccess: Boolean = false,
    val isBehaviorExpanded: Boolean = false,
    val advertIntervalError: UiText? = null,
    val floodAdvertIntervalError: UiText? = null,
    val floodMaxHopsError: UiText? = null,
) {
    val roomAccessLoaded: Boolean get() = guestPassword != null || allowReadOnly != null

    val roomAccessModified: Boolean
        get() = (guestPassword != null && guestPassword != originalGuestPassword) ||
            (allowReadOnly != null && allowReadOnly != originalAllowReadOnly)

    val behaviorLoaded: Boolean get() = advertIntervalMinutes != null || floodAdvertIntervalHours != null || floodMaxHops != null

    val behaviorModified: Boolean
        get() = (advertIntervalMinutes != null && advertIntervalMinutes != originalAdvertIntervalMinutes) ||
            (floodAdvertIntervalHours != null && floodAdvertIntervalHours != originalFloodAdvertIntervalHours) ||
            (floodMaxHops != null && floodMaxHops != originalFloodMaxHops)
}

/**
 * Backs the room settings screen (not yet built — see PLAN.md's Node Settings epic, sub-slice 4).
 * Ported from `RoomSettingsViewModel.swift`, the room counterpart of [RepeaterSettingsViewModel]:
 * same third sub-slice of the epic, built on the shared [NodeSettingsViewModel] (sub-slice 2), but
 * a trimmed room-only subset (room access + behavior, no repeat mode/regions) — see that class's
 * doc for the architectural notes ([ViewModel]-vs-plain-helper split, [onCleared] replacing Swift's
 * `cleanup()`, no `makeNodeCLISendClosure` port) that apply here identically.
 *
 * Unlike [RepeaterSettingsViewModel], Room has no binary owner-info protocol — firmware is fetched
 * via CLI only, so [NodeSettingsViewModel.onPreFetchNodeInfo] is left unset (matching Swift's
 * `helper.onPreFetchNodeInfo = nil` comment) and device info is fetched directly on [init].
 *
 * Applying room access uses its own [RoomSettingsUiState.isApplyingRoomAccess]/
 * [applyRoomAccess]-vs-[RoomSettingsUiState.isApplyingBehavior] flags rather than the shared
 * [NodeSettingsViewModel]'s `isApplying`, matching Swift's `RoomSettingsViewModel` keeping its own
 * separate applying flags per section instead of routing through `helper.isApplying` the way
 * [RepeaterSettingsViewModel]'s behavior/regions do — only `errorMessage` is shared here.
 */
class RoomSettingsViewModel(
    private val connectionManager: ConnectionManager,
    private val sessionId: UUID,
) : ViewModel() {
    /** Shared device info/identity/radio/contact info/security/device-action logic. */
    val settings = NodeSettingsViewModel()

    private val _uiState = MutableStateFlow(RoomSettingsUiState())
    val uiState: StateFlow<RoomSettingsUiState> = _uiState.asStateFlow()

    private val roomAdminService get() = connectionManager.roomAdminService

    init {
        viewModelScope.launch {
            val session = connectionManager.remoteNodeService?.fetchSession(sessionId) ?: return@launch
            val roomAdminService = roomAdminService ?: return@launch

            settings.configure(
                session = session,
                sendCommand = { id, cmd, timeoutMs -> roomAdminService.sendCommand(id, cmd, timeoutMs) },
                sendRawCommand = { id, cmd, timeoutMs -> roomAdminService.sendRawCommand(id, cmd, timeoutMs) },
            )
            settings.setNodeInfo(name = session.name)

            registerBehaviorLateRecovery()

            roomAdminService.setCLIHandler { message, _ -> settings.handleCommonLateResponse(message.text) }

            settings.fetchDeviceInfo()
        }
    }

    override fun onCleared() {
        roomAdminService?.setCLIHandler { _, _ -> }
    }

    // MARK: - Late Reply Recovery

    private fun behaviorSectionComplete(state: RoomSettingsUiState): Boolean =
        state.originalAdvertIntervalMinutes != null && state.originalFloodAdvertIntervalHours != null && state.originalFloodMaxHops != null

    private fun registerBehaviorLateRecovery() {
        settings.registerLateRecovery("get advert.interval") { value ->
            if (value !is CLIResponse.AdvertInterval) return@registerLateRecovery
            _uiState.update { state ->
                val updated = state.copy(advertIntervalMinutes = value.minutes, originalAdvertIntervalMinutes = value.minutes)
                updated.copy(behaviorError = !behaviorSectionComplete(updated))
            }
        }
        settings.registerLateRecovery("get flood.advert.interval") { value ->
            if (value !is CLIResponse.FloodAdvertInterval) return@registerLateRecovery
            _uiState.update { state ->
                val updated = state.copy(floodAdvertIntervalHours = value.hours, originalFloodAdvertIntervalHours = value.hours)
                updated.copy(behaviorError = !behaviorSectionComplete(updated))
            }
        }
        settings.registerLateRecovery("get flood.max") { value ->
            if (value !is CLIResponse.FloodMax) return@registerLateRecovery
            _uiState.update { state ->
                val updated = state.copy(floodMaxHops = value.hops, originalFloodMaxHops = value.hops)
                updated.copy(behaviorError = !behaviorSectionComplete(updated))
            }
        }
    }

    // MARK: - Field Setters

    fun setGuestPassword(value: String?) {
        _uiState.update { it.copy(guestPassword = value) }
    }

    fun setAllowReadOnly(value: Boolean?) {
        _uiState.update { it.copy(allowReadOnly = value) }
    }

    fun setAdvertIntervalMinutes(value: Int?) {
        _uiState.update { it.copy(advertIntervalMinutes = value) }
    }

    fun setFloodAdvertIntervalHours(value: Int?) {
        _uiState.update { it.copy(floodAdvertIntervalHours = value) }
    }

    fun setFloodMaxHops(value: Int?) {
        _uiState.update { it.copy(floodMaxHops = value) }
    }

    fun setRoomAccessExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isRoomAccessExpanded = expanded) }
    }

    fun setBehaviorExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isBehaviorExpanded = expanded) }
    }

    // MARK: - Room Access Fetch/Apply

    fun fetchRoomAccess() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRoomAccess = true, roomAccessError = false) }

            try {
                val response = settings.sendAndWait("get guest.password", rawMatching = true)
                when (CLIResponse.parse(response, "get guest.password")) {
                    is CLIResponse.Ok, is CLIResponse.Error, is CLIResponse.UnknownCommand -> {
                        _uiState.update { it.copy(guestPassword = "", originalGuestPassword = "") }
                    }
                    else -> {
                        val trimmed = response.trim()
                        val value = if (trimmed.startsWith("> ")) trimmed.substring(2) else trimmed
                        _uiState.update { it.copy(guestPassword = value, originalGuestPassword = value) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteNodeError.Timeout) {
                _uiState.update { it.copy(roomAccessError = true) }
            } catch (error: Exception) {
                // Non-timeout failures are silently ignored, matching Swift's log-only handling.
            }

            try {
                val response = settings.sendAndWait("get allow.read.only", rawMatching = true)
                val parsed = CLIResponse.parse(response, "get allow.read.only")
                if (parsed is CLIResponse.Raw) {
                    val isOn = parsed.text.lowercase() == "on"
                    _uiState.update { it.copy(allowReadOnly = isOn, originalAllowReadOnly = isOn) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteNodeError.Timeout) {
                _uiState.update { it.copy(roomAccessError = true) }
            } catch (error: Exception) {
                // Non-timeout failures are silently ignored, matching Swift's log-only handling.
            }

            _uiState.update { it.copy(isLoadingRoomAccess = false) }
        }
    }

    fun applyRoomAccess() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isApplyingRoomAccess = true) }
            settings.setErrorMessage(null)

            try {
                var allSucceeded = true

                val guestPassword = state.guestPassword
                if (guestPassword != null && guestPassword != state.originalGuestPassword) {
                    val response = settings.sendAndWait("set guest.password $guestPassword")
                    if (CLIResponse.parse(response) is CLIResponse.Ok) {
                        _uiState.update { it.copy(originalGuestPassword = guestPassword) }
                    } else {
                        allSucceeded = false
                    }
                }

                val allowReadOnly = state.allowReadOnly
                if (allowReadOnly != null && allowReadOnly != state.originalAllowReadOnly) {
                    val response = settings.sendAndWait("set allow.read.only ${if (allowReadOnly) "on" else "off"}")
                    if (CLIResponse.parse(response) is CLIResponse.Ok) {
                        _uiState.update { it.copy(originalAllowReadOnly = allowReadOnly) }
                    } else {
                        allSucceeded = false
                    }
                }

                if (allSucceeded) {
                    flashRoomAccessSuccess()
                    return@launch
                } else {
                    settings.setErrorMessage(UiText.of(R.string.nodeset_some_failed))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                settings.setErrorMessage(error.toUiText(UiText.of(R.string.nodeset_room_access_failed)))
            }

            _uiState.update { it.copy(isApplyingRoomAccess = false) }
        }
    }

    private suspend fun flashRoomAccessSuccess() {
        _uiState.update { it.copy(isApplyingRoomAccess = false, roomAccessApplySuccess = true) }
        delay(NodeSettingsViewModel.SUCCESS_FLASH_DURATION_MS)
        _uiState.update { it.copy(roomAccessApplySuccess = false) }
    }

    // MARK: - Behavior Fetch/Apply

    fun fetchBehaviorSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBehavior = true, behaviorError = false) }
            var hadTimeout = false

            try {
                val response = settings.sendAndWait("get advert.interval")
                val parsed = CLIResponse.parse(response, "get advert.interval")
                if (parsed is CLIResponse.AdvertInterval) {
                    _uiState.update { it.copy(advertIntervalMinutes = parsed.minutes, originalAdvertIntervalMinutes = parsed.minutes) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteNodeError.Timeout) {
                hadTimeout = true
            } catch (error: Exception) {
                // Non-timeout failures are silently ignored, matching Swift's log-only handling.
            }

            try {
                val response = settings.sendAndWait("get flood.advert.interval")
                val parsed = CLIResponse.parse(response, "get flood.advert.interval")
                if (parsed is CLIResponse.FloodAdvertInterval) {
                    _uiState.update { it.copy(floodAdvertIntervalHours = parsed.hours, originalFloodAdvertIntervalHours = parsed.hours) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteNodeError.Timeout) {
                hadTimeout = true
            } catch (error: Exception) {
                // Non-timeout failures are silently ignored, matching Swift's log-only handling.
            }

            try {
                val response = settings.sendAndWait("get flood.max")
                val parsed = CLIResponse.parse(response, "get flood.max")
                if (parsed is CLIResponse.FloodMax) _uiState.update { it.copy(floodMaxHops = parsed.hops, originalFloodMaxHops = parsed.hops) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteNodeError.Timeout) {
                hadTimeout = true
            } catch (error: Exception) {
                // Non-timeout failures are silently ignored, matching Swift's log-only handling.
            }

            _uiState.update { it.copy(isLoadingBehavior = false, behaviorError = hadTimeout) }
        }
    }

    fun applyBehaviorSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            val validation = NodeSettingsViewModel.validateBehaviorFields(state.advertIntervalMinutes, state.floodAdvertIntervalHours, state.floodMaxHops)
            _uiState.update {
                it.copy(advertIntervalError = validation.advertInterval, floodAdvertIntervalError = validation.floodInterval, floodMaxHopsError = validation.floodMaxHops)
            }
            if (validation.hasErrors) return@launch

            _uiState.update { it.copy(isApplyingBehavior = true) }
            settings.setErrorMessage(null)

            try {
                var allSucceeded = true

                val advertIntervalMinutes = state.advertIntervalMinutes
                if (advertIntervalMinutes != null && advertIntervalMinutes != state.originalAdvertIntervalMinutes) {
                    val response = settings.sendAndWait("set advert.interval $advertIntervalMinutes")
                    if (CLIResponse.parse(response) is CLIResponse.Ok) {
                        _uiState.update { it.copy(originalAdvertIntervalMinutes = advertIntervalMinutes) }
                    } else {
                        allSucceeded = false
                    }
                }

                val floodAdvertIntervalHours = state.floodAdvertIntervalHours
                if (floodAdvertIntervalHours != null && floodAdvertIntervalHours != state.originalFloodAdvertIntervalHours) {
                    val response = settings.sendAndWait("set flood.advert.interval $floodAdvertIntervalHours")
                    if (CLIResponse.parse(response) is CLIResponse.Ok) {
                        _uiState.update { it.copy(originalFloodAdvertIntervalHours = floodAdvertIntervalHours) }
                    } else {
                        allSucceeded = false
                    }
                }

                val floodMaxHops = state.floodMaxHops
                if (floodMaxHops != null && floodMaxHops != state.originalFloodMaxHops) {
                    val response = settings.sendAndWait("set flood.max $floodMaxHops")
                    if (CLIResponse.parse(response) is CLIResponse.Ok) {
                        _uiState.update { it.copy(originalFloodMaxHops = floodMaxHops) }
                    } else {
                        allSucceeded = false
                    }
                }

                if (allSucceeded) {
                    flashBehaviorSuccess()
                    return@launch
                } else {
                    settings.setErrorMessage(UiText.of(R.string.nodeset_some_failed))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                settings.setErrorMessage(error.toUiText(UiText.of(R.string.nodeset_behavior_failed)))
            }

            _uiState.update { it.copy(isApplyingBehavior = false) }
        }
    }

    private suspend fun flashBehaviorSuccess() {
        _uiState.update { it.copy(isApplyingBehavior = false, behaviorApplySuccess = true) }
        delay(NodeSettingsViewModel.SUCCESS_FLASH_DURATION_MS)
        _uiState.update { it.copy(behaviorApplySuccess = false) }
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val sessionId: UUID,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RoomSettingsViewModel(connectionManager, sessionId) as T
    }
}
