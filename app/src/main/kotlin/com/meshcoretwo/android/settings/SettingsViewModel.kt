// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.protocol.AutoAddConfig
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.DisconnectReason
import com.meshcoretwo.services.connection.connectViaWiFi
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.disconnect
import com.meshcoretwo.services.connection.forgetDevice
import com.meshcoretwo.services.connection.removeStaleNodes
import com.meshcoretwo.services.connection.removeUnfavoritedNodes
import com.meshcoretwo.services.connection.settingsService
import com.meshcoretwo.services.connection.updateDevice
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.notifications.NotificationPreferences
import com.meshcoretwo.services.notifications.NotificationPreferencesStore
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.region.RadioPresets
import com.meshcoretwo.services.region.RadioPresets.RadioPreset
import com.meshcoretwo.services.settings.AdvertLocationPolicy
import com.meshcoretwo.services.settings.AutoAddMode
import com.meshcoretwo.services.settings.DeviceGPSState
import com.meshcoretwo.services.settings.DevicePreferenceStore
import com.meshcoretwo.services.settings.GPSSource
import com.meshcoretwo.services.settings.SettingsServiceError
import com.meshcoretwo.services.settings.StaleNodeCleanupPreferencesStore
import com.meshcoretwo.services.settings.TelemetryModes
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SettingsUiState {
    data object Connecting : SettingsUiState()
    data class Ready(val device: DeviceDto) : SettingsUiState()
}

/**
 * Backs the Settings screen (`SettingsScreen.kt`) — PLAN.md's Phase 5 item 6: device, notifications,
 * and now `LocationSettingsSection`'s port ([setShareLocationPublicly]/[setAutoUpdateLocation]/
 * [setGpsSource]/[setDeviceGpsEnabled]/[setManualLocation]), plus [setDefaultFloodScope] (Phase 5
 * slice 32's `DefaultFloodScopeSection` port — an unrelated "flood scope" concept, not to be
 * confused with the geographic region step from onboarding). Persisting
 * `AppViewModel.regionSelection` and offering *that* region concept here is still a separate
 * follow-up ticket. Same
 * imperative-refetch shape as [com.meshcoretwo.android.contacts.ContactsListViewModel]: reload on
 * entering [DeviceConnectionState.READY] plus an explicit [refresh].
 *
 * Device-settings writes ([renameDevice]/[applyPreset]/[setBlePin]) follow the pattern
 * [com.meshcoretwo.services.connection.updateDevice]'s own doc comment anticipates ("called by
 * device-settings services after a local change succeeds"): apply the change via
 * [com.meshcoretwo.services.settings.SettingsService]'s verified setter, then patch just the
 * fields that setter is documented to touch onto the cached [DeviceDto] and persist via
 * [com.meshcoretwo.services.connection.updateDevice] — deliberately *not* reconstructing the
 * whole row from the verified setter's [com.meshcoretwo.protocol.SelfInfo] readback, which would
 * require redoing that type's MHz/Hz-vs-kHz unit conversions here a second time for no benefit
 * (the device itself already verified the value; this is purely the local cache catching up so
 * the UI doesn't wait for the next full resync).
 */
class SettingsViewModel(
    private val connectionManager: ConnectionManager,
    private val notificationPreferencesStore: NotificationPreferencesStore,
    private val staleNodeCleanupPreferencesStore: StaleNodeCleanupPreferencesStore,
    private val devicePreferenceStore: DevicePreferenceStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Connecting)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val notificationPreferences: StateFlow<NotificationPreferences> = notificationPreferencesStore.preferences
    val staleNodeCleanupThresholdDays: StateFlow<Int> = staleNodeCleanupPreferencesStore.thresholdDays
    val staleNodeCleanupLastRun: StateFlow<Instant?> = staleNodeCleanupPreferencesStore.lastCleanup

    /** Device-side GPS support/enabled state — `null` until [refresh]'s async read-back completes. Ported from `LocationSettingsSection`'s `deviceHasGPS`/`deviceGPSEnabled`. */
    private val _deviceGpsState = MutableStateFlow<DeviceGPSState?>(null)
    val deviceGpsState: StateFlow<DeviceGPSState?> = _deviceGpsState.asStateFlow()

    private val _autoUpdateLocation = MutableStateFlow(false)
    val autoUpdateLocation: StateFlow<Boolean> = _autoUpdateLocation.asStateFlow()

    private val _gpsSource = MutableStateFlow(GPSSource.PHONE)
    val gpsSource: StateFlow<GPSSource> = _gpsSource.asStateFlow()

    private val _errorMessage = MutableStateFlow<UiText?>(null)
    val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

    private val _statusMessage = MutableStateFlow<UiText?>(null)
    val statusMessage: StateFlow<UiText?> = _statusMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    init {
        viewModelScope.launch {
            connectionManager.connectionStateEvents.collect { state ->
                if (state == DeviceConnectionState.READY) refresh() else _uiState.value = SettingsUiState.Connecting
            }
        }
    }

    fun refresh() {
        val device = connectionManager.connectedDeviceRecord
        _uiState.value = if (connectionManager.connectionState == DeviceConnectionState.READY && device != null) {
            loadLocationPreferences(device.id)
            SettingsUiState.Ready(device)
        } else {
            SettingsUiState.Connecting
        }
    }

    /**
     * Ported from `LocationSettingsSection.loadPreferences`/`loadDeviceGPSState`. The phone-side
     * prefs load synchronously (plain `SharedPreferences` reads); the device-side GPS read is a BLE
     * round trip, so it runs as a background [runAction]-free fetch — a failure here just leaves
     * [deviceGpsState] `null`/unsupported rather than surfacing an error snackbar on every screen
     * entry, matching Swift's own `catch { deviceHasGPS = false }`.
     */
    private fun loadLocationPreferences(deviceId: UUID) {
        _autoUpdateLocation.value = devicePreferenceStore.isAutoUpdateLocationEnabled(deviceId)
        _gpsSource.value = devicePreferenceStore.gpsSource(deviceId)
        viewModelScope.launch {
            val settingsService = connectionManager.settingsService ?: return@launch
            val state = try {
                settingsService.getDeviceGPSState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeviceGPSState(isSupported = false, isEnabled = false)
            }
            _deviceGpsState.value = state
            if (state.isEnabled && !devicePreferenceStore.hasSetGpsSource(deviceId)) {
                devicePreferenceStore.setGpsSource(GPSSource.DEVICE, deviceId)
                _gpsSource.value = GPSSource.DEVICE
            }
        }
    }

    fun renameDevice(name: String) = runAction {
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        val info = settingsService.setNodeNameVerified(name)
        patchDevice { it.copy(nodeName = info.name) }
        _statusMessage.value = UiText.of(R.string.settings_msg_renamed)
    }

    fun applyPreset(preset: RadioPreset) = runAction {
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        settingsService.applyRadioPresetVerified(preset)
        patchDevice {
            it.copy(
                frequency = preset.frequencyKHz,
                bandwidth = preset.bandwidthHz,
                spreadingFactor = preset.spreadingFactor,
                codingRate = preset.codingRate,
                appliedRadioPresetID = preset.id,
                pathHashMode = if (it.supportsPathHashMode) preset.pathHashMode ?: it.pathHashMode else it.pathHashMode,
            )
        }
        _statusMessage.value = UiText.of(R.string.settings_msg_preset_applied, preset.name)
    }

    fun setBlePin(pin: UInt) = runAction {
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        settingsService.setBlePin(pin)
        patchDevice { it.copy(blePin = pin) }
        _statusMessage.value = UiText.of(R.string.settings_msg_pin_updated)
    }

    fun setPathHashMode(mode: UByte) = runAction {
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        settingsService.setPathHashModeVerified(mode)
        patchDevice { it.copy(pathHashMode = mode) }
        _statusMessage.value = UiText.of(R.string.settings_msg_path_hash_updated)
    }

    /**
     * Applies (or clears, for `name == null`) the device's persisted default flood-routing scope.
     * Ported from `DefaultFloodScopeSection.apply(name:)` — same non-retrying error path as
     * [setMultiAcks]; Swift's `retryAlert`/`dismiss()`-on-give-up isn't ported here either.
     */
    fun setDefaultFloodScope(name: String?) = runAction {
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        val actual = settingsService.setDefaultFloodScopeVerified(name)
        patchDevice { it.copy(defaultFloodScopeName = actual) }
        _statusMessage.value = if (actual != null) UiText.of(R.string.settings_msg_scope_set, actual) else UiText.of(R.string.settings_msg_scope_disabled)
    }

    /**
     * Ported from `TelemetrySettingsSection.saveTelemetry`. Like the Swift version, callers pass
     * only the field(s) actually changing — the rest are carried over from the cached device —
     * since [com.meshcoretwo.services.settings.SettingsService.setOtherParamsVerified] writes all
     * three telemetry fields as one packed byte. Same non-retrying error path as [setMultiAcks];
     * Swift's `retryAlert`/`dismiss()`-on-give-up isn't ported here either.
     */
    fun setTelemetry(base: UByte? = null, location: UByte? = null, environment: UByte? = null) = runAction {
        val device = connectionManager.connectedDeviceRecord ?: throw NotConnectedException
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        val modes = TelemetryModes(
            base = base ?: device.telemetryModeBase,
            location = location ?: device.telemetryModeLocation,
            environment = environment ?: device.telemetryModeEnvironment,
        )
        settingsService.setOtherParamsVerified(device, telemetryModes = modes)
        patchDevice {
            it.copy(
                telemetryModeBase = modes.base,
                telemetryModeLocation = modes.location,
                telemetryModeEnvironment = modes.environment,
            )
        }
        _statusMessage.value = UiText.of(R.string.settings_msg_telemetry)
    }

    /**
     * Ported from `NodesSettingsSection.applySettings`. Unlike Swift's single combined "Apply"
     * button covering every field on that screen, callers here apply one field at a time —
     * matching how [setTelemetry] already handles a multi-field packed setting — so each parameter
     * defaults to the cached device's current value when omitted. [mode] alone determines
     * [com.meshcoretwo.services.settings.SettingsService.setOtherParamsVerified]'s `autoAddContacts`
     * write (`manualAddContacts = true` for [AutoAddMode.MANUAL]/[AutoAddMode.SELECTED_TYPES],
     * `false` only for [AutoAddMode.ALL]); the type/overwrite bitmask and max-hops write only
     * happens when [com.meshcoretwo.services.persistence.DeviceDto.supportsAutoAddConfig] (older
     * firmware only has the manual/all toggle) — same v1.12+ guard as Swift's `applySettings`.
     */
    fun setAutoAddSettings(
        mode: AutoAddMode? = null,
        contacts: Boolean? = null,
        repeaters: Boolean? = null,
        roomServers: Boolean? = null,
        overwriteOldest: Boolean? = null,
        maxHops: UByte? = null,
    ) = runAction {
        val device = connectionManager.connectedDeviceRecord ?: throw NotConnectedException
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException

        val newMode = mode ?: device.autoAddMode
        val manualAdd = newMode != AutoAddMode.ALL
        settingsService.setOtherParamsVerified(device, autoAddContacts = !manualAdd)

        var bitmask = device.autoAddConfig
        var hops = device.autoAddMaxHops
        if (device.supportsAutoAddConfig) {
            var config: UByte = 0u
            if (overwriteOldest ?: device.overwriteOldest) config = config or AutoAddConfig.OVERWRITE_OLDEST_BIT
            if (newMode == AutoAddMode.SELECTED_TYPES) {
                if (contacts ?: device.autoAddContacts) config = config or AutoAddConfig.CONTACTS_BIT
                if (repeaters ?: device.autoAddRepeaters) config = config or AutoAddConfig.REPEATERS_BIT
                if (roomServers ?: device.autoAddRoomServers) config = config or AutoAddConfig.ROOM_SERVERS_BIT
            }
            val verified = settingsService.setAutoAddConfigVerified(
                AutoAddConfig(bitmask = config, maxHops = maxHops ?: device.autoAddMaxHops),
            )
            bitmask = verified.bitmask
            hops = verified.maxHops
        }

        patchDevice { it.copy(manualAddContacts = manualAdd, autoAddConfig = bitmask, autoAddMaxHops = hops) }
        _statusMessage.value = UiText.of(R.string.settings_msg_nodes)
    }

    /**
     * Applies manual radio parameters (plus path hash mode and the repeat-mode toggle), ported from
     * `AdvancedRadioSection.applySettings`. Values are validated by [ManualRadioSettingsDialog];
     * pre-repeat bookkeeping lives in [withManualRadioSettings], shared with onboarding.
     */
    internal fun setAdvancedRadioSettings(settings: ManualRadioSettings) = runAction {
        val device = connectionManager.connectedDeviceRecord ?: throw NotConnectedException
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        settingsService.writeManualRadioSettings(settings, device)
        patchDevice { it.withManualRadioSettings(settings) }
        _statusMessage.value = UiText.of(R.string.settings_msg_radio)
    }

    /**
     * Persists the auto-delete-stale-nodes threshold (0 = disabled, otherwise 7/14/30/90 days) and,
     * when enabling or changing it while connected, runs a cleanup pass immediately — ported from
     * `StaleNodeCleanupSection`'s `onChange(of: threshold)` (Swift's `performStaleNodeCleanup(force:
     * true)`). Not ported: the automatic post-sync trigger `AppState.onDeviceSynced` normally also
     * runs (3-hour cooldown, `performStaleNodeCleanup()` with `force: false`) — this port has no
     * `ConnectionManager.onDeviceSynced` wiring at the app-state level yet (the callback exists on
     * [ConnectionManager] but nothing assigns it), so cleanup only happens on an explicit threshold
     * change here, never silently in the background after a routine reconnect.
     */
    fun setStaleNodeCleanupThreshold(days: Int) {
        staleNodeCleanupPreferencesStore.setThresholdDays(days)
        if (days <= 0) return
        runAction {
            val result = connectionManager.removeStaleNodes(days)
            staleNodeCleanupPreferencesStore.recordCleanupRun()
            _statusMessage.value = if (result.total > 0) {
                UiText.of(R.string.settings_msg_stale_removed, result.removed, result.total)
            } else {
                UiText.of(R.string.settings_msg_stale_none)
            }
        }
    }

    /** Ported from `DirectMessagesSettingsSection.saveMultiAcks`. Unlike its Swift retry-alert-on-failure UI, failures surface through the same error snackbar as every other setter here (see [renameDevice]) — no dedicated retry path, matching how [setPathHashMode] already handles this class of error. */
    fun setMultiAcks(count: UByte) = runAction {
        val device = connectionManager.connectedDeviceRecord ?: throw NotConnectedException
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        settingsService.setOtherParamsVerified(device, multiAcks = count)
        patchDevice { it.copy(multiAcks = count) }
        _statusMessage.value = UiText.of(R.string.settings_msg_acks)
    }

    /**
     * Ported from `LocationSettingsSection.updateShareLocation`/`selectedAdvertLocationPolicy`.
     * When enabling, picks [AdvertLocationPolicy.SHARE] (live device GPS) if auto-update is on with
     * [GPSSource.DEVICE] and the device actually has GPS, otherwise [AdvertLocationPolicy.PREFS]
     * (the last value written via [setManualLocation]/device GPS); disabling always writes
     * [AdvertLocationPolicy.NONE]. Same non-retrying error path as [setMultiAcks].
     */
    fun setShareLocationPublicly(share: Boolean) = runAction {
        val device = connectionManager.connectedDeviceRecord ?: throw NotConnectedException
        applySharePolicy(device, share)
        _statusMessage.value = if (share) UiText.of(R.string.settings_msg_share_on) else UiText.of(R.string.settings_msg_share_off)
    }

    private suspend fun applySharePolicy(device: DeviceDto, share: Boolean) {
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        val policy = sharePolicy(share)
        if (device.advertLocationPolicyMode == policy) return
        settingsService.setOtherParamsVerified(device, advertLocationPolicy = policy)
        patchDevice { it.copy(advertLocationPolicy = policy.rawValue) }
    }

    /** Re-applies the current share policy after something that could change its eligibility (auto-update/GPS-source/device-GPS toggles) — a no-op unless sharing is already on. */
    private suspend fun reapplySharePolicyIfNeeded() {
        val device = connectionManager.connectedDeviceRecord ?: return
        if (!device.advertLocationPolicyMode.isEnabled) return
        applySharePolicy(device, share = true)
    }

    private fun sharePolicy(share: Boolean): AdvertLocationPolicy {
        if (!share) return AdvertLocationPolicy.NONE
        val deviceHasGps = _deviceGpsState.value?.isSupported == true
        return if (_autoUpdateLocation.value && deviceHasGps && _gpsSource.value == GPSSource.DEVICE) {
            AdvertLocationPolicy.SHARE
        } else {
            AdvertLocationPolicy.PREFS
        }
    }

    /**
     * Ported from `LocationSettingsSection.handleAutoUpdateChange`. Simplified relative to Swift:
     * no phone-permission "denied → open system Settings" alert flow — [locationProvider] can't
     * distinguish "not yet asked" from "denied" and can't itself launch a permission request (see
     * its own doc comment), so this just refuses with an error message when [GPSSource.PHONE] isn't
     * authorized, the same simplification [com.meshcoretwo.android.map.MapViewModel]'s "center on
     * me" button already makes for the same reason.
     */
    fun setAutoUpdateLocation(enabled: Boolean, locationProvider: LocationProvider) = runAction {
        val device = connectionManager.connectedDeviceRecord ?: throw NotConnectedException
        if (enabled && _gpsSource.value == GPSSource.PHONE && !locationProvider.isAuthorized) {
            _errorMessage.value = UiText.of(R.string.settings_err_location_permission)
            return@runAction
        }
        devicePreferenceStore.setAutoUpdateLocationEnabled(enabled, device.id)
        _autoUpdateLocation.value = enabled
        if (_gpsSource.value == GPSSource.DEVICE) {
            setDeviceGpsEnabledInternal(enabled)
        }
        reapplySharePolicyIfNeeded()
        _statusMessage.value = if (enabled) UiText.of(R.string.settings_msg_autoupdate_on) else UiText.of(R.string.settings_msg_autoupdate_off)
    }

    /** Ported from `LocationSettingsSection.handleGPSSourceChange`. Same permission-refusal simplification as [setAutoUpdateLocation]. */
    fun setGpsSource(source: GPSSource, locationProvider: LocationProvider) = runAction {
        val device = connectionManager.connectedDeviceRecord ?: throw NotConnectedException
        if (source == GPSSource.PHONE && !locationProvider.isAuthorized) {
            _errorMessage.value = UiText.of(R.string.settings_err_location_permission)
            return@runAction
        }
        devicePreferenceStore.setGpsSource(source, device.id)
        _gpsSource.value = source
        if (_autoUpdateLocation.value) {
            setDeviceGpsEnabledInternal(source == GPSSource.DEVICE)
        }
        reapplySharePolicyIfNeeded()
        _statusMessage.value = UiText.of(R.string.settings_msg_gps_source)
    }

    /** Ported from `LocationSettingsSection`'s `deviceGPSBinding`/`updateDeviceGPSToggle`. Turning it off while auto-update was following it also turns auto-update off, matching Swift's `shouldDisableAutoUpdate`. */
    fun setDeviceGpsEnabled(enabled: Boolean) = runAction {
        val device = connectionManager.connectedDeviceRecord ?: throw NotConnectedException
        setDeviceGpsEnabledInternal(enabled)
        if (!enabled && _autoUpdateLocation.value && _gpsSource.value == GPSSource.DEVICE) {
            _autoUpdateLocation.value = false
            devicePreferenceStore.setAutoUpdateLocationEnabled(false, device.id)
            reapplySharePolicyIfNeeded()
        }
        _statusMessage.value = if (enabled) UiText.of(R.string.settings_msg_gps_on) else UiText.of(R.string.settings_msg_gps_off)
    }

    private suspend fun setDeviceGpsEnabledInternal(enabled: Boolean) {
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        _deviceGpsState.value = settingsService.setDeviceGPSEnabledVerified(enabled)
    }

    /**
     * Sets a manual device location, ported from `LocationPickerView.forLocalDevice`'s `onSave`.
     * [com.meshcoretwo.services.settings.SettingsService.setManualLocationVerified] already turns
     * off device GPS first when it was on; this additionally mirrors that back into
     * [autoUpdateLocation]/[gpsSource] preferences and downgrades an active
     * [AdvertLocationPolicy.SHARE] to [AdvertLocationPolicy.PREFS] (a manual pin should not be
     * silently overwritten by the next device GPS fix), same as Swift.
     */
    fun setManualLocation(latitude: Double, longitude: Double) = runAction {
        val device = connectionManager.connectedDeviceRecord ?: throw NotConnectedException
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        val wasDeviceGpsAutoUpdate = _autoUpdateLocation.value && _gpsSource.value == GPSSource.DEVICE
        settingsService.setManualLocationVerified(latitude, longitude)
        patchDevice { it.copy(latitude = latitude, longitude = longitude) }
        if (wasDeviceGpsAutoUpdate) {
            _autoUpdateLocation.value = false
            devicePreferenceStore.setAutoUpdateLocationEnabled(false, device.id)
        }
        _deviceGpsState.value = _deviceGpsState.value?.copy(isEnabled = false)
        if (connectionManager.connectedDeviceRecord?.advertLocationPolicyMode == AdvertLocationPolicy.SHARE) {
            applySharePolicy(connectionManager.connectedDeviceRecord!!, share = true)
        }
        _statusMessage.value = UiText.of(R.string.settings_msg_location_set)
    }

    /**
     * Reconnects over WiFi at a new host/port, ported from `WiFiEditSheet.saveChanges()`: unlike
     * the other setters above (a write to the already-connected device), changing the WiFi
     * address means talking to a *different* endpoint, so this disconnects first — matching
     * Swift's `appState.disconnect(reason: .wifiAddressChange)` before `connectViaWiFi`. On
     * success [connectViaWiFi] itself persists the new `wifiHost`/`wifiPort` onto the device row
     * (see `ConnectionManagerSession.kt`'s `buildServicesAndSaveDeviceImpl`), and the
     * `connectionStateEvents` collector in [init] refreshes [uiState] once `READY` — no manual
     * [patchDevice] needed here, unlike [renameDevice]/[applyPreset]/[setBlePin].
     */
    fun updateWifiConnection(host: String, port: Int) = runAction {
        connectionManager.disconnect(DisconnectReason.WIFI_ADDRESS_CHANGE)
        connectionManager.connectViaWiFi(host = host, port = port, forceFullSync = true)
        _statusMessage.value = UiText.of(R.string.settings_msg_wifi)
    }

    /**
     * Deletes every non-favorite contact from the device and app, along with their messages.
     * Ported from `DangerZoneViewModel.removeUnfavoritedNodes`, simplified: Swift fetches
     * [com.meshcoretwo.services.connection.unfavoritedNodeCount] first and shows a count-specific
     * confirmation dialog (with a "none found" alert when the count is 0) before removing — this
     * port's confirmation dialog (`DangerZoneSection`'s `ConfirmDialog`) shows a static warning
     * instead, the same simplification already applied to [reboot]/[factoryReset]'s confirmations,
     * so the result (including "0 of 0") is only ever reported via [_statusMessage] after the fact
     * rather than pre-empted with a dedicated empty-state dialog. [forgetDevice] no longer belongs
     * to this group — its dialog now mirrors Swift's two-button keep/delete choice.
     */
    fun removeUnfavoritedNodes() = runAction {
        val result = connectionManager.removeUnfavoritedNodes()
        _statusMessage.value = if (result.total > 0) {
            UiText.of(R.string.settings_msg_nonfav_removed, result.removed, result.total)
        } else {
            UiText.of(R.string.settings_msg_nonfav_none)
        }
    }

    /**
     * Imports an expanded (64-byte) Ed25519 private key onto the device, replacing its identity —
     * shared by both "Import Key" (`ImportKeyViewModel.importKey`) and "Regenerate Identity"
     * (`RegenerateIdentityViewModel.replaceIdentity`), which independently duplicate the same two
     * calls in Swift; ported here as one function since both dialogs already funnel into
     * [SettingsScreen.kt]'s shared confirm-then-replace flow.
     *
     * Unlike Swift's `refreshDeviceInfo()` afterward (which just updates the cached self info in
     * place), this always ends with [com.meshcoretwo.services.connection.forgetDevice]: this
     * port's device-record lookup on reconnect matches by public key alone (no BLE-peripheral
     * fallback the way Swift has — see `ConnectionManagerSession.kt`'s "Identifier translation"
     * doc), so once the public key changes, the next reconnect would silently create a *new*
     * device row and orphan every contact/channel/message under the old one. Forgetting
     * immediately — the same outcome [factoryReset] already produces for the same underlying
     * reason (it also rotates keys) — makes that explicit instead of leaving a stale, unreachable
     * row behind.
     */
    fun importPrivateKey(key: ByteArray) = runAction {
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        settingsService.importPrivateKey(key)
        connectionManager.forgetDevice()
        _statusMessage.value = UiText.of(R.string.settings_msg_identity_replaced)
    }

    /** Power-cycles the radio. A timeout/disconnect right after sending the command is expected — the device reboots before it can answer — so that failure mode is swallowed, matching `DangerZoneViewModel.swift`'s comment on the same race. */
    fun reboot() = runAction {
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        try {
            settingsService.reboot()
        } catch (e: SettingsServiceError) {
            // Expected: device reboots before sending an OK response.
        }
        _statusMessage.value = UiText.of(R.string.settings_msg_reboot_sent)
    }

    /**
     * Wipes the device's node identity (new keys) and always cleans up the local pairing
     * afterward, matching `DangerZoneViewModel.factoryReset()` — a timeout here is the normal
     * case (see [reboot]'s doc), not a failure worth surfacing.
     */
    fun factoryReset() = runAction {
        val settingsService = connectionManager.settingsService ?: throw NotConnectedException
        try {
            settingsService.factoryReset()
            delay(RESET_REBOOT_GRACE_PERIOD_MS)
        } catch (e: SettingsServiceError) {
            // Expected: device reboots before sending an OK response.
        }
        connectionManager.forgetDevice()
        _statusMessage.value = UiText.of(R.string.settings_msg_reset_forgotten)
    }

    /**
     * Unpairs without resetting the radio itself. [deleteData] mirrors
     * `DangerZoneSection.swift`'s two-button confirmation dialog ("Forget Device" vs "Forget Device
     * & Delete Data") — `false` demotes the device to a ghost instead of deleting its history, so a
     * later reconnect (same radio, or the same identity restored via config import) reattaches it
     * (see [com.meshcoretwo.services.connection.forgetDevice]'s doc).
     */
    fun forgetDevice(deleteData: Boolean) = runAction {
        connectionManager.forgetDevice(deleteData)
        _statusMessage.value = if (deleteData) UiText.of(R.string.settings_msg_forgotten) else UiText.of(R.string.settings_msg_forgotten_kept)
    }

    fun clearMessages() {
        _errorMessage.value = null
        _statusMessage.value = null
    }

    private suspend fun patchDevice(transform: (DeviceDto) -> DeviceDto) {
        val device = connectionManager.connectedDeviceRecord ?: return
        var updated = transform(device)
        // Drop a stale catalog id once live RF no longer matches it. Repeat Mode keeps the id so
        // the community name still paints after it ends. Ported from `updateDevice(from:)`'s
        // stale-id branch (upstream `74ad7911`).
        val applied = updated.appliedRadioPresetID
        if (applied != null && !updated.clientRepeat &&
            RadioPresets.matchingPresets(updated.frequency, updated.bandwidth, updated.spreadingFactor, updated.codingRate).none { it.id == applied }
        ) {
            updated = updated.copy(appliedRadioPresetID = null)
        }
        connectionManager.updateDevice(updated)
        _uiState.value = SettingsUiState.Ready(updated)
    }

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotConnectedException) {
                _errorMessage.value = UiText.of(R.string.preset_not_connected)
            } catch (e: SettingsServiceError) {
                _errorMessage.value = e.toUiText(UiText.of(R.string.settings_err_generic))
            } catch (e: Exception) {
                _errorMessage.value = e.toUiText(UiText.of(R.string.settings_err_generic))
            } finally {
                _isBusy.value = false
            }
        }
    }

    private object NotConnectedException : Exception("Not connected to a device.")

    class Factory(
        private val connectionManager: ConnectionManager,
        private val notificationPreferencesStore: NotificationPreferencesStore,
        private val staleNodeCleanupPreferencesStore: StaleNodeCleanupPreferencesStore,
        private val devicePreferenceStore: DevicePreferenceStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(connectionManager, notificationPreferencesStore, staleNodeCleanupPreferencesStore, devicePreferenceStore) as T
    }

    private companion object {
        const val RESET_REBOOT_GRACE_PERIOD_MS = 2_000L
    }
}
