// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import com.meshcoretwo.protocol.AutoAddConfig
import com.meshcoretwo.protocol.BatteryInfo
import com.meshcoretwo.protocol.ConfigurationSessionOps
import com.meshcoretwo.protocol.CoreStats
import com.meshcoretwo.protocol.DeviceCapabilities
import com.meshcoretwo.protocol.FloodScope
import com.meshcoretwo.protocol.FrequencyRange
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.PacketStats
import com.meshcoretwo.protocol.RadioStats
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.protocol.utf8Prefix
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.region.RadioPresets.RadioPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant
import kotlin.math.abs

/**
 * Service for managing device settings via the MeshCore session: radio configuration, node
 * identity, Bluetooth PIN, telemetry/location-sharing policy, stats, custom variables, and key
 * management. Ported from `SettingsService.swift` + `SettingsService+Verified.swift`.
 *
 * **Deferred, not yet ported:**
 * - The two `@available(*, deprecated)` boolean-sharing compatibility overloads of
 *   `setOtherParams`/`setOtherParamsVerified` — dead code paths even in Swift, kept there only for
 *   source compatibility with callers that predate `AdvertLocationPolicy`.
 * - `PersistentLogger` diagnostic logging throughout — this codebase's business services don't log
 *   (see `ChannelService`'s class doc); none of the dropped log calls are load-bearing.
 *
 * Event delivery uses a [MutableSharedFlow] rather than Swift's single-subscriber
 * `AsyncStream`-with-replacement-warning: that machinery exists only to work around `AsyncStream`
 * being fundamentally single-consumer, whereas [kotlinx.coroutines.flow.SharedFlow] is natively
 * multicast, so no equivalent subscriber-replacement bookkeeping is needed.
 */
class SettingsService(private val session: ConfigurationSessionOps) {
    private val eventsFlow = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 64)

    /** Stream of settings change events. */
    fun events(): Flow<SettingsEvent> = eventsFlow

    // MARK: - Radio Settings

    /**
     * Sets radio parameters manually.
     *
     * Both numeric parameters are integer values divided by 1000 before being forwarded to
     * [ConfigurationSessionOps.setRadio]. Pass values in the same scaled-integer form a radio
     * preset's `frequencyKHz`/`bandwidthHz` would use:
     * - [frequencyKHz]: frequency expressed in kHz (e.g. 869618 -> 869.618 MHz on the wire)
     * - [bandwidthKHz]: bandwidth expressed in Hz (e.g. 62500 -> 62.5 kHz on the wire), despite the name
     */
    /** Applies a radio preset to the device. */
    suspend fun applyRadioPreset(preset: RadioPreset) {
        setRadioParams(
            frequencyKHz = preset.frequencyKHz,
            bandwidthKHz = preset.bandwidthHz,
            spreadingFactor = preset.spreadingFactor,
            codingRate = preset.codingRate,
        )
        applyPresetPathHashIfNeeded(preset)
    }

    /** Writes the preset's path hash size after RF, skipping firmware without `CMD_SET_PATH_HASH_MODE` (below v10). */
    private suspend fun applyPresetPathHashIfNeeded(preset: RadioPreset) {
        val mode = preset.pathHashMode ?: return
        if (!queryDevice().supportsPathHashMode) return
        setPathHashMode(mode)
    }

    suspend fun setRadioParams(
        frequencyKHz: UInt,
        bandwidthKHz: UInt,
        spreadingFactor: UByte,
        codingRate: UByte,
        clientRepeat: Boolean? = null,
    ) {
        try {
            session.setRadio(
                frequency = frequencyKHz.toDouble() / 1000.0,
                bandwidth = bandwidthKHz.toDouble() / 1000.0,
                spreadingFactor = spreadingFactor,
                codingRate = codingRate,
                clientRepeat = clientRepeat,
            )
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Sets the transmit power. */
    suspend fun setTxPower(power: Byte) {
        try {
            session.setTxPower(power)
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Reads the device clock. */
    suspend fun getTime(): Instant {
        try {
            return session.getTime()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Sets the device clock. The firmware rejects moving the clock backwards. */
    suspend fun setTime(date: Instant) {
        try {
            session.setTime(date)
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    // MARK: - Node Settings

    /** Sets the publicly visible node name. */
    suspend fun setNodeName(name: String) {
        val truncated = name.utf8Prefix(MAX_USABLE_NAME_BYTES)
        try {
            session.setName(truncated)
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Sets node location (latitude/longitude in degrees). */
    suspend fun setLocation(latitude: Double, longitude: Double) {
        try {
            session.setCoordinates(latitude, longitude)
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    // MARK: - Bluetooth Settings

    /** Sets BLE PIN (0 = disabled/random, 100000-999999 = fixed PIN). */
    suspend fun setBlePin(pin: UInt) {
        try {
            session.setDevicePin(pin)
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    // MARK: - Other Settings

    /** Sets other device parameters (contacts, telemetry, location policy). */
    suspend fun setOtherParams(
        autoAddContacts: Boolean,
        telemetryModes: TelemetryModes,
        advertLocationPolicy: AdvertLocationPolicy,
        multiAcks: UByte,
    ) = setOtherParams(
        autoAddContacts = autoAddContacts,
        telemetryModes = telemetryModes,
        advertLocationPolicyRaw = advertLocationPolicy.rawValue,
        multiAcks = multiAcks,
    )

    /**
     * Sets other device parameters, taking the advertisement location policy as a raw byte.
     *
     * Used by config import so a policy value not modeled by [AdvertLocationPolicy] (e.g. from
     * newer firmware) is forwarded to the device verbatim instead of being coerced.
     */
    suspend fun setOtherParams(
        autoAddContacts: Boolean,
        telemetryModes: TelemetryModes,
        advertLocationPolicyRaw: UByte,
        multiAcks: UByte,
    ) {
        try {
            session.setOtherParams(
                manualAddContacts = !autoAddContacts,
                telemetryModeEnvironment = telemetryModes.environment,
                telemetryModeLocation = telemetryModes.location,
                telemetryModeBase = telemetryModes.base,
                advertisementLocationPolicy = advertLocationPolicyRaw,
                multiAcks = multiAcks,
            )
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    // MARK: - Factory Reset

    /** Performs a factory reset on the device. */
    suspend fun factoryReset() {
        try {
            session.factoryReset()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Reboots the device. */
    suspend fun reboot() {
        try {
            session.reboot()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    // MARK: - Device Info

    /** Fetches battery and storage information from the device. */
    suspend fun getBattery(): BatteryInfo {
        try {
            return session.getBattery()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Queries device capabilities. */
    suspend fun queryDevice(): DeviceCapabilities {
        try {
            return session.queryDevice()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Gets self info by sending appStart. */
    suspend fun getSelfInfo(): SelfInfo {
        try {
            return session.sendAppStart()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    // MARK: - Auto-Add Config

    /** Gets auto-add configuration from the device. */
    suspend fun getAutoAddConfig(): AutoAddConfig {
        try {
            return session.getAutoAddConfig()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Refreshes auto-add config from the device (for initial load) and notifies observers. */
    suspend fun refreshAutoAddConfig() {
        val config = getAutoAddConfig()
        eventsFlow.emit(SettingsEvent.AutoAddConfigUpdated(config))
    }

    // MARK: - Repeat Frequency Ranges

    /** Gets allowed repeat frequency ranges from the device. */
    private suspend fun getRepeatFreq(): List<FrequencyRange> {
        try {
            return session.getRepeatFreq()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Refreshes allowed repeat frequency ranges from the device and notifies observers. */
    suspend fun refreshRepeatFreqRanges() {
        val ranges = getRepeatFreq()
        eventsFlow.emit(SettingsEvent.AllowedRepeatFreqUpdated(ranges))
    }

    /**
     * Refreshes device info from the device and notifies observers. Use this instead of
     * [setLocationVerified] when the device already has correct coordinates (e.g. from its own GPS).
     */
    suspend fun refreshDeviceInfo() {
        val selfInfo = getSelfInfo()
        eventsFlow.emit(SettingsEvent.DeviceUpdated(selfInfo))
    }

    /** Sets auto-add configuration on the device. */
    suspend fun setAutoAddConfig(config: AutoAddConfig) {
        try {
            session.setAutoAddConfig(config)
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Sets auto-add configuration with verification. */
    suspend fun setAutoAddConfigVerified(config: AutoAddConfig): AutoAddConfig {
        setAutoAddConfig(config)

        val actualConfig = getAutoAddConfig()
        if (actualConfig != config) {
            throw SettingsServiceError.VerificationFailed(
                expectedValue = "bitmask=${config.bitmask}, maxHops=${config.maxHops}",
                actualValue = "bitmask=${actualConfig.bitmask}, maxHops=${actualConfig.maxHops}",
            )
        }

        eventsFlow.emit(SettingsEvent.AutoAddConfigUpdated(actualConfig))
        return actualConfig
    }

    // MARK: - Path Hash Mode

    /** Sets the path hash mode on the device (0=1-byte, 1=2-byte, 2=3-byte hashes). */
    suspend fun setPathHashMode(mode: UByte) {
        try {
            session.setPathHashMode(mode)
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Sets the path hash mode with verification via [queryDevice]. Returns the verified mode. */
    suspend fun setPathHashModeVerified(mode: UByte): UByte {
        setPathHashMode(mode)

        val capabilities = queryDevice()
        if (capabilities.pathHashMode != mode) {
            throw SettingsServiceError.VerificationFailed(
                expectedValue = "pathHashMode=$mode",
                actualValue = "pathHashMode=${capabilities.pathHashMode}",
            )
        }

        eventsFlow.emit(SettingsEvent.PathHashModeUpdated(mode))
        return mode
    }

    // MARK: - Default Flood Scope

    /**
     * Fetches the device's persisted default flood scope.
     *
     * Requires firmware v11+; older firmware rejects the opcode and surfaces
     * [SettingsServiceError.SessionError] wrapping a [MeshCoreError.DeviceError].
     *
     * @return The persisted scope name, or `null` when none is configured.
     */
    suspend fun getDefaultFloodScope(): String? {
        try {
            val scope = session.getDefaultFloodScope()
            val name = scope?.name
            eventsFlow.emit(SettingsEvent.DefaultFloodScopeUpdated(name))
            return name
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /**
     * Persists the device's default flood scope and verifies via a follow-up read.
     *
     * Passing `null` for [name] clears the persisted scope. Non-null names are sent as
     * [FloodScope.Region] — firmware derives the key and stores both. Names are truncated to
     * [MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES] UTF-8 bytes before both key derivation and send, so the
     * stored display and derived scope key agree on the same byte sequence.
     *
     * @return The verified name read back from the device.
     */
    suspend fun setDefaultFloodScopeVerified(name: String?): String? {
        val expected = if (!name.isNullOrEmpty()) name.utf8Prefix(MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES) else null
        try {
            if (expected != null) {
                session.setDefaultFloodScope(expected, FloodScope.Region(expected))
            } else {
                session.setDefaultFloodScope("", FloodScope.Disabled)
            }
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }

        val actual = getDefaultFloodScope()
        if (actual != expected) {
            throw SettingsServiceError.VerificationFailed(
                expectedValue = expected ?: "(cleared)",
                actualValue = actual ?: "(cleared)",
            )
        }
        return actual
    }

    // MARK: - Stats

    /** Gets core statistics. */
    suspend fun getStatsCore(): CoreStats {
        try {
            return session.getStatsCore()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Gets radio statistics. */
    suspend fun getStatsRadio(): RadioStats {
        try {
            return session.getStatsRadio()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Gets packet statistics. */
    suspend fun getStatsPackets(): PacketStats {
        try {
            return session.getStatsPackets()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    // MARK: - Custom Variables

    /** Gets custom variables from the device. */
    suspend fun getCustomVars(): Map<String, String> {
        try {
            return session.getCustomVars()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Reads the device's GPS support/enabled state from its `gps` custom variable. */
    suspend fun getDeviceGPSState(): DeviceGPSState = deviceGPSState(getCustomVars())

    /** Sets a custom variable on the device. */
    suspend fun setCustomVar(key: String, value: String) {
        try {
            session.setCustomVar(key, value)
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Enables or disables the device's own GPS, verifies via read-back, and refreshes device info. */
    suspend fun setDeviceGPSEnabledVerified(enabled: Boolean): DeviceGPSState {
        setCustomVar("gps", if (enabled) "1" else "0")

        val state = getDeviceGPSState()
        if (!state.isSupported) {
            throw SettingsServiceError.DeviceGPSVerificationFailed(expectedEnabled = enabled, actualEnabled = false)
        }
        if (state.isEnabled != enabled) {
            throw SettingsServiceError.DeviceGPSVerificationFailed(expectedEnabled = enabled, actualEnabled = state.isEnabled)
        }

        refreshDeviceInfo()
        return state
    }

    // MARK: - Private Key Management

    /** Exports the private key from the device. */
    suspend fun exportPrivateKey(): ByteArray {
        try {
            return session.exportPrivateKey()
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    /** Imports a private key to the device. */
    suspend fun importPrivateKey(key: ByteArray) {
        try {
            session.importPrivateKey(key)
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    // MARK: - Signing

    /** Signs data using the device's private key. */
    suspend fun sign(data: ByteArray): ByteArray {
        try {
            return session.sign(data)
        } catch (error: MeshCoreError) {
            throw SettingsServiceError.SessionError(error)
        }
    }

    // MARK: - Verified Methods (ported from SettingsService+Verified.swift)

    /** Sets the node name with verification. Returns the verified self info for UI update. */
    suspend fun setNodeNameVerified(name: String): SelfInfo {
        val truncated = name.utf8Prefix(MAX_USABLE_NAME_BYTES)
        setNodeName(truncated)

        val selfInfo = getSelfInfo()
        if (selfInfo.name != truncated) {
            throw SettingsServiceError.VerificationFailed(expectedValue = truncated, actualValue = selfInfo.name)
        }

        eventsFlow.emit(SettingsEvent.DeviceUpdated(selfInfo))
        return selfInfo
    }

    /** Sets location with verification. */
    suspend fun setLocationVerified(latitude: Double, longitude: Double): SelfInfo {
        // Calculate the scaled values we're actually sending.
        val scaledLatSent = (latitude * 1_000_000).toInt()
        val scaledLonSent = (longitude * 1_000_000).toInt()

        setLocation(latitude, longitude)

        // Read back and compare at scaled integer level for precise diagnostics.
        val selfInfo = getSelfInfo()
        val scaledLatReceived = (selfInfo.latitude * 1_000_000).toInt()
        val scaledLonReceived = (selfInfo.longitude * 1_000_000).toInt()

        val latDiff = abs(scaledLatSent - scaledLatReceived)
        val lonDiff = abs(scaledLonSent - scaledLonReceived)

        // Tolerance of 2 scaled units (~0.2m) handles floating-point conversion.
        val tolerance = 2
        if (latDiff > tolerance || lonDiff > tolerance) {
            val expectedLat = scaledLatSent / 1_000_000.0
            val expectedLon = scaledLonSent / 1_000_000.0
            throw SettingsServiceError.VerificationFailed(
                expectedValue = "($expectedLat, $expectedLon)",
                actualValue = "(${selfInfo.latitude}, ${selfInfo.longitude})",
            )
        }

        eventsFlow.emit(SettingsEvent.DeviceUpdated(selfInfo))
        return selfInfo
    }

    /** Sets a manual location, turning off device GPS first when needed so the value persists. */
    suspend fun setManualLocationVerified(latitude: Double, longitude: Double): SelfInfo {
        val gpsState = getDeviceGPSState()
        if (gpsState.isSupported && gpsState.isEnabled) {
            setDeviceGPSEnabledVerified(false)
        }
        return setLocationVerified(latitude, longitude)
    }

    /**
     * Sets radio parameters with verification.
     *
     * Same unit conventions as [setRadioParams] — [frequencyKHz] is in kHz (869618 -> 869.618 MHz)
     * and [bandwidthKHz] is in Hz (62500 -> 62.5 kHz) despite the suffix.
     */
    suspend fun setRadioParamsVerified(
        frequencyKHz: UInt,
        bandwidthKHz: UInt,
        spreadingFactor: UByte,
        codingRate: UByte,
        clientRepeat: Boolean? = null,
    ): SelfInfo {
        setRadioParams(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate, clientRepeat)

        val selfInfo = getSelfInfo()

        val expectedFreqMHz = frequencyKHz.toDouble() / 1000.0
        val expectedBwMHz = bandwidthKHz.toDouble() / 1000.0

        if (abs(selfInfo.radioFrequency - expectedFreqMHz) >= 0.001 ||
            abs(selfInfo.radioBandwidth - expectedBwMHz) >= 0.001 ||
            selfInfo.radioSpreadingFactor != spreadingFactor ||
            selfInfo.radioCodingRate != codingRate
        ) {
            throw SettingsServiceError.VerificationFailed(
                expectedValue = "freq=$frequencyKHz, bw=$bandwidthKHz, sf=$spreadingFactor, cr=$codingRate",
                actualValue = "freq=${selfInfo.radioFrequency}, bw=${selfInfo.radioBandwidth}, " +
                    "sf=${selfInfo.radioSpreadingFactor}, cr=${selfInfo.radioCodingRate}",
            )
        }

        // Verify clientRepeat via queryDevice if it was explicitly set.
        if (clientRepeat != null) {
            val capabilities = queryDevice()
            if (capabilities.clientRepeat != clientRepeat) {
                throw SettingsServiceError.VerificationFailed(
                    expectedValue = "clientRepeat=$clientRepeat",
                    actualValue = "clientRepeat=${capabilities.clientRepeat}",
                )
            }
            eventsFlow.emit(SettingsEvent.ClientRepeatUpdated(clientRepeat))
        }

        eventsFlow.emit(SettingsEvent.DeviceUpdated(selfInfo))
        return selfInfo
    }

    /**
     * Applies a radio preset with verification: RF first, then the preset's path hash size when the
     * firmware supports it (v10+). Callers persist the applied catalog id only after this returns,
     * so a failed hash write can't stamp an id over RF-equal aliases (upstream `e02cebe9`).
     */
    suspend fun applyRadioPresetVerified(preset: RadioPreset): SelfInfo {
        val selfInfo = setRadioParamsVerified(
            frequencyKHz = preset.frequencyKHz,
            bandwidthKHz = preset.bandwidthHz,
            spreadingFactor = preset.spreadingFactor,
            codingRate = preset.codingRate,
        )
        val mode = preset.pathHashMode
        if (mode != null && queryDevice().supportsPathHashMode) setPathHashModeVerified(mode)
        return selfInfo
    }

    /** Sets TX power with verification. */
    suspend fun setTxPowerVerified(power: Byte): SelfInfo {
        setTxPower(power)

        val selfInfo = getSelfInfo()
        if (selfInfo.txPower != power) {
            throw SettingsServiceError.VerificationFailed(expectedValue = "$power", actualValue = "${selfInfo.txPower}")
        }

        eventsFlow.emit(SettingsEvent.DeviceUpdated(selfInfo))
        return selfInfo
    }

    /** Sets other params with verification. */
    suspend fun setOtherParamsVerified(
        autoAddContacts: Boolean,
        telemetryModes: TelemetryModes,
        advertLocationPolicy: AdvertLocationPolicy,
        multiAcks: UByte,
    ): SelfInfo {
        setOtherParams(autoAddContacts, telemetryModes, advertLocationPolicy, multiAcks)

        val selfInfo = getSelfInfo()

        // manualAddContacts is inverted (false = auto-add enabled).
        if (selfInfo.manualAddContacts == autoAddContacts) {
            throw SettingsServiceError.VerificationFailed(
                expectedValue = "autoAdd=$autoAddContacts",
                actualValue = "autoAdd=${!selfInfo.manualAddContacts}",
            )
        }

        eventsFlow.emit(SettingsEvent.DeviceUpdated(selfInfo))
        return selfInfo
    }

    /**
     * Convenience overload: uses [device]'s current values as defaults, overriding only the
     * supplied parameters. Ported from `setOtherParamsVerified(from device:, ...)`.
     */
    suspend fun setOtherParamsVerified(
        device: DeviceDto,
        autoAddContacts: Boolean? = null,
        telemetryModes: TelemetryModes? = null,
        advertLocationPolicy: AdvertLocationPolicy? = null,
        multiAcks: UByte? = null,
    ): SelfInfo = setOtherParamsVerified(
        autoAddContacts = autoAddContacts ?: !device.manualAddContacts,
        telemetryModes = telemetryModes ?: device.telemetryModes,
        advertLocationPolicy = advertLocationPolicy ?: device.advertLocationPolicyMode,
        multiAcks = multiAcks ?: device.multiAcks,
    )

    companion object {
        /** Maximum usable bytes for names (firmware `char[32]` minus null terminator). */
        private const val MAX_USABLE_NAME_BYTES = 31

        /**
         * Maximum UTF-8 bytes for the default flood scope name field. The firmware field is 31
         * bytes with zero padding and accepts `0 < strlen(name) < 31`, so the effective cap is 30.
         * `internal` (not `private`) so [com.meshcoretwo.services.settings.RegionNameValidator]
         * shares the same limit instead of duplicating the magic number.
         */
        internal const val MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES = 30

        private fun deviceGPSState(vars: Map<String, String>): DeviceGPSState {
            val value = vars["gps"] ?: return DeviceGPSState(isSupported = false, isEnabled = false)
            return DeviceGPSState(isSupported = true, isEnabled = value == "1")
        }
    }
}
