// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.protocol.AutoAddConfig
import com.meshcoretwo.services.remotenode.OCVPreset
import com.meshcoretwo.services.settings.AdvertLocationPolicy
import com.meshcoretwo.services.settings.AutoAddMode
import com.meshcoretwo.services.settings.TelemetryModes
import com.meshcoretwo.services.utilities.isAtLeastVersion
import java.time.Instant
import java.util.UUID

/**
 * An immutable snapshot of a device — see [ContactDto]'s doc for why this crosses the DAO/store
 * boundary instead of the Room entity. Ported from `DeviceDTO` (`Device.swift`), trimmed to the
 * fields [DeviceEntity] carries (see its class doc for what's deferred).
 */
data class DeviceDto(
    val id: UUID,
    val radioID: UUID,
    val publicKey: ByteArray,
    val nodeName: String,
    val firmwareVersion: UByte,
    val firmwareVersionString: String,
    val manufacturerName: String,
    val buildDate: String,
    val maxContacts: UShort,
    val maxChannels: UByte,
    val frequency: UInt,
    val bandwidth: UInt,
    val spreadingFactor: UByte,
    val codingRate: UByte,
    val txPower: Byte,
    val maxTxPower: Byte,
    val latitude: Double,
    val longitude: Double,
    val blePin: UInt,
    val lastConnected: Instant,
    val lastContactSync: UInt,
    val isActive: Boolean,
    val ocvPreset: String?,
    val customOCVArrayString: String?,
    /** See [DeviceEntity.appliedRadioPresetID]'s doc. */
    val appliedRadioPresetID: String? = null,
    /** Configured routing path-hash size code — see [DeviceEntity.pathHashMode]'s doc. */
    val pathHashMode: UByte = 0u,
    /** The BLE MAC address last used to reach this device — see [DeviceEntity.bleAddress]'s doc. */
    val bleAddress: String? = null,
    /** The WiFi host/port last used to reach this device — see [DeviceEntity.wifiHost]'s doc. */
    val wifiHost: String? = null,
    val wifiPort: Int? = null,
    /** See [DeviceEntity.manualAddContacts]'s doc. */
    val manualAddContacts: Boolean = false,
    /** See [DeviceEntity.multiAcks]'s doc. */
    val multiAcks: UByte = 2u,
    /** See [DeviceEntity.telemetryModeBase]'s doc. */
    val telemetryModeBase: UByte = 2u,
    /** See [DeviceEntity.telemetryModeLocation]'s doc. */
    val telemetryModeLocation: UByte = 0u,
    /** See [DeviceEntity.telemetryModeEnvironment]'s doc. */
    val telemetryModeEnvironment: UByte = 0u,
    /** See [DeviceEntity.advertLocationPolicy]'s doc. Raw byte — use [autoAddMode]/[autoAddContacts]/etc. for the typed form. */
    val advertLocationPolicy: UByte = 0u,
    /** See [DeviceEntity.autoAddConfig]'s doc. Raw bitmask — use [autoAddMode]/[autoAddContacts]/etc. for the typed form. */
    val autoAddConfig: UByte = 0u,
    /** See [DeviceEntity.autoAddMaxHops]'s doc. */
    val autoAddMaxHops: UByte = 0u,
    /** See [DeviceEntity.clientRepeat]'s doc. */
    val clientRepeat: Boolean = false,
    /** See [DeviceEntity.preRepeatFrequency]'s doc — kHz, same unit as [frequency]. */
    val preRepeatFrequency: UInt? = null,
    /** See [DeviceEntity.preRepeatBandwidth]'s doc — Hz, same unit as [bandwidth]. */
    val preRepeatBandwidth: UInt? = null,
    val preRepeatSpreadingFactor: UByte? = null,
    val preRepeatCodingRate: UByte? = null,
    /** See [DeviceEntity.defaultFloodScopeName]'s doc. */
    val defaultFloodScopeName: String? = null,
    /** See [DeviceEntity.knownRegions]'s doc. */
    val knownRegions: List<String> = emptyList(),
    /** See [DeviceEntity.isGhost]'s doc. */
    val isGhost: Boolean = false,
) {
    /** The 6-byte public key prefix used for identifying messages. */
    val publicKeyPrefix: ByteArray get() = publicKey.copyOfRange(0, minOf(6, publicKey.size))

    /** Telemetry modes constructed from the raw base/location/environment values. Ported from `Device.telemetryModes`. */
    val telemetryModes: TelemetryModes
        get() = TelemetryModes(base = telemetryModeBase, location = telemetryModeLocation, environment = telemetryModeEnvironment)

    /** Advertisement location policy interpreted from [advertLocationPolicy]. Ported from `Device.advertLocationPolicyMode`. */
    val advertLocationPolicyMode: AdvertLocationPolicy
        get() = AdvertLocationPolicy.fromRawValue(advertLocationPolicy) ?: AdvertLocationPolicy.NONE

    /** Computed auto-add mode based on [manualAddContacts] and [autoAddConfig]. Ported from `Device.autoAddMode`. */
    val autoAddMode: AutoAddMode get() = AutoAddMode.mode(manualAddContacts = manualAddContacts, autoAddConfig = autoAddConfig)

    /** Whether to auto-add Chat (contact) nodes. Ported from `Device.autoAddContacts`. */
    val autoAddContacts: Boolean get() = autoAddConfig and AutoAddConfig.CONTACTS_BIT != 0u.toUByte()

    /** Whether to auto-add Repeater nodes. Ported from `Device.autoAddRepeaters`. */
    val autoAddRepeaters: Boolean get() = autoAddConfig and AutoAddConfig.REPEATERS_BIT != 0u.toUByte()

    /** Whether to auto-add Room Server nodes. Ported from `Device.autoAddRoomServers`. */
    val autoAddRoomServers: Boolean get() = autoAddConfig and AutoAddConfig.ROOM_SERVERS_BIT != 0u.toUByte()

    /** Whether to overwrite the oldest non-favorite node when storage is full. Ported from `Device.overwriteOldest`. */
    val overwriteOldest: Boolean get() = autoAddConfig and AutoAddConfig.OVERWRITE_OLDEST_BIT != 0u.toUByte()

    /**
     * Whether the device supports auto-add configuration (firmware v1.12+); older firmware only
     * supports the [manualAddContacts] toggle. Ported from `Device.supportsAutoAddConfig` — unlike
     * [supportsTraceHashSizeOverride]/[supportsPathHashMode], Swift has no `firmwareVersion`
     * numeric-code fallback for this one (`FIRMWARE_VER_CODE` doesn't disambiguate v1.12), so this
     * is a straight port of the `firmwareVersionString.isAtLeast(major:minor:)` check via
     * [String.isAtLeastVersion].
     */
    val supportsAutoAddConfig: Boolean get() = firmwareVersionString.isAtLeastVersion(major = 1, minor = 12)

    /** Whether the device supports auto-add max hops (firmware v1.14+). Ported from `Device.supportsAutoAddMaxHops`. */
    val supportsAutoAddMaxHops: Boolean get() = firmwareVersionString.isAtLeastVersion(major = 1, minor = 14)

    /** Whether this device supports client repeat mode (firmware v9+). Ported from `Device.supportsClientRepeat`. */
    val supportsClientRepeat: Boolean get() = firmwareVersion >= 9u

    /**
     * Whether pre-repeat radio settings are saved for restoration. Ported from
     * `Device.hasPreRepeatSettings`.
     */
    val hasPreRepeatSettings: Boolean
        get() = preRepeatFrequency != null && preRepeatBandwidth != null &&
            preRepeatSpreadingFactor != null && preRepeatCodingRate != null

    /**
     * Trace hash size per hop in bytes (1, 2, or 4), derived from [pathHashMode]. Trace protocol
     * uses power-of-2 encoding (`1 shl pathHashMode`), unlike the linear 1/2/3-byte routing hash
     * size. Ported from `Device.traceHashSize`.
     */
    val traceHashSize: Int get() = 1 shl pathHashMode.toInt()

    /**
     * Whether the trace command honors a per-trace hash size in its flags byte (firmware v1.11+).
     * Ported from `Device.supportsTraceHashSizeOverride`, trimmed to the `firmwareVersion` check —
     * Swift also OR's a `firmwareVersionString.isAtLeast(major: 1, minor: 11)` check to disambiguate
     * the v1.11/v1.12 window where `FIRMWARE_VER_CODE` itself stayed 8; this port has no
     * semver-string-compare utility yet, so that fallback branch is dropped (a v1.11-exact device
     * self-reporting `firmwareVersion == 8` will under-detect support until one is ported).
     */
    val supportsTraceHashSizeOverride: Boolean get() = firmwareVersion >= 9u

    /** Whether this device supports path hash mode configuration (firmware v10+). Ported from `Device.supportsPathHashMode`. */
    val supportsPathHashMode: Boolean get() = firmwareVersion >= 10u

    /** Whether the device supports a persisted default flood-routing scope (firmware v11+). Ported from `Device.supportsDefaultFloodScope`. */
    val supportsDefaultFloodScope: Boolean get() = firmwareVersion >= 11u

    /** Whether a channel can override the device default and broadcast unscoped (firmware v12+). Ported from `Device.supportsUnscopedFloodSend`. */
    val supportsUnscopedFloodSend: Boolean get() = firmwareVersion >= 12u

    /**
     * Whether region discovery can query a repeater that isn't already a contact (firmware
     * v13+, or v1.16+ by version string — mirrors [supportsTraceHashSizeOverride]'s two-check
     * shape for the same v1.11-window reason). Ported from `Device.supportsAdHocRepeaterRequest`.
     */
    val supportsAdHocRepeaterRequest: Boolean
        get() = firmwareVersion >= 13u || firmwareVersionString.isAtLeastVersion(major = 1, minor = 16)

    // ByteArray has reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceDto) return false
        return id == other.id &&
            radioID == other.radioID &&
            publicKey.contentEquals(other.publicKey) &&
            nodeName == other.nodeName &&
            firmwareVersion == other.firmwareVersion &&
            firmwareVersionString == other.firmwareVersionString &&
            manufacturerName == other.manufacturerName &&
            buildDate == other.buildDate &&
            maxContacts == other.maxContacts &&
            maxChannels == other.maxChannels &&
            frequency == other.frequency &&
            bandwidth == other.bandwidth &&
            spreadingFactor == other.spreadingFactor &&
            codingRate == other.codingRate &&
            txPower == other.txPower &&
            maxTxPower == other.maxTxPower &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            blePin == other.blePin &&
            lastConnected == other.lastConnected &&
            lastContactSync == other.lastContactSync &&
            isActive == other.isActive &&
            ocvPreset == other.ocvPreset &&
            appliedRadioPresetID == other.appliedRadioPresetID &&
            customOCVArrayString == other.customOCVArrayString &&
            pathHashMode == other.pathHashMode &&
            bleAddress == other.bleAddress &&
            wifiHost == other.wifiHost &&
            wifiPort == other.wifiPort &&
            manualAddContacts == other.manualAddContacts &&
            multiAcks == other.multiAcks &&
            telemetryModeBase == other.telemetryModeBase &&
            telemetryModeLocation == other.telemetryModeLocation &&
            telemetryModeEnvironment == other.telemetryModeEnvironment &&
            advertLocationPolicy == other.advertLocationPolicy &&
            autoAddConfig == other.autoAddConfig &&
            autoAddMaxHops == other.autoAddMaxHops &&
            clientRepeat == other.clientRepeat &&
            preRepeatFrequency == other.preRepeatFrequency &&
            preRepeatBandwidth == other.preRepeatBandwidth &&
            preRepeatSpreadingFactor == other.preRepeatSpreadingFactor &&
            preRepeatCodingRate == other.preRepeatCodingRate &&
            defaultFloodScopeName == other.defaultFloodScopeName &&
            knownRegions == other.knownRegions &&
            isGhost == other.isGhost
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + nodeName.hashCode()
        result = 31 * result + firmwareVersion.hashCode()
        result = 31 * result + firmwareVersionString.hashCode()
        result = 31 * result + manufacturerName.hashCode()
        result = 31 * result + buildDate.hashCode()
        result = 31 * result + maxContacts.hashCode()
        result = 31 * result + maxChannels.hashCode()
        result = 31 * result + frequency.hashCode()
        result = 31 * result + bandwidth.hashCode()
        result = 31 * result + spreadingFactor.hashCode()
        result = 31 * result + codingRate.hashCode()
        result = 31 * result + txPower.hashCode()
        result = 31 * result + maxTxPower.hashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + blePin.hashCode()
        result = 31 * result + lastConnected.hashCode()
        result = 31 * result + lastContactSync.hashCode()
        result = 31 * result + isActive.hashCode()
        result = 31 * result + (ocvPreset?.hashCode() ?: 0)
        result = 31 * result + (appliedRadioPresetID?.hashCode() ?: 0)
        result = 31 * result + (customOCVArrayString?.hashCode() ?: 0)
        result = 31 * result + pathHashMode.hashCode()
        result = 31 * result + (bleAddress?.hashCode() ?: 0)
        result = 31 * result + (wifiHost?.hashCode() ?: 0)
        result = 31 * result + (wifiPort ?: 0)
        result = 31 * result + manualAddContacts.hashCode()
        result = 31 * result + multiAcks.hashCode()
        result = 31 * result + telemetryModeBase.hashCode()
        result = 31 * result + telemetryModeLocation.hashCode()
        result = 31 * result + telemetryModeEnvironment.hashCode()
        result = 31 * result + advertLocationPolicy.hashCode()
        result = 31 * result + autoAddConfig.hashCode()
        result = 31 * result + autoAddMaxHops.hashCode()
        result = 31 * result + clientRepeat.hashCode()
        result = 31 * result + (preRepeatFrequency?.hashCode() ?: 0)
        result = 31 * result + (preRepeatBandwidth?.hashCode() ?: 0)
        result = 31 * result + (preRepeatSpreadingFactor?.hashCode() ?: 0)
        result = 31 * result + (preRepeatCodingRate?.hashCode() ?: 0)
        result = 31 * result + (defaultFloodScopeName?.hashCode() ?: 0)
        result = 31 * result + knownRegions.hashCode()
        result = 31 * result + isGhost.hashCode()
        return result
    }
}

/**
 * Returns a copy with radio configuration fields reset to safe defaults, the surrogate [DeviceDto
 * .id] reset to a fresh [UUID], and [DeviceDto.bleAddress] cleared. Used during backup export to
 * avoid exposing the BLE PIN, radio frequency, or the source phone's BLE MAC address in the backup
 * file — those are only meaningful on the phone that paired the radio and aren't portable across
 * installs. Ported from `Device.redactedForBackup()`; [DeviceDto.wifiHost]/[DeviceDto.wifiPort]
 * are intentionally retained (restore-then-reconnect reads them), matching Swift's own choice to
 * keep WiFi connection methods while stripping Bluetooth ones. [DeviceDto.publicKey]/
 * [DeviceDto.radioID] are preserved as the reconciliation/partition keys.
 *
 * The literal defaults below (frequency/bandwidth/spreading factor/coding rate/tx power) mirror
 * Swift's `Device.Defaults` enum; the rest of the reset fields already default to these same
 * values on [DeviceDto]'s own constructor, so `copy(...)` names only what actually changes.
 */
fun DeviceDto.redactedForBackup(): DeviceDto = copy(
    id = UUID.randomUUID(),
    frequency = 915_000u,
    bandwidth = 250_000u,
    spreadingFactor = 10u,
    codingRate = 5u,
    appliedRadioPresetID = null,
    txPower = 20,
    maxTxPower = 20,
    latitude = 0.0,
    longitude = 0.0,
    blePin = 0u,
    clientRepeat = false,
    pathHashMode = 0u,
    bleAddress = null,
    manualAddContacts = false,
    multiAcks = 2u,
    telemetryModeBase = 2u,
    telemetryModeLocation = 0u,
    telemetryModeEnvironment = 0u,
    advertLocationPolicy = 0u,
    autoAddConfig = 0u,
    autoAddMaxHops = 0u,
    preRepeatFrequency = null,
    preRepeatBandwidth = null,
    preRepeatSpreadingFactor = null,
    preRepeatCodingRate = null,
)

/**
 * Checks if this version string is at least the specified version. Handles formats like
 * "v1.12.0", "1.12", "v1.12". Ported from `String.isAtLeast(major:minor:)`.
 */
/** Maps a persisted row to the immutable snapshot services consume. */
fun DeviceEntity.toDto(): DeviceDto = DeviceDto(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    nodeName = nodeName,
    firmwareVersion = firmwareVersion.toUByte(),
    firmwareVersionString = firmwareVersionString,
    manufacturerName = manufacturerName,
    buildDate = buildDate,
    maxContacts = maxContacts.toUShort(),
    maxChannels = maxChannels.toUByte(),
    frequency = frequency.toUInt(),
    bandwidth = bandwidth.toUInt(),
    spreadingFactor = spreadingFactor.toUByte(),
    codingRate = codingRate.toUByte(),
    txPower = txPower.toByte(),
    maxTxPower = maxTxPower.toByte(),
    latitude = latitude,
    longitude = longitude,
    blePin = blePin.toUInt(),
    lastConnected = lastConnected,
    lastContactSync = lastContactSync.toUInt(),
    isActive = isActive,
    ocvPreset = ocvPreset,
    appliedRadioPresetID = appliedRadioPresetID,
    customOCVArrayString = customOCVArrayString,
    pathHashMode = pathHashMode.toUByte(),
    bleAddress = bleAddress,
    wifiHost = wifiHost,
    wifiPort = wifiPort,
    manualAddContacts = manualAddContacts,
    multiAcks = multiAcks.toUByte(),
    telemetryModeBase = telemetryModeBase.toUByte(),
    telemetryModeLocation = telemetryModeLocation.toUByte(),
    telemetryModeEnvironment = telemetryModeEnvironment.toUByte(),
    advertLocationPolicy = advertLocationPolicy.toUByte(),
    autoAddConfig = autoAddConfig.toUByte(),
    autoAddMaxHops = autoAddMaxHops.toUByte(),
    clientRepeat = clientRepeat,
    preRepeatFrequency = preRepeatFrequency?.toUInt(),
    preRepeatBandwidth = preRepeatBandwidth?.toUInt(),
    preRepeatSpreadingFactor = preRepeatSpreadingFactor?.toUByte(),
    preRepeatCodingRate = preRepeatCodingRate?.toUByte(),
    defaultFloodScopeName = defaultFloodScopeName,
    knownRegions = if (knownRegions.isEmpty()) emptyList() else knownRegions.split(","),
    isGhost = isGhost,
)

/** Maps a domain snapshot to the Room row shape (the lossless signed widening — see [DeviceEntity]'s class doc). */
fun DeviceDto.toEntity(): DeviceEntity = DeviceEntity(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    nodeName = nodeName,
    firmwareVersion = firmwareVersion.toInt(),
    firmwareVersionString = firmwareVersionString,
    manufacturerName = manufacturerName,
    buildDate = buildDate,
    maxContacts = maxContacts.toInt(),
    maxChannels = maxChannels.toInt(),
    frequency = frequency.toLong(),
    bandwidth = bandwidth.toLong(),
    spreadingFactor = spreadingFactor.toInt(),
    codingRate = codingRate.toInt(),
    txPower = txPower.toInt(),
    maxTxPower = maxTxPower.toInt(),
    latitude = latitude,
    longitude = longitude,
    blePin = blePin.toLong(),
    lastConnected = lastConnected,
    lastContactSync = lastContactSync.toLong(),
    isActive = isActive,
    ocvPreset = ocvPreset,
    appliedRadioPresetID = appliedRadioPresetID,
    customOCVArrayString = customOCVArrayString,
    pathHashMode = pathHashMode.toInt(),
    bleAddress = bleAddress,
    wifiHost = wifiHost,
    wifiPort = wifiPort,
    manualAddContacts = manualAddContacts,
    multiAcks = multiAcks.toInt(),
    telemetryModeBase = telemetryModeBase.toInt(),
    telemetryModeLocation = telemetryModeLocation.toInt(),
    telemetryModeEnvironment = telemetryModeEnvironment.toInt(),
    advertLocationPolicy = advertLocationPolicy.toInt(),
    autoAddConfig = autoAddConfig.toInt(),
    autoAddMaxHops = autoAddMaxHops.toInt(),
    clientRepeat = clientRepeat,
    preRepeatFrequency = preRepeatFrequency?.toLong(),
    preRepeatBandwidth = preRepeatBandwidth?.toLong(),
    preRepeatSpreadingFactor = preRepeatSpreadingFactor?.toInt(),
    preRepeatCodingRate = preRepeatCodingRate?.toInt(),
    defaultFloodScopeName = defaultFloodScopeName,
    knownRegions = knownRegions.joinToString(","),
    isGhost = isGhost,
)

/**
 * The active OCV array for this device (preset or custom), same rule as [ContactDto.activeOCVArray].
 */
val DeviceDto.activeOCVArray: List<Int>
    get() {
        if (ocvPreset == OCVPreset.CUSTOM.rawValue && customOCVArrayString != null) {
            val parsed = customOCVArrayString.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (parsed.size == 11) return parsed
        }
        ocvPreset?.let { OCVPreset.fromRawValue(it)?.let { preset -> return preset.ocvArray } }
        return OCVPreset.LI_ION.ocvArray
    }
