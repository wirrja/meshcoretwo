// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.protocol.AutoAddConfig
import com.meshcoretwo.protocol.DeviceCapabilities
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.services.ServiceContainer
import com.meshcoretwo.services.persistence.DeviceDto
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID

/**
 * Session-establishment and device-record helpers shared by every connect path (fresh connect,
 * device switch, auto-reconnect rebuild). Ported from the matching sections of
 * `ConnectionManager.swift` — see [ConnectionManager]'s class doc for the file split and what's
 * excluded (identity-import reconciliation, SwiftData migrations, the ~15 `Device` fields this
 * port's [DeviceDto] doesn't model).
 */
internal const val SESSION_START_TIMEOUT_MILLIS = 10_000L
internal const val QUERY_DEVICE_TIMEOUT_MILLIS = 10_000L

/** Starts a session and queries device capabilities, bounding each step with a timeout. Ported from `initializeSession`. */
internal suspend fun ConnectionManager.initializeSessionImpl(
    session: com.meshcoretwo.protocol.MeshCoreSession,
): Pair<SelfInfo, DeviceCapabilities> {
    val started = withTimeoutOrNull(SESSION_START_TIMEOUT_MILLIS) {
        session.start()
        true
    }
    if (started == null) throw ConnectionError.InitializationFailed("session.start() timed out")

    val selfInfo = session.currentSelfInfo
        ?: throw ConnectionError.InitializationFailed("Failed to get device self info")

    val capabilities = withTimeoutOrNull(QUERY_DEVICE_TIMEOUT_MILLIS) { session.queryDevice() }
        ?: throw ConnectionError.InitializationFailed("queryDevice() timed out")

    return selfInfo to capabilities
}

/** Wires the clean-channel-sync callback on a new [ServiceContainer] so [ConnectionManager.lastCleanChannelSync]/[ConnectionManager.lastAttemptedChannelSync] update when a channel phase completes. */
internal fun ConnectionManager.wireCleanChannelSyncCallbackImpl(services: ServiceContainer) {
    services.syncCoordinator.onCleanChannelSync = { radioID -> lastCleanChannelSync = radioID to Instant.now() }
    services.syncCoordinator.onChannelSyncAttempted = { radioID -> lastAttemptedChannelSync = radioID to Instant.now() }
}

/**
 * Builds a fresh [ServiceContainer], resolves/persists the [DeviceDto] record, and updates
 * [ConnectionManager.session]/[ConnectionManager.services]/[ConnectionManager.connectedDevice].
 * Ported from `buildServicesAndSaveDevice`, with the domain-identity resolution changed to match
 * public key first (Android has no [deviceAddress]-known-ahead-of-time equivalent for a
 * never-before-seen device — see [ConnectionManager]'s "Identifier translation" doc) rather than
 * Swift's `deviceID`-first / public-key-fallback order.
 *
 * Also wires [com.meshcoretwo.services.nodeconfig.NodeConfigService.setOnPostIdentityImport] to
 * [reconcileIdentity], matching where Swift wires the same callback (`ConnectionManager`'s own
 * `buildServicesAndSaveDevice`, not [ServiceContainer] construction — see its class doc for why the
 * container itself can't do this wiring).
 *
 * @param deviceAddress The BLE MAC address, for a BLE connect/switch/reconnect — `null` for WiFi
 *   (see [wifiHost]/[wifiPort]). Whichever transport's address is `null` here is left untouched
 *   from [existingDevice] rather than cleared, so a device connected over both transports keeps
 *   both — see [DeviceEntity.wifiHost]'s doc.
 */
internal suspend fun ConnectionManager.buildServicesAndSaveDeviceImpl(
    deviceAddress: String?,
    session: com.meshcoretwo.protocol.MeshCoreSession,
    selfInfo: SelfInfo,
    capabilities: DeviceCapabilities,
    wifiHost: String? = null,
    wifiPort: Int? = null,
): Pair<ServiceContainer, UUID> {
    val existingDevice = deviceStore.fetchDevice(selfInfo.publicKey)
    val resolvedDeviceID = existingDevice?.id ?: UUID.randomUUID()
    val resolvedRadioID = existingDevice?.radioID ?: UUID.randomUUID()

    val autoAddConfig = try {
        session.getAutoAddConfig()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        AutoAddConfig(bitmask = 0u)
    }

    val repeatFreqRanges = if (capabilities.clientRepeat) {
        try {
            session.getRepeatFreq()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            emptyList()
        }
    } else {
        emptyList()
    }

    val newServices = ServiceContainer(
        context = context,
        session = session,
        database = database,
        radioID = resolvedRadioID,
        appStateProvider = appStateProvider,
        connectionStateEvents = connectionStateEvents,
        initialConnectionState = connectionState,
        keychainService = keychainServiceFactory(),
    )
    wireCleanChannelSyncCallbackImpl(newServices)
    newServices.nodeConfigService.setOnPostIdentityImport {
        reconcileIdentity(expectedServices = newServices, deviceID = resolvedDeviceID)
    }

    val deviceDto = createDeviceDto(
        deviceID = resolvedDeviceID,
        radioID = resolvedRadioID,
        deviceAddress = deviceAddress,
        selfInfo = selfInfo,
        capabilities = capabilities,
        autoAddConfig = autoAddConfig,
        existingDevice = existingDevice,
        wifiHost = wifiHost,
        wifiPort = wifiPort,
    )
    // Persist before warmUp so purgeOrphanPendingSends sees the in-progress radio's Device row
    // and does not classify its PendingSends as orphans.
    deviceStore.saveDevice(deviceDto)

    try {
        newServices.warmUp()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        // Best-effort, matching Swift's log-and-continue.
    }

    newServices.chatSendQueueService.hydrate()

    this.session = session
    services = newServices
    connectedDevice = deviceDto
    allowedRepeatFreqRanges = repeatFreqRanges

    return newServices to resolvedRadioID
}

/** Builds a [DeviceDto] from device-reported info, merging in the fields [DeviceDto] persists that Android doesn't re-derive on every connect. Ported from `createDevice`, trimmed to the fields [DeviceDto] models. */
private fun createDeviceDto(
    deviceID: UUID,
    radioID: UUID,
    deviceAddress: String?,
    selfInfo: SelfInfo,
    capabilities: DeviceCapabilities,
    autoAddConfig: AutoAddConfig,
    existingDevice: DeviceDto?,
    wifiHost: String? = null,
    wifiPort: Int? = null,
): DeviceDto = DeviceDto(
    id = deviceID,
    radioID = radioID,
    publicKey = selfInfo.publicKey,
    nodeName = selfInfo.name,
    firmwareVersion = capabilities.firmwareVersion,
    firmwareVersionString = capabilities.version,
    manufacturerName = capabilities.model,
    buildDate = capabilities.firmwareBuild,
    maxContacts = capabilities.maxContacts.toUShort(),
    maxChannels = capabilities.maxChannels.coerceAtMost(255).toUByte(),
    // MHz -> kHz
    frequency = (selfInfo.radioFrequency * 1000).toInt().toUInt(),
    // kHz -> Hz
    bandwidth = (selfInfo.radioBandwidth * 1000).toInt().toUInt(),
    spreadingFactor = selfInfo.radioSpreadingFactor,
    codingRate = selfInfo.radioCodingRate,
    txPower = selfInfo.txPower,
    maxTxPower = selfInfo.maxTxPower,
    latitude = selfInfo.latitude,
    longitude = selfInfo.longitude,
    blePin = capabilities.blePin,
    lastConnected = Instant.now(),
    lastContactSync = existingDevice?.lastContactSync ?: 0u,
    isActive = true,
    // A connected device is never a ghost — always false regardless of existingDevice?.isGhost, the
    // same explicit-not-carried-forward treatment as isActive above. This is what "un-ghosts" a row
    // that reconnected over its own unchanged publicKey (no reflash, so reconcileIdentity never
    // ran) — see DeviceEntity.isGhost's doc.
    isGhost = false,
    ocvPreset = existingDevice?.ocvPreset,
    appliedRadioPresetID = existingDevice?.appliedRadioPresetID,
    customOCVArrayString = existingDevice?.customOCVArrayString,
    pathHashMode = capabilities.pathHashMode,
    bleAddress = deviceAddress ?: existingDevice?.bleAddress,
    wifiHost = wifiHost ?: existingDevice?.wifiHost,
    wifiPort = wifiPort ?: existingDevice?.wifiPort,
    manualAddContacts = selfInfo.manualAddContacts,
    multiAcks = selfInfo.multiAcks,
    telemetryModeBase = selfInfo.telemetryModeBase,
    telemetryModeLocation = selfInfo.telemetryModeLocation,
    telemetryModeEnvironment = selfInfo.telemetryModeEnvironment,
    advertLocationPolicy = selfInfo.advertisementLocationPolicy,
    autoAddConfig = autoAddConfig.bitmask,
    autoAddMaxHops = autoAddConfig.maxHops,
    clientRepeat = capabilities.clientRepeat,
    // Carry the existing device's pre-repeat settings forward, unless repeat mode is no longer
    // active — then they're orphaned (e.g. disabled outside the app) and cleared. Ported from
    // `ConnectionManager.createDevice`'s post-init pre-repeat-clearing block.
    preRepeatFrequency = if (capabilities.clientRepeat) existingDevice?.preRepeatFrequency else null,
    preRepeatBandwidth = if (capabilities.clientRepeat) existingDevice?.preRepeatBandwidth else null,
    preRepeatSpreadingFactor = if (capabilities.clientRepeat) existingDevice?.preRepeatSpreadingFactor else null,
    preRepeatCodingRate = if (capabilities.clientRepeat) existingDevice?.preRepeatCodingRate else null,
    // App-only state, never re-derived from device-reported info — see DeviceEntity.knownRegions's doc.
    defaultFloodScopeName = existingDevice?.defaultFloodScopeName,
    knownRegions = existingDevice?.knownRegions ?: emptyList(),
)

/**
 * Promotes the connection to [DeviceConnectionState.READY] if it's still alive and owned by the
 * expected services. Skips post-sync work (time sync, `onDeviceSynced`) when sync failed to avoid
 * BLE pressure. Ported from `promoteToReady`.
 *
 * @param additionalGuard Caller-specific invariant checked at every guard point, including after
 *   the internal `syncDeviceTimeIfNeeded` await — a competing reconnect cycle could start during
 *   time sync, and promoting a stale session to ready would shadow the new one. Only
 *   `rebuildSessionImpl` uses this (reconnect-generation check).
 * @return `true` if ready was set, `false` if promotion was suppressed.
 */
internal suspend fun ConnectionManager.promoteToReadyImpl(
    syncSucceeded: Boolean,
    expectedServices: ServiceContainer,
    transportType: TransportType,
    additionalGuard: (() -> Boolean)? = null,
): Boolean {
    if (!connectionIntent.wantsConnection) return false
    if (services !== expectedServices) return false
    if (additionalGuard?.invoke() == false) return false

    currentTransportType = transportType
    setConnectionState(if (syncSucceeded) DeviceConnectionState.READY else DeviceConnectionState.SYNCING)
    surfacedAuthFailureDeviceAddress = null

    // Skip time sync on BLE failure to avoid pressure on a saturated link.
    if (syncSucceeded) {
        syncDeviceTimeIfNeededImpl()
        if (!connectionIntent.wantsConnection) return false
        if (services !== expectedServices) return false
        if (additionalGuard?.invoke() == false) return false
    }

    if (syncSucceeded) onDeviceSynced?.invoke()
    return true
}

/**
 * Setting the radio clock backward makes its request timestamps fall below the `last_timestamp`
 * remote repeaters recorded for it, and their replay protection then silently drops every packet
 * until the clock re-passes the stored value — a tight tolerance keeps each backward step (and
 * therefore each deaf window) no longer than the tolerance itself. Ported from `deviceClockDriftTolerance`.
 */
private const val DEVICE_CLOCK_DRIFT_TOLERANCE_SECONDS = 5L
private const val TIME_SYNC_TIMEOUT_MILLIS = 5_000L

/** Syncs the device clock when it drifts beyond the tolerance. Ported from `syncDeviceTimeIfNeeded`. */
internal suspend fun ConnectionManager.syncDeviceTimeIfNeededImpl() {
    val session = session ?: return
    try {
        val deviceTime = withTimeoutOrNull(TIME_SYNC_TIMEOUT_MILLIS) { session.getTime() } ?: return
        val driftSeconds = kotlin.math.abs(java.time.Duration.between(deviceTime, Instant.now()).seconds)
        if (driftSeconds > DEVICE_CLOCK_DRIFT_TOLERANCE_SECONDS) {
            withTimeoutOrNull(TIME_SYNC_TIMEOUT_MILLIS) { session.setTime(Instant.now()) }
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        // Best-effort, matching Swift's log-and-continue.
    }
}

/**
 * Configures BLE write pacing based on the detected device platform. Ported from `configureBLEPacing`.
 */
internal suspend fun ConnectionManager.configureBLEPacingImpl(capabilities: DeviceCapabilities) {
    detectAndStorePlatformImpl(capabilities.model, TransportType.BLUETOOTH)
    stateMachine.setWritePacingDelay(detectedPlatform.recommendedWritePacing.toMillis())
}

/**
 * Detects and stores the device platform from its model string, used for channel-sync throttling
 * and BLE write pacing. Ported from `detectAndStorePlatform`, including the WiFi
 * unrecognized-model-defaults-to-ESP32 branch — every MeshCore WiFi transport shipped so far is
 * ESP32-based, so an unrecognized model string over WiFi is more likely a new/renamed ESP32 board
 * than a platform this heuristic has never seen at all.
 */
internal fun ConnectionManager.detectAndStorePlatformImpl(model: String, transportType: TransportType) {
    var platform = DevicePlatform.detect(model)
    if (transportType == TransportType.WIFI && platform == DevicePlatform.UNKNOWN) {
        platform = DevicePlatform.ESP32
    }
    detectedPlatform = platform
}
