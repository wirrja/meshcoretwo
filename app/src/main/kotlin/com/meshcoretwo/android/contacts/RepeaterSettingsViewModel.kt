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
import com.meshcoretwo.services.connection.repeaterAdminService
import com.meshcoretwo.services.remotenode.CLIResponse
import com.meshcoretwo.services.remotenode.RemoteNodeError
import com.meshcoretwo.services.settings.RegionNameValidator
import com.meshcoretwo.services.utilities.isAtLeastVersion
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One row of a repeater's known-regions list. Firmware dumps regions as an indented tree
 * (`region` CLI reply), not a flat alphabet — [parentName]/[depth] preserve that shape so the
 * settings list can render it instead of re-sorting into a flat list. Ported from
 * `RepeaterRegionEntry.swift`.
 */
data class RepeaterRegionEntry(
    val name: String,
    /** Null only for the wildcard root. A child of the wildcard root stores [RepeaterSettingsViewModel.WILDCARD_NAME], not null. */
    val parentName: String?,
    val depth: Int,
    val floodAllowed: Boolean,
    val isHome: Boolean,
) {
    val isWildcard: Boolean get() = name == RepeaterSettingsViewModel.WILDCARD_NAME

    /** [parentName], but null instead of [RepeaterSettingsViewModel.WILDCARD_NAME] for a top-level region. */
    val namedParent: String? get() = parentName?.takeIf { it != RepeaterSettingsViewModel.WILDCARD_NAME }

    /** Where a newly added region attaches — the wildcard root, or an existing named region. */
    sealed class Parent {
        object Wildcard : Parent()
        data class Named(val name: String) : Parent()
    }
}

data class RepeaterSettingsUiState(
    // Behavior
    val advertIntervalMinutes: Int? = null,
    val floodAdvertIntervalHours: Int? = null,
    val floodMaxHops: Int? = null,
    val repeaterEnabled: Boolean? = null,
    val originalAdvertIntervalMinutes: Int? = null,
    val originalFloodAdvertIntervalHours: Int? = null,
    val originalFloodMaxHops: Int? = null,
    val originalRepeaterEnabled: Boolean? = null,
    val isLoadingBehavior: Boolean = false,
    val behaviorError: Boolean = false,
    val advertIntervalError: UiText? = null,
    val floodAdvertIntervalError: UiText? = null,
    val floodMaxHopsError: UiText? = null,
    val behaviorApplySuccess: Boolean = false,
    val isBehaviorExpanded: Boolean = false,

    // Regions
    val regions: List<RepeaterRegionEntry> = emptyList(),
    val originalRegions: List<RepeaterRegionEntry>? = null,
    val isLoadingRegions: Boolean = false,
    val regionsError: Boolean = false,
    val hasUnsavedRegionChanges: Boolean = false,
    val regionsSaveSuccess: Boolean = false,
    /** Unset when null. Scopes flood traffic this node originates, not which regions it repeats. */
    val defaultScopeName: String? = null,
    /** False until a `region default` reply parses. Distinct from `defaultScopeName == null`. */
    val defaultScopeLoaded: Boolean = false,
    val isRegionsExpanded: Boolean = false,
) {
    val behaviorLoaded: Boolean get() = repeaterEnabled != null || advertIntervalMinutes != null

    val behaviorSettingsModified: Boolean
        get() = (repeaterEnabled != null && repeaterEnabled != originalRepeaterEnabled) ||
            (advertIntervalMinutes != null && advertIntervalMinutes != originalAdvertIntervalMinutes) ||
            (floodAdvertIntervalHours != null && floodAdvertIntervalHours != originalFloodAdvertIntervalHours) ||
            (floodMaxHops != null && floodMaxHops != originalFloodMaxHops)

    val regionsLoaded: Boolean get() = originalRegions != null
}

/**
 * Backs the repeater settings screen (not yet built — see PLAN.md's Node Settings epic, sub-slice
 * 4). Ported from `RepeaterSettingsViewModel.swift`, third sub-slice of the epic, built on the
 * shared [NodeSettingsViewModel] (sub-slice 2): adds repeater-only behavior settings (repeat mode,
 * advert/flood intervals, flood max hops) and region management on top of it.
 *
 * A real [ViewModel] (unlike [settings], which stays a plain embedded helper — see that class's
 * doc): this is the actual screen owner, matching [RepeaterStatusViewModel]'s constructor shape
 * and [ViewModelProvider.Factory] pattern.
 *
 * Swift's `makeNodeCLISendClosure` has no port here: it hands a raw-command closure to
 * `NodeCLIViewModel`, but this port's [NodeCLIViewModel] already resolves
 * [ConnectionManager.repeaterAdminService] itself from `(connectionManager, sessionId, isRoom)`
 * rather than taking an injected closure, so there is nothing for a factory method to produce.
 *
 * Swift's `cleanup()` (called from the view's `.onDisappear`) becomes [onCleared] — this port's
 * idiomatic teardown hook — instead of a method the screen must remember to call.
 */
class RepeaterSettingsViewModel(
    private val connectionManager: ConnectionManager,
    private val sessionId: UUID,
) : ViewModel() {
    /** Shared device info/identity/radio/contact info/security/device-action logic. */
    val settings = NodeSettingsViewModel()

    private val _uiState = MutableStateFlow(RepeaterSettingsUiState())
    val uiState: StateFlow<RepeaterSettingsUiState> = _uiState.asStateFlow()

    private var isLoadingNodeInfo = false

    private val repeaterAdminService get() = connectionManager.repeaterAdminService

    init {
        viewModelScope.launch {
            val session = connectionManager.remoteNodeService?.fetchSession(sessionId) ?: return@launch
            val repeaterAdminService = repeaterAdminService ?: return@launch

            settings.configure(
                session = session,
                sendCommand = { id, cmd, timeoutMs -> repeaterAdminService.sendCommand(id, cmd, timeoutMs) },
                sendRawCommand = { id, cmd, timeoutMs -> repeaterAdminService.sendRawCommand(id, cmd, timeoutMs) },
            )
            settings.setName(session.name)
            settings.onPreFetchNodeInfo = { fetchNodeInfo() }

            registerBehaviorLateRecovery()

            repeaterAdminService.setCLIHandler { message, _ -> settings.handleCommonLateResponse(message.text) }

            fetchNodeInfo()
        }
    }

    override fun onCleared() {
        repeaterAdminService?.setCLIHandler { _, _ -> }
    }

    /** Pre-fetches firmware version/name/owner info via the binary protocol, ahead of any CLI round-trip. */
    private suspend fun fetchNodeInfo() {
        if (isLoadingNodeInfo) return
        val session = settings.uiState.value.session ?: return
        val repeaterAdminService = repeaterAdminService ?: return
        isLoadingNodeInfo = true
        try {
            val response = repeaterAdminService.requestOwnerInfo(session.id)
            settings.setNodeInfo(firmwareVersion = response.firmwareVersion, name = response.nodeName, ownerInfo = response.ownerInfo)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Logged only in Swift; the CLI fallback path still covers device info/identity/contact info.
        } finally {
            isLoadingNodeInfo = false
        }
    }

    // MARK: - Late Reply Recovery

    private fun behaviorSectionComplete(state: RepeaterSettingsUiState): Boolean =
        state.originalRepeaterEnabled != null && state.originalAdvertIntervalMinutes != null &&
            state.originalFloodAdvertIntervalHours != null && state.originalFloodMaxHops != null

    private fun registerBehaviorLateRecovery() {
        settings.registerLateRecovery("get repeat") { value ->
            if (value !is CLIResponse.RepeatMode) return@registerLateRecovery
            _uiState.update { state ->
                val updated = state.copy(repeaterEnabled = value.enabled, originalRepeaterEnabled = value.enabled)
                updated.copy(behaviorError = !behaviorSectionComplete(updated))
            }
        }
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

    fun setRepeaterEnabled(value: Boolean?) {
        _uiState.update { it.copy(repeaterEnabled = value) }
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

    fun setBehaviorExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isBehaviorExpanded = expanded) }
    }

    fun setRegionsExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isRegionsExpanded = expanded) }
    }

    // MARK: - Behavior Fetch/Apply

    fun fetchBehaviorSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBehavior = true, behaviorError = false) }
            var hadTimeout = false

            try {
                val response = settings.sendAndWait("get repeat")
                val parsed = CLIResponse.parse(response, "get repeat")
                if (parsed is CLIResponse.RepeatMode) _uiState.update { it.copy(repeaterEnabled = parsed.enabled, originalRepeaterEnabled = parsed.enabled) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteNodeError.Timeout) {
                hadTimeout = true
            } catch (error: Exception) {
                // Non-timeout failures are silently ignored, matching Swift's log-only handling.
            }

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

            settings.setApplying(true)

            try {
                var allSucceeded = true

                val repeaterEnabled = state.repeaterEnabled
                if (repeaterEnabled != null && repeaterEnabled != state.originalRepeaterEnabled) {
                    val response = settings.sendAndWait("set repeat ${if (repeaterEnabled) "on" else "off"}")
                    if (CLIResponse.parse(response) is CLIResponse.Ok) {
                        _uiState.update { it.copy(originalRepeaterEnabled = repeaterEnabled) }
                    } else {
                        allSucceeded = false
                    }
                }

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

            settings.setApplying(false)
        }
    }

    private suspend fun flashBehaviorSuccess() {
        settings.setApplying(false)
        _uiState.update { it.copy(behaviorApplySuccess = true) }
        delay(NodeSettingsViewModel.SUCCESS_FLASH_DURATION_MS)
        _uiState.update { it.copy(behaviorApplySuccess = false) }
    }

    // MARK: - Region Methods

    /**
     * `region default` exists on MeshCore repeater firmware v1.15.0+; older firmware doesn't
     * understand the command. Unknown firmware version is treated as unsupported. Ported from
     * `RepeaterSettingsViewModel.supportsRegionDefaultScope`.
     */
    fun supportsRegionDefaultScope(): Boolean =
        settings.uiState.value.firmwareVersion?.isAtLeastVersion(major = 1, minor = 15) == true

    fun fetchRegions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRegions = true, regionsError = false) }

            try {
                val treeResponse = settings.sendAndWait("region", timeoutMs = REGION_TIMEOUT_MS, rawMatching = true)
                val parsed = parseRegionTree(treeResponse)
                if (parsed.isEmpty()) {
                    _uiState.update { it.copy(originalRegions = null, regionsError = true) }
                } else {
                    _uiState.update { it.copy(regions = parsed, originalRegions = parsed) }
                    if (supportsRegionDefaultScope()) {
                        try {
                            val defaultReply = settings.sendAndWait("region default", timeoutMs = REGION_TIMEOUT_MS, rawMatching = true)
                            val parsedScope = parseDefaultScopeReply(defaultReply)
                            if (parsedScope != null) applyParsedDefaultScope(parsedScope)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            // Default scope is best-effort; the region list above already loaded.
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteNodeError.Timeout) {
                _uiState.update { it.copy(regionsError = true) }
            } catch (error: Exception) {
                // Non-timeout failures are silently ignored, matching Swift's log-only handling.
            }

            _uiState.update { it.copy(isLoadingRegions = false) }
        }
    }

    private fun applyParsedDefaultScope(parsed: ParsedDefaultScope) {
        _uiState.update {
            it.copy(defaultScopeName = if (parsed is ParsedDefaultScope.Named) parsed.name else null, defaultScopeLoaded = true)
        }
    }

    fun toggleRegionFlood(name: String) {
        viewModelScope.launch {
            val index = _uiState.value.regions.indexOfFirst { it.name == name }
            if (index == -1) return@launch
            val currentlyAllowed = _uiState.value.regions[index].floodAllowed
            val command = if (currentlyAllowed) "region denyf $name" else "region allowf $name"

            settings.setApplying(true)

            try {
                val response = settings.sendAndWait(command)
                if (CLIResponse.parse(response) is CLIResponse.Ok) {
                    _uiState.update { state ->
                        val updatedIndex = state.regions.indexOfFirst { it.name == name }
                        if (updatedIndex == -1) return@update state
                        val regions = state.regions.toMutableList()
                        regions[updatedIndex] = regions[updatedIndex].copy(floodAllowed = !currentlyAllowed)
                        state.copy(regions = regions, hasUnsavedRegionChanges = true)
                    }
                } else {
                    settings.setErrorMessage(UiText.of(R.string.nodeset_unknown_region))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                settings.setErrorMessage(error.toUiText(UiText.of(R.string.nodeset_region_toggle_failed)))
            }

            settings.setApplying(false)
        }
    }

    fun setDefaultScope(name: String?) {
        viewModelScope.launch {
            if (!supportsRegionDefaultScope()) return@launch
            if (name == WILDCARD_NAME) return@launch
            if (name == _uiState.value.defaultScopeName) return@launch

            val argument = name ?: FIRMWARE_NULL_TOKEN
            settings.setApplying(true)

            try {
                val response = settings.sendAndWait("region default $argument", rawMatching = true)
                if (response.contains(DEFAULT_SCOPE_SET_REPLY_MARKER)) {
                    _uiState.update { state ->
                        val regions = if (name != null) {
                            val index = state.regions.indexOfFirst { it.name == name }
                            if (index == -1) {
                                state.regions
                            } else {
                                state.regions.toMutableList().also { it[index] = it[index].copy(floodAllowed = true) }
                            }
                        } else {
                            state.regions
                        }
                        state.copy(defaultScopeName = name, defaultScopeLoaded = true, regions = regions)
                    }
                } else {
                    settings.setErrorMessage(UiText.of(R.string.nodeset_unknown_region))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                settings.setErrorMessage(error.toUiText(UiText.of(R.string.nodeset_scope_failed)))
            }

            settings.setApplying(false)
        }
    }

    fun addRegion(name: String, parent: RepeaterRegionEntry.Parent) {
        viewModelScope.launch {
            val trimmed = name.trim()
            val validationError = RegionNameValidator.validate(trimmed, _uiState.value.regions.map { it.name })
            if (validationError != null) {
                if (validationError != RegionNameValidator.ValidationError.Empty) settings.setErrorMessage(UiText.of(R.string.nodeset_region_add_failed))
                return@launch
            }

            val command: String
            val parentName: String
            when (parent) {
                is RepeaterRegionEntry.Parent.Wildcard -> {
                    command = "region put $trimmed"
                    parentName = WILDCARD_NAME
                }
                is RepeaterRegionEntry.Parent.Named -> {
                    if (_uiState.value.regions.none { it.name == parent.name }) {
                        settings.setErrorMessage(UiText.of(R.string.nodeset_region_add_failed))
                        return@launch
                    }
                    command = "region put $trimmed ${parent.name}"
                    parentName = parent.name
                }
            }

            settings.setApplying(true)

            try {
                val response = settings.sendAndWait(command)
                if (CLIResponse.parse(response) is CLIResponse.Ok) {
                    _uiState.update { state ->
                        val liveParentIndex = state.regions.indexOfFirst { it.name == parentName }
                        val depth = if (liveParentIndex != -1) state.regions[liveParentIndex].depth + 1 else 1
                        val newEntry = RepeaterRegionEntry(name = trimmed, parentName = parentName, depth = depth, floodAllowed = true, isHome = false)
                        val regions = state.regions.toMutableList()
                        if (liveParentIndex != -1) {
                            val parentDepth = regions[liveParentIndex].depth
                            var insertIndex = liveParentIndex + 1
                            while (insertIndex < regions.size && regions[insertIndex].depth > parentDepth) insertIndex++
                            regions.add(insertIndex, newEntry)
                        } else {
                            regions.add(newEntry)
                        }
                        state.copy(regions = regions, hasUnsavedRegionChanges = true)
                    }
                } else {
                    settings.setErrorMessage(UiText.of(R.string.nodeset_region_add_failed))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                settings.setErrorMessage(error.toUiText(UiText.of(R.string.nodeset_region_add_failed)))
            }

            settings.setApplying(false)
        }
    }

    fun removeRegion(name: String) {
        viewModelScope.launch {
            settings.setApplying(true)
            val wasDefault = _uiState.value.defaultScopeName == name

            try {
                val response = settings.sendAndWait("region remove $name")
                when {
                    CLIResponse.parse(response) is CLIResponse.Ok -> {
                        _uiState.update { it.copy(regions = it.regions.filterNot { region -> region.name == name }, hasUnsavedRegionChanges = true) }
                        if (wasDefault && supportsRegionDefaultScope()) {
                            val clearReply = settings.sendAndWait("region default $FIRMWARE_NULL_TOKEN", rawMatching = true)
                            if (clearReply.contains(DEFAULT_SCOPE_SET_REPLY_MARKER)) {
                                _uiState.update { it.copy(defaultScopeName = null) }
                            } else {
                                settings.setErrorMessage(UiText.of(R.string.nodeset_unknown_region))
                            }
                        }
                    }
                    response.contains("not empty") -> settings.setErrorMessage(UiText.of(R.string.nodeset_region_children))
                    else -> settings.setErrorMessage(UiText.of(R.string.nodeset_region_remove_failed))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                settings.setErrorMessage(error.toUiText(UiText.of(R.string.nodeset_region_remove_failed)))
            }

            settings.setApplying(false)
        }
    }

    fun saveRegions() {
        viewModelScope.launch {
            settings.setApplying(true)

            try {
                val response = settings.sendAndWait("region save")
                if (CLIResponse.parse(response) is CLIResponse.Ok) {
                    _uiState.update { it.copy(hasUnsavedRegionChanges = false) }
                    flashRegionsSaveSuccess()
                    return@launch
                } else {
                    settings.setErrorMessage(UiText.of(R.string.nodeset_regions_save_failed))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                settings.setErrorMessage(error.toUiText(UiText.of(R.string.nodeset_regions_save_failed)))
            }

            settings.setApplying(false)
        }
    }

    private suspend fun flashRegionsSaveSuccess() {
        settings.setApplying(false)
        _uiState.update { it.copy(regionsSaveSuccess = true) }
        delay(NodeSettingsViewModel.SUCCESS_FLASH_DURATION_MS)
        _uiState.update { it.copy(regionsSaveSuccess = false) }
    }

    /** Unset (`<null>` or `*`) versus a named region. */
    sealed class ParsedDefaultScope {
        object Cleared : ParsedDefaultScope()
        data class Named(val name: String) : ParsedDefaultScope()
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val sessionId: UUID,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RepeaterSettingsViewModel(connectionManager, sessionId) as T
    }

    companion object {
        const val WILDCARD_NAME = "*"

        /** CLI argument when default scope is unset (`region default <null>`). */
        private const val FIRMWARE_NULL_TOKEN = "<null>"

        /** GET substring. SET replies use [DEFAULT_SCOPE_SET_REPLY_MARKER], which also matches this. */
        private const val DEFAULT_SCOPE_REPLY_MARKER = "default scope is"
        private const val DEFAULT_SCOPE_SET_REPLY_MARKER = "default scope is now"

        private const val REGION_TIMEOUT_MS = 10_000L

        /** `RegionMap.exportTo(reply, 160)` yields at most 159 UTF-8 bytes plus a trailing NUL. */
        const val FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES = 159

        /**
         * Parses a `region` CLI reply into its indented tree, one space of leading indent per
         * depth level. Rejects (returns empty) anything that looks like a truncated or malformed
         * dump — a region name with an embedded space, a line indented more than one level past
         * its predecessor, or a dump that doesn't end in a newline or looks saturated at the
         * firmware's max payload size — rather than rendering a corrupted tree. Ported from
         * `RepeaterSettingsViewModel.parseRegionTree`.
         */
        fun parseRegionTree(response: String): List<RepeaterRegionEntry> {
            // Firmware ends each region with LF; a truncated dump's last character isn't LF.
            if (response.toByteArray(Charsets.UTF_8).size >= FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES) return emptyList()
            if (response.isEmpty() || response.last() != '\n') return emptyList()

            val entries = mutableListOf<RepeaterRegionEntry>()
            val stack = mutableListOf<String>()
            val lines = response.split(Regex("\r\n|\n")).filter { it.isNotEmpty() }

            for (line in lines) {
                val depth = line.takeWhile { it == ' ' }.length
                var text = line.substring(depth)
                if (text.isEmpty()) return emptyList()

                val floodAllowed: Boolean
                if (text.endsWith(" F")) {
                    floodAllowed = true
                    text = text.dropLast(2)
                } else {
                    floodAllowed = false
                }

                val isHome: Boolean
                if (text.endsWith("^")) {
                    isHome = true
                    text = text.dropLast(1)
                } else {
                    isHome = false
                }

                if (text.isEmpty() || text.contains(' ')) return emptyList()
                if (depth > stack.size) return emptyList()

                while (stack.size > depth) stack.removeAt(stack.size - 1)
                stack.add(text)
                val parentName = if (depth == 0) null else stack[depth - 1]

                entries.add(RepeaterRegionEntry(name = text, parentName = parentName, depth = depth, floodAllowed = floodAllowed, isHome = isHome))
            }

            return entries
        }

        /** Null is an unparsed reply, not an unset scope. */
        fun parseDefaultScopeReply(response: String): ParsedDefaultScope? {
            val lines = response.split("\n").filter { it.isNotEmpty() }
            val last = lines.lastOrNull() ?: return null
            var line = last.trim()
            if (line.startsWith(">")) line = line.substring(1).trim()
            if (!line.lowercase().contains(DEFAULT_SCOPE_REPLY_MARKER)) return null

            val tokens = line.split(" ").filter { it.isNotEmpty() }
            val lastToken = tokens.lastOrNull() ?: return null
            // Firmware treats a default of `*` as unset, same as `<null>`.
            if (lastToken == FIRMWARE_NULL_TOKEN || lastToken == WILDCARD_NAME) return ParsedDefaultScope.Cleared
            return ParsedDefaultScope.Named(lastToken)
        }
    }
}
