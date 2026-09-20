// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.remotenode.CLIResponse
import com.meshcoretwo.services.remotenode.NodeSettingsResponseParser
import com.meshcoretwo.services.remotenode.RemoteCLICommandRewriter
import com.meshcoretwo.services.remotenode.RemoteNodeError
import com.meshcoretwo.services.remotenode.RemoteOperationTimeoutPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NodeSettingsUiState(
    val session: RemoteNodeSessionDto? = null,

    // Device Info
    val firmwareVersion: String? = null,
    val deviceTimeUTC: String? = null,
    /** Positive means the node's clock is ahead of [NodeSettingsViewModel.now]. */
    val clockDrift: Long? = null,
    val isLoadingDeviceInfo: Boolean = false,
    val deviceInfoError: Boolean = false,

    // Identity
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val originalName: String? = null,
    val originalLatitude: Double? = null,
    val originalLongitude: Double? = null,
    val isLoadingIdentity: Boolean = false,
    val identityError: Boolean = false,
    val nameError: UiText? = null,
    val latitudeError: UiText? = null,
    val longitudeError: UiText? = null,

    // Radio
    val frequency: Double? = null,
    val bandwidth: Double? = null,
    val spreadingFactor: Int? = null,
    val codingRate: Int? = null,
    val isLoadingRadio: Boolean = false,
    val radioError: Boolean = false,
    val radioSettingsModified: Boolean = false,

    // Contact Info
    val ownerInfo: String? = null,
    val originalOwnerInfo: String? = null,
    val isLoadingContactInfo: Boolean = false,
    val contactInfoError: Boolean = false,

    // Security
    val newPassword: String = "",
    val confirmPassword: String = "",

    // Expansion state
    val isDeviceInfoExpanded: Boolean = false,
    val isRadioExpanded: Boolean = false,
    val isIdentityExpanded: Boolean = false,
    val isContactInfoExpanded: Boolean = false,
    val isSecurityExpanded: Boolean = false,

    // Global state
    val isApplying: Boolean = false,
    val isRebooting: Boolean = false,
    val errorMessage: UiText? = null,
    val successMessage: UiText? = null,
    val showSuccessAlert: Boolean = false,
    val identityApplySuccess: Boolean = false,
    val contactInfoApplySuccess: Boolean = false,
    val changePasswordSuccess: Boolean = false,
    val isSendingAdvert: Boolean = false,
) {
    val deviceInfoLoaded: Boolean get() = deviceTimeUTC != null

    val deviceTime: String? get() = deviceTimeUTC?.let { NodeSettingsViewModel.convertUTCToLocal(it) }

    val identityLoaded: Boolean get() = originalLatitude != null || originalLongitude != null

    val identitySettingsModified: Boolean
        get() = (name != null && name != originalName) ||
            (latitude != null && latitude != originalLatitude) ||
            (longitude != null && longitude != originalLongitude)

    val radioLoaded: Boolean get() = frequency != null

    val contactInfoLoaded: Boolean get() = originalOwnerInfo != null

    /**
     * Gated on [contactInfoLoaded] so the text field's empty pre-fetch value can't enable Apply
     * and wipe the node's owner info before the current value arrives.
     */
    val contactInfoSettingsModified: Boolean get() = contactInfoLoaded && ownerInfo != originalOwnerInfo

    /** Counts UTF-16 code units, not grapheme clusters like Swift's `String.count` — display-only, not wire-accurate. */
    val ownerInfoCharCount: Int get() = (ownerInfo ?: "").length

    val isOwnerInfoTooLong: Boolean get() = ownerInfoCharCount > NodeSettingsViewModel.OWNER_INFO_MAX_LENGTH
}

/**
 * Shared logic for repeater and room settings screens: device info, identity, radio, contact
 * info, security, device actions, and late-reply recovery. Ported from
 * `NodeSettingsViewModel.swift` — second sub-slice of the Node Settings epic (PLAN.md), built on
 * the already-ported [NodeSettingsResponseParser]/[RemoteCLICommandRewriter]/[CLIResponse].
 *
 * A plain class, not an [androidx.lifecycle.ViewModel] itself: on iOS this is a bare `@Observable`
 * helper embedded as a field (`RepeaterSettingsViewModel.helper`/`RoomSettingsViewModel.helper`),
 * not a screen owner in its own right. The next sub-slice's `RepeaterSettingsViewModel`/
 * `RoomSettingsViewModel` are expected to embed this the same way and drive its suspend functions
 * from their own `viewModelScope`. State is exposed as a single [StateFlow] — this codebase's
 * established pattern (e.g. [RepeaterStatusViewModel]) — rather than Swift's per-field
 * `@Observable` vars.
 *
 * Swift's `NodeSettingsError.noService` collapses onto the already-established
 * [RemoteNodeError.SessionNotFound] rather than a new error type, matching every other
 * RemoteNodes app-layer class ([NodeCLIViewModel] etc.) throwing it for the same "no admin
 * service configured" condition.
 *
 * Field setters ([setName], [setFrequency], etc.) have no Swift counterpart function — they're
 * the mechanical Kotlin translation of Swift's directly-settable `var` properties, which the View
 * layer binds to two-way there. [setFrequency]/[setBandwidth]/[setSpreadingFactor]/[setCodingRate]
 * fold in `radioSettingsModified = true`, a side effect `SharedNodeSettingsViews.swift`'s `onChange`
 * handlers set manually in Swift.
 */
class NodeSettingsViewModel {
    private val _uiState = MutableStateFlow(NodeSettingsUiState())
    val uiState: StateFlow<NodeSettingsUiState> = _uiState.asStateFlow()

    /** Reference clock for [applyDeviceTime]'s drift calculation; overridable in tests. */
    var now: () -> Instant = Instant::now

    private var sendCommandFn: (suspend (UUID, String, Long) -> String)? = null
    private var sendRawCommandFn: (suspend (UUID, String, Long) -> String)? = null

    /**
     * Called when firmware version or node info needs pre-fetching. Repeater sets this to the
     * binary `requestOwnerInfo`; Room sets this to CLI `ver`.
     */
    var onPreFetchNodeInfo: (suspend () -> Unit)? = null

    // MARK: - Configuration

    fun configure(
        session: RemoteNodeSessionDto,
        sendCommand: suspend (UUID, String, Long) -> String,
        sendRawCommand: suspend (UUID, String, Long) -> String,
    ) {
        _uiState.update { it.copy(session = session) }
        sendCommandFn = sendCommand
        sendRawCommandFn = sendRawCommand
        registerSharedLateRecovery()
    }

    /** Sets name and owner info from an external source (e.g. binary protocol pre-fetch). */
    fun setNodeInfo(firmwareVersion: String? = null, name: String? = null, ownerInfo: String? = null) {
        _uiState.update {
            it.copy(
                firmwareVersion = firmwareVersion ?: it.firmwareVersion,
                name = name ?: it.name,
                originalName = name ?: it.originalName,
                ownerInfo = ownerInfo ?: it.ownerInfo,
                originalOwnerInfo = ownerInfo ?: it.originalOwnerInfo,
            )
        }
    }

    fun cleanup() {
        sendCommandFn = null
        sendRawCommandFn = null
        onPreFetchNodeInfo = null
        unansweredQueries.clear()
        recentResponses.clear()
        lateRecoveryAppliers.clear()
    }

    // MARK: - Field Setters

    fun setName(value: String?) {
        _uiState.update { it.copy(name = value) }
    }

    fun setLatitude(value: Double?) {
        _uiState.update { it.copy(latitude = value) }
    }

    fun setLongitude(value: Double?) {
        _uiState.update { it.copy(longitude = value) }
    }

    fun setOwnerInfo(value: String?) {
        _uiState.update { it.copy(ownerInfo = value) }
    }

    fun setFrequency(value: Double?) {
        _uiState.update { it.copy(frequency = value, radioSettingsModified = true) }
    }

    fun setBandwidth(value: Double?) {
        _uiState.update { it.copy(bandwidth = value, radioSettingsModified = true) }
    }

    fun setSpreadingFactor(value: Int?) {
        _uiState.update { it.copy(spreadingFactor = value, radioSettingsModified = true) }
    }

    fun setCodingRate(value: Int?) {
        _uiState.update { it.copy(codingRate = value, radioSettingsModified = true) }
    }

    fun setNewPassword(value: String) {
        _uiState.update { it.copy(newPassword = value) }
    }

    fun setConfirmPassword(value: String) {
        _uiState.update { it.copy(confirmPassword = value) }
    }

    fun setDeviceInfoExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isDeviceInfoExpanded = expanded) }
    }

    fun setRadioExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isRadioExpanded = expanded) }
    }

    fun setIdentityExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isIdentityExpanded = expanded) }
    }

    fun setContactInfoExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isContactInfoExpanded = expanded) }
    }

    fun setSecurityExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isSecurityExpanded = expanded) }
    }

    /** Setting from Location picker. */
    fun setLocationFromPicker(latitude: Double, longitude: Double) {
        _uiState.update { it.copy(latitude = latitude, longitude = longitude) }
    }

    /**
     * Sets the shared applying/error-message fields from outside this class. Repeater/room-only
     * actions (behavior settings, regions, room access) share these two fields with this class's
     * own apply methods, matching Swift's `RepeaterSettingsViewModel`/`RoomSettingsViewModel`
     * directly mutating `helper.isApplying`/`helper.errorMessage`.
     */
    fun setApplying(value: Boolean) {
        _uiState.update { it.copy(isApplying = value) }
    }

    fun setErrorMessage(value: UiText?) {
        _uiState.update { it.copy(errorMessage = value) }
    }

    /** Dismisses the shared success alert (`showSuccessAlert`/`successMessage`). No Swift counterpart needed: SwiftUI's `.alert(isPresented:)` binding clears it automatically on dismiss. */
    fun dismissSuccessAlert() {
        _uiState.update { it.copy(showSuccessAlert = false) }
    }

    // MARK: - CLI Transport

    suspend fun sendAndWait(
        command: String,
        timeoutMs: Long = DEFAULT_CLI_TIMEOUT_MS,
        rawMatching: Boolean = false,
    ): String {
        val session = _uiState.value.session ?: throw RemoteNodeError.SessionNotFound
        val send = (if (rawMatching) sendRawCommandFn else sendCommandFn) ?: throw RemoteNodeError.SessionNotFound

        try {
            val response = send(session.id, command, timeoutMs)
            rememberSeenResponse(response)
            unansweredQueries.remove(command)
            return response
        } catch (error: RemoteNodeError.Timeout) {
            if (CLIResponse.isStructuredQuery(command)) unansweredQueries.add(command)
            throw error
        }
    }

    // MARK: - Fetch Methods

    suspend fun fetchDeviceInfo() {
        _uiState.update { it.copy(isLoadingDeviceInfo = true, deviceInfoError = false) }

        if (_uiState.value.firmwareVersion == null) onPreFetchNodeInfo?.invoke()

        if (_uiState.value.firmwareVersion == null) {
            try {
                val response = sendAndWait("ver")
                val parsed = CLIResponse.parse(response, "ver")
                if (parsed is CLIResponse.Version) _uiState.update { it.copy(firmwareVersion = parsed.text) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteNodeError.Timeout) {
                _uiState.update { it.copy(deviceInfoError = true) }
            } catch (error: Exception) {
                // Non-timeout failures are silently ignored, matching Swift's log-only handling.
            }
        }

        try {
            val response = sendAndWait("clock")
            val parsed = CLIResponse.parse(response, "clock")
            if (parsed is CLIResponse.DeviceTime) applyDeviceTime(parsed.text)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteNodeError.Timeout) {
            _uiState.update { it.copy(deviceInfoError = true) }
        } catch (error: Exception) {
            // Non-timeout failures are silently ignored, matching Swift's log-only handling.
        }

        _uiState.update { it.copy(isLoadingDeviceInfo = false) }
    }

    suspend fun fetchIdentity() {
        _uiState.update { it.copy(isLoadingIdentity = true, identityError = false) }
        var hadTimeout = false

        if (_uiState.value.originalName == null) onPreFetchNodeInfo?.invoke()

        if (_uiState.value.originalName == null) {
            try {
                val response = sendAndWait("get name")
                val parsed = CLIResponse.parse(response, "get name")
                if (parsed is CLIResponse.Name) _uiState.update { it.copy(name = parsed.text, originalName = parsed.text) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteNodeError.Timeout) {
                hadTimeout = true
            } catch (error: Exception) {
                // Non-timeout failures are silently ignored, matching Swift's log-only handling.
            }
        }

        try {
            val response = sendAndWait("get lat")
            val parsed = CLIResponse.parse(response, "get lat")
            if (parsed is CLIResponse.Latitude) _uiState.update { it.copy(latitude = parsed.degrees, originalLatitude = parsed.degrees) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteNodeError.Timeout) {
            hadTimeout = true
        } catch (error: Exception) {
            // Non-timeout failures are silently ignored, matching Swift's log-only handling.
        }

        try {
            val response = sendAndWait("get lon")
            val parsed = CLIResponse.parse(response, "get lon")
            if (parsed is CLIResponse.Longitude) _uiState.update { it.copy(longitude = parsed.degrees, originalLongitude = parsed.degrees) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteNodeError.Timeout) {
            hadTimeout = true
        } catch (error: Exception) {
            // Non-timeout failures are silently ignored, matching Swift's log-only handling.
        }

        _uiState.update { it.copy(isLoadingIdentity = false, identityError = hadTimeout) }
    }

    suspend fun fetchRadioSettings() {
        _uiState.update { it.copy(isLoadingRadio = true, radioError = false) }
        var hadTimeout = false

        try {
            val response = sendAndWait("get radio")
            val parsed = CLIResponse.parse(response, "get radio")
            if (parsed is CLIResponse.Radio) {
                _uiState.update {
                    it.copy(frequency = parsed.frequency, bandwidth = parsed.bandwidth, spreadingFactor = parsed.spreadingFactor, codingRate = parsed.codingRate)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteNodeError.Timeout) {
            hadTimeout = true
        } catch (error: Exception) {
            // Non-timeout failures are silently ignored, matching Swift's log-only handling.
        }

        _uiState.update { it.copy(isLoadingRadio = false, radioError = hadTimeout) }
    }

    suspend fun fetchContactInfo() {
        if (_uiState.value.originalOwnerInfo == null) onPreFetchNodeInfo?.invoke()
        if (_uiState.value.originalOwnerInfo != null) return

        _uiState.update { it.copy(isLoadingContactInfo = true, contactInfoError = false) }

        try {
            val response = sendAndWait("get owner.info")
            val parsed = CLIResponse.parse(response, "get owner.info")
            if (parsed is CLIResponse.OwnerInfo) {
                val displayText = NodeSettingsResponseParser.displayOwnerInfo(parsed.text)
                _uiState.update { it.copy(ownerInfo = displayText, originalOwnerInfo = displayText) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteNodeError.Timeout) {
            _uiState.update { it.copy(contactInfoError = true) }
        } catch (error: Exception) {
            // Non-timeout failures are silently ignored, matching Swift's log-only handling.
        }

        _uiState.update { it.copy(isLoadingContactInfo = false) }
    }

    // MARK: - Success Flash

    /**
     * Drops the section's applying flag and flashes its success indicator for
     * [SUCCESS_FLASH_DURATION_MS]. The transforms target the section's own state, which may live
     * on this shared view model or on the owning view model in the next sub-slice.
     */
    private suspend fun flashSuccess(
        setApplying: (NodeSettingsUiState, Boolean) -> NodeSettingsUiState,
        setSuccess: (NodeSettingsUiState, Boolean) -> NodeSettingsUiState,
    ) {
        _uiState.update { setSuccess(setApplying(it, false), true) }
        delay(SUCCESS_FLASH_DURATION_MS)
        _uiState.update { setSuccess(it, false) }
    }

    // MARK: - Apply Methods

    suspend fun applyRadioSettings() {
        val state = _uiState.value
        val frequency = state.frequency
        val bandwidth = state.bandwidth
        val spreadingFactor = state.spreadingFactor
        val codingRate = state.codingRate
        if (frequency == null || bandwidth == null || spreadingFactor == null || codingRate == null) {
            _uiState.update { it.copy(errorMessage = UiText.of(R.string.nodeset_radio_not_loaded)) }
            return
        }

        _uiState.update { it.copy(isApplying = true, errorMessage = null) }

        try {
            val response = sendAndWait("set radio $frequency,$bandwidth,$spreadingFactor,$codingRate")
            if (CLIResponse.parse(response) is CLIResponse.Ok) {
                _uiState.update {
                    it.copy(radioSettingsModified = false, successMessage = UiText.of(R.string.nodeset_radio_applied), showSuccessAlert = true)
                }
            } else {
                _uiState.update { it.copy(errorMessage = UiText.of(R.string.nodeset_radio_apply_failed)) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.toUiText(UiText.of(R.string.nodeset_radio_apply_error))) }
        }

        _uiState.update { it.copy(isApplying = false) }
    }

    suspend fun applyIdentitySettings() {
        val state = _uiState.value
        val validation = validateIdentityFields(state.name, state.latitude, state.longitude)
        _uiState.update { it.copy(nameError = validation.name, latitudeError = validation.latitude, longitudeError = validation.longitude) }
        if (validation.hasErrors) return

        _uiState.update { it.copy(isApplying = true, errorMessage = null) }

        try {
            var allSucceeded = true

            val name = state.name
            if (name != null && name != state.originalName) {
                val response = sendAndWait("set name $name")
                if (CLIResponse.parse(response) is CLIResponse.Ok) {
                    _uiState.update { it.copy(originalName = name) }
                } else {
                    allSucceeded = false
                }
            }

            val latitude = state.latitude
            if (latitude != null && latitude != state.originalLatitude) {
                val response = sendAndWait("set lat $latitude")
                if (CLIResponse.parse(response) is CLIResponse.Ok) {
                    _uiState.update { it.copy(originalLatitude = latitude) }
                } else {
                    allSucceeded = false
                }
            }

            val longitude = state.longitude
            if (longitude != null && longitude != state.originalLongitude) {
                val response = sendAndWait("set lon $longitude")
                if (CLIResponse.parse(response) is CLIResponse.Ok) {
                    _uiState.update { it.copy(originalLongitude = longitude) }
                } else {
                    allSucceeded = false
                }
            }

            if (allSucceeded) {
                flashSuccess(
                    setApplying = { s, v -> s.copy(isApplying = v) },
                    setSuccess = { s, v -> s.copy(identityApplySuccess = v) },
                )
                return
            } else {
                _uiState.update { it.copy(errorMessage = UiText.of(R.string.nodeset_some_failed)) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.toUiText(UiText.of(R.string.nodeset_identity_failed))) }
        }

        _uiState.update { it.copy(isApplying = false) }
    }

    suspend fun applyContactInfoSettings() {
        _uiState.update { it.copy(isApplying = true, errorMessage = null) }

        try {
            val pipeText = NodeSettingsResponseParser.wireOwnerInfo(_uiState.value.ownerInfo ?: "")
            val response = sendAndWait("set owner.info $pipeText")
            if (CLIResponse.parse(response) is CLIResponse.Ok) {
                _uiState.update { it.copy(originalOwnerInfo = it.ownerInfo) }
                flashSuccess(
                    setApplying = { s, v -> s.copy(isApplying = v) },
                    setSuccess = { s, v -> s.copy(contactInfoApplySuccess = v) },
                )
                return
            } else {
                _uiState.update { it.copy(errorMessage = UiText.of(R.string.nodeset_some_failed)) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.toUiText(UiText.of(R.string.nodeset_contact_failed))) }
        }

        _uiState.update { it.copy(isApplying = false) }
    }

    // MARK: - Security

    suspend fun changePassword() {
        val state = _uiState.value
        if (state.newPassword.isEmpty()) {
            _uiState.update { it.copy(errorMessage = UiText.of(R.string.nodeset_pw_empty)) }
            return
        }
        if (state.newPassword != state.confirmPassword) {
            _uiState.update { it.copy(errorMessage = UiText.of(R.string.nodeset_pw_mismatch)) }
            return
        }

        _uiState.update { it.copy(isApplying = true, errorMessage = null) }

        try {
            val response = sendAndWait("password ${state.newPassword}", rawMatching = true)
            if (NodeSettingsResponseParser.isPasswordChangeSuccessful(response)) {
                _uiState.update { it.copy(newPassword = "", confirmPassword = "") }
                flashSuccess(
                    setApplying = { s, v -> s.copy(isApplying = v) },
                    setSuccess = { s, v -> s.copy(changePasswordSuccess = v) },
                )
                return
            } else {
                _uiState.update { it.copy(errorMessage = UiText.of(R.string.nodeset_pw_failed)) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.toUiText(UiText.of(R.string.nodeset_pw_failed))) }
        }

        _uiState.update { it.copy(isApplying = false) }
    }

    // MARK: - Device Actions

    suspend fun reboot() {
        if (_uiState.value.session == null) return

        _uiState.update { it.copy(isRebooting = true, errorMessage = null) }

        try {
            // Firmware reboots without replying, so a timeout is the expected outcome.
            sendAndWait("reboot", timeoutMs = RemoteOperationTimeoutPolicy.FIRE_AND_FORGET_CLI_MS)
            _uiState.update { it.copy(successMessage = UiText.of(R.string.nodeset_reboot_sent), showSuccessAlert = true) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteNodeError.Timeout) {
            _uiState.update { it.copy(successMessage = UiText.of(R.string.nodeset_reboot_sent), showSuccessAlert = true) }
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.toUiText(UiText.of(R.string.nodeset_reboot_failed))) }
        }

        _uiState.update { it.copy(isRebooting = false) }
    }

    suspend fun forceAdvert() {
        _uiState.update { it.copy(isSendingAdvert = true) }
        try {
            sendAndWait("advert")
            _uiState.update { it.copy(successMessage = UiText.of(R.string.nodeset_advert_sent), showSuccessAlert = true) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.toUiText(UiText.of(R.string.nodeset_advert_failed))) }
        } finally {
            _uiState.update { it.copy(isSendingAdvert = false) }
        }
    }

    suspend fun syncTime() {
        _uiState.update { it.copy(isApplying = true, errorMessage = null) }

        try {
            val response = sendAndWait(RemoteCLICommandRewriter.rewrite(RemoteCLICommandRewriter.CLOCK_SYNC_COMMAND))
            when (val outcome = NodeSettingsResponseParser.classifyClockSyncResponse(response)) {
                NodeSettingsResponseParser.ClockSyncOutcome.Synced -> {
                    if (NodeSettingsResponseParser.clockResponseText(response) != null) {
                        applyDeviceTime(response)
                    } else {
                        _uiState.update { it.copy(clockDrift = null) }
                    }
                    _uiState.update { it.copy(successMessage = UiText.of(R.string.nodeset_time_synced), showSuccessAlert = true) }
                }
                NodeSettingsResponseParser.ClockSyncOutcome.ClockAhead -> {
                    _uiState.update {
                        it.copy(
                            errorMessage = UiText.of(R.string.nodeset_clock_ahead),
                        )
                    }
                }
                is NodeSettingsResponseParser.ClockSyncOutcome.Failed -> {
                    _uiState.update { it.copy(errorMessage = outcome.message.takeIf { it.isNotEmpty() }?.let(UiText::Plain) ?: UiText.of(R.string.nodeset_time_failed)) }
                }
                NodeSettingsResponseParser.ClockSyncOutcome.Unexpected -> {
                    _uiState.update { it.copy(errorMessage = UiText.of(R.string.nodeset_unexpected_response, response)) }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.toUiText(UiText.of(R.string.nodeset_time_failed))) }
        }

        _uiState.update { it.copy(isApplying = false) }
    }

    private fun applyDeviceTime(raw: String) {
        val text = NodeSettingsResponseParser.clockResponseText(raw) ?: return
        val drift = NodeSettingsResponseParser.clockDrift(text, now())
        _uiState.update { it.copy(deviceTimeUTC = text, clockDrift = drift) }
    }

    // MARK: - Late Reply Recovery

    /** Structured queries this screen sent that timed out unanswered. */
    private val unansweredQueries: MutableSet<String> = mutableSetOf()

    /** Recently observed reply texts; a mesh duplicate of an already-answered command must not be recovered for a different query. */
    private val recentResponses: MutableList<String> = mutableListOf()

    /** Appliers for recovered late replies, keyed by query. */
    private val lateRecoveryAppliers: MutableMap<String, (CLIResponse) -> Unit> = mutableMapOf()

    /** Registers a field applier for a recoverable query. Repeater and room view models add their own section fields on top of the shared ones. */
    fun registerLateRecovery(query: String, apply: (CLIResponse) -> Unit) {
        lateRecoveryAppliers[query] = apply
    }

    private fun rememberSeenResponse(response: String) {
        recentResponses.add(response)
        if (recentResponses.size > RECENT_RESPONSES_LIMIT) recentResponses.removeAt(0)
    }

    /**
     * Adopts an out-of-band CLI reply for the one unanswered query it can only belong to. The
     * reply already spent its airtime, so recovering it beats refetching; ambiguous or duplicate
     * replies are ignored.
     */
    fun handleCommonLateResponse(response: String) {
        if (recentResponses.contains(response)) return
        val (query, value) = NodeSettingsResponseParser.recoveredResponse(response, unansweredQueries) ?: return
        val apply = lateRecoveryAppliers[query] ?: return

        unansweredQueries.remove(query)
        rememberSeenResponse(response)
        apply(value)
    }

    private fun identitySectionComplete(state: NodeSettingsUiState): Boolean =
        state.originalName != null && state.originalLatitude != null && state.originalLongitude != null

    private fun registerSharedLateRecovery() {
        registerLateRecovery("get radio") { value ->
            if (value !is CLIResponse.Radio) return@registerLateRecovery
            _uiState.update {
                it.copy(frequency = value.frequency, bandwidth = value.bandwidth, spreadingFactor = value.spreadingFactor, codingRate = value.codingRate, radioError = false)
            }
        }
        registerLateRecovery("get lat") { value ->
            if (value !is CLIResponse.Latitude) return@registerLateRecovery
            _uiState.update { state ->
                val updated = state.copy(latitude = value.degrees, originalLatitude = value.degrees)
                updated.copy(identityError = !identitySectionComplete(updated))
            }
        }
        registerLateRecovery("get lon") { value ->
            if (value !is CLIResponse.Longitude) return@registerLateRecovery
            _uiState.update { state ->
                val updated = state.copy(longitude = value.degrees, originalLongitude = value.degrees)
                updated.copy(identityError = !identitySectionComplete(updated))
            }
        }
        registerLateRecovery("clock") { value ->
            if (value !is CLIResponse.DeviceTime) return@registerLateRecovery
            applyDeviceTime(value.text)
            _uiState.update { it.copy(deviceInfoError = it.firmwareVersion == null) }
        }
    }

    // MARK: - Shared Validation

    data class BehaviorValidationErrors(
        val advertInterval: UiText? = null,
        val floodInterval: UiText? = null,
        val floodMaxHops: UiText? = null,
    ) {
        val hasErrors: Boolean get() = advertInterval != null || floodInterval != null || floodMaxHops != null
    }

    data class IdentityValidationErrors(
        val name: UiText? = null,
        val latitude: UiText? = null,
        val longitude: UiText? = null,
    ) {
        val hasErrors: Boolean get() = name != null || latitude != null || longitude != null
    }

    companion object {
        /** Firmware limit on the `set owner.info` value length. */
        const val OWNER_INFO_MAX_LENGTH = 119

        private const val DEFAULT_CLI_TIMEOUT_MS = 10_000L

        /** Also used by [RepeaterSettingsViewModel]/[RoomSettingsViewModel]'s own section flashes, hence `internal` not `private`. */
        internal const val SUCCESS_FLASH_DURATION_MS = 1_500L
        private const val RECENT_RESPONSES_LIMIT = 16

        /**
         * Maximum usable bytes for names (firmware `char[32]` minus null terminator). A local copy
         * rather than a shared `ProtocolLimits` constant — none exists in this port yet; matches
         * [com.meshcoretwo.services.settings.SettingsService]'s own private copy of the same
         * firmware constant.
         */
        private const val MAX_USABLE_NAME_BYTES = 31

        /** Firmware-accepted ranges for the behavior fields; 0 means disabled for the two intervals and is validated separately. */
        private val ADVERT_INTERVAL_MINUTES_RANGE = 60..240
        private val FLOOD_INTERVAL_HOURS_RANGE = 3..168
        private val FLOOD_MAX_HOPS_RANGE = 0..64

        private val LOCAL_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        private val LOCAL_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)

        fun convertUTCToLocal(utcString: String): String {
            val instant = NodeSettingsResponseParser.utcDate(utcString) ?: return utcString
            val zoned = instant.atZone(ZoneId.systemDefault())
            return "${LOCAL_TIME_FORMATTER.format(zoned)} - ${LOCAL_DATE_FORMATTER.format(zoned)}"
        }

        fun validateBehaviorFields(advertInterval: Int?, floodInterval: Int?, floodMaxHops: Int?): BehaviorValidationErrors = BehaviorValidationErrors(
            advertInterval = if (advertInterval != null && advertInterval != 0 && advertInterval !in ADVERT_INTERVAL_MINUTES_RANGE) {
                UiText.of(R.string.nodeset_v_advert)
            } else {
                null
            },
            floodInterval = if (floodInterval != null && floodInterval != 0 && floodInterval !in FLOOD_INTERVAL_HOURS_RANGE) {
                UiText.of(R.string.nodeset_v_flood_advert)
            } else {
                null
            },
            floodMaxHops = if (floodMaxHops != null && floodMaxHops !in FLOOD_MAX_HOPS_RANGE) UiText.of(R.string.nodeset_v_max_hops) else null,
        )

        /**
         * Rejects out-of-range coordinates rather than clamping, so a mistyped value surfaces to
         * the user instead of firmware silently normalizing it. Ranges and the name byte cap
         * mirror `PacketBuilder`/`ProtocolLimits` on the Swift side; inlined here as literals
         * rather than importing `protocol`'s `PacketBuilder` (app must not reach past `services`
         * — see project constraints), matching how [RemoteNodeSessionDto.hasLocation] already inlines the
         * same lat/lon ranges.
         */
        fun validateIdentityFields(name: String?, latitude: Double?, longitude: Double?): IdentityValidationErrors = IdentityValidationErrors(
            name = if (name != null && name.toByteArray(Charsets.UTF_8).size > MAX_USABLE_NAME_BYTES) UiText.of(R.string.nodeset_v_name, MAX_USABLE_NAME_BYTES) else null,
            latitude = if (latitude != null && (!latitude.isFinite() || latitude !in -90.0..90.0)) UiText.of(R.string.nodeset_v_lat) else null,
            longitude = if (longitude != null && (!longitude.isFinite() || longitude !in -180.0..180.0)) UiText.of(R.string.nodeset_v_lon) else null,
        )
    }
}
