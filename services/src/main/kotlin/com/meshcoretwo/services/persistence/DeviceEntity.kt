// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * A connected MeshCore BLE/WiFi device. Ported from `Device.swift`'s `@Model` (SwiftData) to a
 * Room `@Entity`, trimmed to device identity and radio parameters — the ~20 pre-repeat/OCV-preset-
 * detail/connection-method/known-region fields Swift's `Device` also carries are deferred until
 * `SettingsService`/`DeviceService`'s fuller surface or `ConnectionManager` need them (see
 * PLAN.md's Phase 3 status), rather than modeled speculatively now. [ocvPreset]/
 * [customOCVArrayString] are one exception: they're included because
 * [com.meshcoretwo.services.device.DeviceService.updateOCVSettings] (ported alongside this
 * entity) needs them. [manualAddContacts]/[multiAcks]/[telemetryModeBase]/[telemetryModeLocation]/
 * [telemetryModeEnvironment]/[advertLocationPolicy] are another: added for
 * [com.meshcoretwo.services.settings.SettingsService.setOtherParamsVerified]'s device-defaulting
 * overload (see its doc). [autoAddConfig]/[autoAddMaxHops] were added for `NodesSettingsSection`'s
 * port (PLAN.md's Phase 5 slice 28) — see [DeviceDto.autoAddMode]'s doc. [clientRepeat]/
 * [preRepeatFrequency]/[preRepeatBandwidth]/[preRepeatSpreadingFactor]/[preRepeatCodingRate] were
 * added for `AdvancedRadioSection`'s port (Phase 5 slice 29) — see [DeviceDto.hasPreRepeatSettings]'s
 * doc. [defaultFloodScopeName]/[knownRegions] were added for `DefaultFloodScopeSection`'s port
 * (Phase 5 slice 32) — see [DeviceDto.supportsDefaultFloodScope]'s doc. [isGhost] was added for
 * ghost-identity reconciliation (the `demoteDeviceToGhost`/`reconcileGhostIdentity`/
 * `ConnectionManager.reconcileIdentity` trio) — see its own doc.
 *
 * As with [ContactEntity], [firmwareVersion]/[maxContacts]/[maxChannels]/[spreadingFactor]/
 * [codingRate]/[txPower]/[maxTxPower] (all [UByte]/[Int8]/[UInt16] on the wire) and
 * [frequency]/[bandwidth]/[blePin]/[lastContactSync] (all `UInt32`) are stored as [Int]/[Long] —
 * Room's KSP processor cannot handle Kotlin unsigned types as column types.
 */
@Entity(
    tableName = "devices",
    indices = [
        Index(value = ["radioID"], unique = true),
        Index(value = ["publicKey"], unique = true),
    ],
)
data class DeviceEntity(
    @PrimaryKey val id: UUID,
    val radioID: UUID,
    val publicKey: ByteArray,
    val nodeName: String,
    val firmwareVersion: Int,
    val firmwareVersionString: String,
    val manufacturerName: String,
    val buildDate: String,
    val maxContacts: Int,
    val maxChannels: Int,
    val frequency: Long,
    val bandwidth: Long,
    val spreadingFactor: Int,
    val codingRate: Int,
    val txPower: Int,
    val maxTxPower: Int,
    val latitude: Double,
    val longitude: Double,
    val blePin: Long,
    val lastConnected: Instant,
    /**
     * Radio-RTC `max(contact.lastmod)` watermark for incremental contact sync — stored verbatim
     * (phone time is deliberately not substituted; see `PersistenceStore+Devices.swift`'s
     * `updateDeviceLastContactSync` doc for why a phone-clock bound can stall convergence).
     */
    val lastContactSync: Long,
    val isActive: Boolean,
    val ocvPreset: String?,
    val customOCVArrayString: String?,
    /**
     * Last radio-preset catalog id the user applied. Live RF can match more than one catalog
     * name (`au-sa-wa`/`br` share a tuple), so the radio tuple alone isn't identity. Ported from
     * `Device.appliedRadioPresetID` (upstream `74ad7911`).
     */
    val appliedRadioPresetID: String? = null,
    /**
     * Configured routing path-hash size code (0/1/2 -> 1/2/3-byte routing hash; trace uses
     * power-of-2 encoding on the same code, see [DeviceDto.traceHashSize]). Added for Trace Path
     * (PLAN.md's Trace Path subslice 3, `TracePathViewModel`'s port) — `0` (1-byte) as a
     * pre-this-column-existing default matches Swift's `Device.Defaults.pathHashMode`.
     */
    val pathHashMode: Int = 0,
    /**
     * The BLE MAC address last used to reach this device, or `null` for a device never connected
     * over BLE (e.g. WiFi-only). Stands in for Swift's `connectionMethods` array (dropped from
     * this entity — see the class doc above): Android has no persistent per-device identifier at
     * the transport layer the way iOS's `CBPeripheral.identifier` doubles as both transport and
     * domain identity, so `ConnectionManager` needs somewhere to persist the MAC↔[id] mapping it
     * resolves at connect time.
     */
    val bleAddress: String? = null,
    /**
     * The TCP host/port last used to reach this device over WiFi, or both `null` for a device
     * never connected over WiFi. Sibling to [bleAddress] — a device connected over both transports
     * (e.g. a BLE-paired device later reached over the same LAN) keeps both columns populated
     * rather than Swift's single `connectionMethods` array, matching this port's one-column-per-
     * transport approach (see [bleAddress]'s doc). Added for WiFi connection support in
     * `ConnectionManager` (`ConnectionManagerWiFi.kt`, ported from `ConnectionManager+WiFi.swift`).
     */
    val wifiHost: String? = null,
    val wifiPort: Int? = null,
    /**
     * Manual-add-contacts mode (inverted auto-add — `false` means auto-add is enabled). Added so
     * [DeviceDto] can back [com.meshcoretwo.services.settings.SettingsService.setOtherParamsVerified]'s
     * device-defaulting overload; see that method's doc. Default matches
     * `Device.Defaults.manualAddContacts`.
     */
    val manualAddContacts: Boolean = false,
    /** Number of direct-message ACKs the device sends (0=disabled, 1-2 typical). Default matches `Device.Defaults.multiAcks`. */
    val multiAcks: Int = 2,
    /** Packed telemetry mode for base data (0-3). Default matches `Device.Defaults.telemetryModeBase`. */
    val telemetryModeBase: Int = 2,
    /** Packed telemetry mode for location data (0-3). Default matches `Device.Defaults.telemetryModeLoc`. */
    val telemetryModeLocation: Int = 0,
    /** Packed telemetry mode for environment data (0-3). Default matches `Device.Defaults.telemetryModeEnv`. */
    val telemetryModeEnvironment: Int = 0,
    /** Raw advertisement location policy byte — see [DeviceDto.advertLocationPolicyMode]. Default matches `Device.Defaults.advertLocationPolicy`. */
    val advertLocationPolicy: Int = 0,
    /** Auto-add type/overwrite bitmask — see [DeviceDto.autoAddMode]. Default matches `Device.Defaults.autoAddConfig`. */
    val autoAddConfig: Int = 0,
    /** Max hops filter for auto-add (v1.14+ firmware only). Default matches `Device.Defaults.autoAddMaxHops`. */
    val autoAddMaxHops: Int = 0,
    /** Whether client repeat mode is enabled (firmware v9+). Default matches `Device.Defaults.clientRepeat`. */
    val clientRepeat: Boolean = false,
    /**
     * Radio settings cached from before repeat mode was enabled, for restoration on disable — all
     * four are set together and cleared together, see [DeviceDto.hasPreRepeatSettings]. Default
     * matches `Device.Defaults.preRepeat*` (all `nil`).
     */
    val preRepeatFrequency: Long? = null,
    val preRepeatBandwidth: Long? = null,
    val preRepeatSpreadingFactor: Int? = null,
    val preRepeatCodingRate: Int? = null,
    /**
     * The device's persisted default flood-routing scope name (firmware v11+), or `null` when
     * disabled. Added for `DefaultFloodScopeSection`'s port (Phase 5 slice 32) — see
     * [DeviceDto.supportsDefaultFloodScope]'s doc. Default matches `Device.Defaults.defaultFloodScopeName`.
     */
    val defaultFloodScopeName: String? = null,
    /**
     * Comma-joined known flood-routing region names — app-only state, not overwritten by ordinary
     * device-info resync (see `createDeviceDto`'s carry-forward). Empty string means none, same
     * convention as [com.meshcoretwo.services.persistence.TracePathDto.hopsSNR]'s comma-joined
     * `Double` column (region names are validated ASCII letters/digits/`-`, so commas can't
     * appear). Default matches `Device.Defaults.knownRegions` (`[]`).
     */
    val knownRegions: String = "",
    /**
     * Whether this row is a ghost — a forgotten-but-kept-data device, unreachable over any
     * transport, preserving [publicKey]/[radioID] so [DeviceStore.reconcileGhostIdentity] can
     * re-attach its Contact/Message/Channel/etc. history to a live device later. Stands in for
     * Swift's implicit "`isActive == false` and no Bluetooth `connectionMethod`" derived state —
     * an explicit flag, since this entity has no `connectionMethods` array to derive it from (see
     * [bleAddress]'s doc) and `isActive == false` alone is already ambiguous with an ordinary
     * saved-but-disconnected real device. Only [DeviceStore.demoteDeviceToGhost] ever sets this
     * `true`; every ordinary connect explicitly writes `false` (see `createDeviceDto`'s doc).
     */
    val isGhost: Boolean = false,
)
