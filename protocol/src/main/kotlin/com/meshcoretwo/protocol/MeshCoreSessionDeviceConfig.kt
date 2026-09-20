// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

// MARK: - Device Info Commands

/**
 * Sends the app-start command to initialize communication with the device. Typically called
 * automatically by [MeshCoreSession.start].
 *
 * @throws MeshCoreError.Timeout if the device doesn't emit `selfInfo`.
 */
internal suspend fun MeshCoreSession.sendAppStartImpl(): SelfInfo {
    val data = PacketBuilder.appStart(configuration.clientIdentifier)
    return sendAndWait(data) { event -> (event as? MeshEvent.SelfInfoEvent)?.info }
}

/** Queries the device for its capabilities and system information. */
internal suspend fun MeshCoreSession.queryDeviceImpl(): DeviceCapabilities {
    val data = PacketBuilder.deviceQuery()
    return sendAndWait(data) { event -> (event as? MeshEvent.DeviceInfo)?.capabilities }
}

/** Retrieves the current battery status from the device. */
internal suspend fun MeshCoreSession.getBatteryImpl(): BatteryInfo =
    sendAndWait(PacketBuilder.getBattery()) { event -> (event as? MeshEvent.Battery)?.info }

// MARK: - Device Configuration Commands

/** Gets the current device time. */
internal suspend fun MeshCoreSession.getTimeImpl(): Instant =
    sendAndWait(PacketBuilder.getTime()) { event -> (event as? MeshEvent.CurrentTime)?.time }

/** Sets the device's current time. */
internal suspend fun MeshCoreSession.setTimeImpl(date: Instant) {
    sendSimpleCommand(PacketBuilder.setTime(date))
}

/** Sets the device's advertised name (max 32 bytes UTF-8). */
internal suspend fun MeshCoreSession.setNameImpl(name: String) {
    sendSimpleCommand(PacketBuilder.setName(name))
}

/** Sets the device's GPS coordinates. */
internal suspend fun MeshCoreSession.setCoordinatesImpl(latitude: Double, longitude: Double) {
    sendSimpleCommand(PacketBuilder.setCoordinates(latitude, longitude))
}

/** Sets the radio transmission power level, in dBm (range: -9 to 30). */
internal suspend fun MeshCoreSession.setTxPowerImpl(power: Byte) {
    sendSimpleCommand(PacketBuilder.setTxPower(power))
}

/**
 * Configures radio parameters for LoRa communication.
 *
 * @param clientRepeat Whether to enable client repeat mode (v9+ firmware, omitted if `null`).
 */
internal suspend fun MeshCoreSession.setRadioImpl(
    frequency: Double,
    bandwidth: Double,
    spreadingFactor: UByte,
    codingRate: UByte,
    clientRepeat: Boolean? = null,
) {
    sendSimpleCommand(PacketBuilder.setRadio(frequency, bandwidth, spreadingFactor, codingRate, clientRepeat))
}

/** Gets the allowed frequency ranges for client repeat mode (v9+ firmware). */
internal suspend fun MeshCoreSession.getRepeatFreqImpl(): List<FrequencyRange> =
    sendAndWait(PacketBuilder.getRepeatFreq()) { event -> (event as? MeshEvent.AllowedRepeatFreq)?.ranges }

/** Configures radio timing parameters for fine-tuning. */
suspend fun MeshCoreSession.setTuning(rxDelay: UInt, af: UInt) {
    sendSimpleCommand(PacketBuilder.setTuning(rxDelay, af))
}

/**
 * Sets miscellaneous device parameters at once. Consider using the granular setters (e.g.
 * [setManualAddContacts]) instead.
 */
internal suspend fun MeshCoreSession.setOtherParamsImpl(
    manualAddContacts: Boolean,
    telemetryModeEnvironment: UByte,
    telemetryModeLocation: UByte,
    telemetryModeBase: UByte,
    advertisementLocationPolicy: UByte,
    multiAcks: UByte? = null,
) {
    sendSimpleCommand(
        PacketBuilder.setOtherParams(
            manualAddContacts,
            telemetryModeEnvironment,
            telemetryModeLocation,
            telemetryModeBase,
            advertisementLocationPolicy,
            multiAcks,
        ),
    )
}

/** Sets the device PIN for administrative access. */
internal suspend fun MeshCoreSession.setDevicePinImpl(pin: UInt) {
    sendSimpleCommand(PacketBuilder.setDevicePin(pin))
}

// MARK: - Granular Device Configuration

/** Sets the base telemetry mode (0-3), preserving other settings via read-modify-write. */
suspend fun MeshCoreSession.setTelemetryModeBase(mode: UByte) {
    mutateOtherParams { it.telemetryModeBase = (mode.toInt() and 0b11).toUByte() }
}

/** Sets the location telemetry mode (0-3), preserving other settings. */
suspend fun MeshCoreSession.setTelemetryModeLocation(mode: UByte) {
    mutateOtherParams { it.telemetryModeLocation = (mode.toInt() and 0b11).toUByte() }
}

/** Sets the environment telemetry mode (0-3), preserving other settings. */
suspend fun MeshCoreSession.setTelemetryModeEnvironment(mode: UByte) {
    mutateOtherParams { it.telemetryModeEnvironment = (mode.toInt() and 0b11).toUByte() }
}

/** Sets whether contacts discovered via advertisement must be manually approved, preserving other settings. */
suspend fun MeshCoreSession.setManualAddContacts(enabled: Boolean) {
    mutateOtherParams { it.manualAddContacts = enabled }
}

/** Sets the multi-acks retry count, preserving other settings. */
suspend fun MeshCoreSession.setMultiAcks(count: UByte) {
    mutateOtherParams { it.multiAcks = count }
}

/** Sets the advertisement location policy, preserving other settings. */
suspend fun MeshCoreSession.setAdvertisementLocationPolicy(policy: UByte) {
    mutateOtherParams { it.advertisementLocationPolicy = policy }
}

/** Gets the current auto-add configuration from the device. */
internal suspend fun MeshCoreSession.getAutoAddConfigImpl(): AutoAddConfig =
    sendAndWait(PacketBuilder.getAutoAddConfig()) { event -> (event as? MeshEvent.AutoAddConfigEvent)?.config }

/** Sets the auto-add configuration on the device. */
internal suspend fun MeshCoreSession.setAutoAddConfigImpl(config: AutoAddConfig) {
    sendSimpleCommand(PacketBuilder.setAutoAddConfig(config))
}

/**
 * Serializes a granular "other params" change against any other granular setter.
 *
 * The read-modify-write (read the current config, apply [transform], write it back) spans
 * several wire exchanges. Holding [MeshCoreSession.otherParamsSerializer] across the whole
 * sequence keeps two concurrent setters from each reading the same snapshot and reverting the
 * other's write when they store the full config back.
 */
private suspend fun MeshCoreSession.mutateOtherParams(transform: (OtherParamsConfig) -> Unit) {
    otherParamsSerializer.withSerialization {
        val config = currentOtherParams()
        transform(config)
        applyOtherParams(config)
    }
}

/** @throws MeshCoreError.SessionNotStarted if selfInfo is unavailable even after a refresh. */
private suspend fun MeshCoreSession.currentOtherParams(): OtherParamsConfig {
    selfInfo?.let { return OtherParamsConfig(it) }
    selfInfo = sendAppStartImpl()
    return selfInfo?.let { OtherParamsConfig(it) } ?: throw MeshCoreError.SessionNotStarted
}

private suspend fun MeshCoreSession.applyOtherParams(config: OtherParamsConfig) {
    setOtherParamsImpl(
        manualAddContacts = config.manualAddContacts,
        telemetryModeEnvironment = config.telemetryModeEnvironment,
        telemetryModeLocation = config.telemetryModeLocation,
        telemetryModeBase = config.telemetryModeBase,
        advertisementLocationPolicy = config.advertisementLocationPolicy,
        multiAcks = config.multiAcks,
    )
    // Refresh selfInfo to keep the cache consistent.
    selfInfo = sendAppStartImpl()
}

/** Reboots the device. The session will be disconnected. */
internal suspend fun MeshCoreSession.rebootImpl() {
    transport.send(PacketBuilder.reboot())
}

/**
 * Retrieves telemetry data from the device. When [MeshCoreSession.currentSelfInfo] is available,
 * only telemetry for the current device is accepted.
 */
internal suspend fun MeshCoreSession.getSelfTelemetryImpl(): TelemetryResponse {
    val expectedPrefix = selfInfo?.publicKey?.prefixBytes(6)
    return sendAndWait(PacketBuilder.getSelfTelemetry()) { event ->
        val response = (event as? MeshEvent.TelemetryResponseEvent)?.response ?: return@sendAndWait null
        if (expectedPrefix == null || response.publicKeyPrefix.contentEquals(expectedPrefix)) response else null
    }
}

/**
 * Retrieves all custom variables stored on the device.
 *
 * @throws MeshCoreError.DeviceError if the device rejects the request (e.g. firmware predating
 *   custom-var support), or [MeshCoreError.Timeout] if the device doesn't respond.
 */
internal suspend fun MeshCoreSession.getCustomVarsImpl(): Map<String, String> = sendAndWaitWithError(
    PacketBuilder.getCustomVars(),
    matching = { event -> (event as? MeshEvent.CustomVars)?.vars },
    errorMatcher = deviceErrorMatcher,
)

/** Sets a custom variable on the device. */
internal suspend fun MeshCoreSession.setCustomVarImpl(key: String, value: String) {
    sendSimpleCommand(PacketBuilder.setCustomVar(key, value))
}

/**
 * Exports the device's private key. A sensitive operation exposing the device's cryptographic
 * identity; the exported key can be imported into another device to clone its identity.
 *
 * @throws MeshCoreError.FeatureDisabled if export is disabled on the device, or
 *   [MeshCoreError.Timeout] if the device doesn't respond.
 */
internal suspend fun MeshCoreSession.exportPrivateKeyImpl(): ByteArray = sendAndWaitWithError(
    PacketBuilder.exportPrivateKey(),
    matching = { event -> (event as? MeshEvent.PrivateKey)?.key },
    errorMatcher = { event ->
        when (event) {
            is MeshEvent.Disabled -> MeshCoreError.FeatureDisabled
            is MeshEvent.Error -> MeshCoreError.DeviceError(event.code ?: 0u)
            else -> null
        }
    },
)

/**
 * Imports a private key into the device, replacing its cryptographic identity and refreshing
 * cached self info. Use with caution.
 *
 * @throws MeshCoreError.FeatureDisabled if the device does not support key import,
 *   [MeshCoreError.Timeout] if the device does not acknowledge the import, or
 *   [MeshCoreError.DeviceError] for a matched device error response.
 */
internal suspend fun MeshCoreSession.importPrivateKeyImpl(key: ByteArray) {
    if (key.size != PacketBuilder.PRIVATE_KEY_SIZE) {
        throw MeshCoreError.InvalidInput("Full ${PacketBuilder.PRIVATE_KEY_SIZE}-byte private key required for importPrivateKey")
    }
    val succeeded: Boolean = sendAndWaitWithError(
        PacketBuilder.importPrivateKey(key),
        matching = { event ->
            when (event) {
                is MeshEvent.Ok -> if (event.value == null) true else null
                is MeshEvent.Disabled -> false
                else -> null
            }
        },
        errorMatcher = { event -> (event as? MeshEvent.Error)?.code?.let { MeshCoreError.DeviceError(it) } },
    )
    if (!succeeded) throw MeshCoreError.FeatureDisabled
    selfInfo = sendAppStartImpl()
}

// MARK: - Stats Commands

/** Retrieves core device statistics. */
internal suspend fun MeshCoreSession.getStatsCoreImpl(): CoreStats =
    sendAndWait(PacketBuilder.getStatsCore()) { event -> (event as? MeshEvent.StatsCore)?.stats }

/** Retrieves radio statistics. */
internal suspend fun MeshCoreSession.getStatsRadioImpl(): RadioStats =
    sendAndWait(PacketBuilder.getStatsRadio()) { event -> (event as? MeshEvent.StatsRadio)?.stats }

/** Retrieves packet statistics. */
internal suspend fun MeshCoreSession.getStatsPacketsImpl(): PacketStats =
    sendAndWait(PacketBuilder.getStatsPackets()) { event -> (event as? MeshEvent.StatsPackets)?.stats }
