// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.contacts.ContactServiceError
import com.meshcoretwo.services.pairing.DevicePairingError
import com.meshcoretwo.services.pairing.PairingError
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.transport.BleError
import com.meshcoretwo.services.utilities.VContactIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/**
 * Device pairing/discovery, forget/delete, and the small device-record update helpers that don't
 * depend on a [DeviceDto] field this port doesn't model. Ported from
 * `ConnectionManager+Pairing.swift` — slice D of the `ConnectionManager` recon in PLAN.md.
 *
 * Android has no AccessorySetupKit-equivalent system pairing registry, so this file's
 * [pairNewDevice] always takes the *macOS* branch of every Swift guard that checked one —
 * `BluetoothScanPairingService`/[com.meshcoretwo.services.pairing.BleScanPairingService], not
 * `AccessorySetupPairingService` — see that class's doc. One consequence carried through
 * everywhere below: `removeStrandedAssociations` isn't ported as a separate function, because its
 * Swift body is `guard pairing.hasSystemPairingRegistry else { return }` and
 * [com.meshcoretwo.services.pairing.BleScanPairingService.hasSystemPairingRegistry] is
 * unconditionally `false` here — the whole function is provably a no-op on this platform, the same
 * way it already is on Swift's own macOS path.
 *
 * **Ported:** [pairNewDevice] (discovery + connect ceremony, cancellation/other-app-conflict
 * handling), [waitForOtherAppReconnection], [removeFailedPairing], [forgetDevice] (both overloads),
 * [deleteDevice], [fetchSavedDevices], [hasAccessory], [pairedAccessoryInfos],
 * [renameCurrentDevice], [clearStalePairings], [updateDevice], [unfavoritedNodeCount],
 * [removeUnfavoritedNodes], [removeStaleNodes] (Phase 5 slice 30 — `ContactDto.recencyTimestamp`/
 * `matchesStaleNodePrune` turned out to already be portable from existing `lastModified`/
 * `lastHeardTimestamp`/`isFavorite` columns, so this bullet's "not ported here" from earlier slices
 * no longer applies). **Cascading delete** (`deleteDeviceAndData`) is ported too — [forgetDevice]'s
 * `deleteData = true` branch and the factory-reset overload both call
 * [com.meshcoretwo.services.persistence.DeviceStore.deleteDeviceAndData], which cascades across
 * Contact/Message/Channel/etc. by hand (see its own doc for why). Ghost-demotion
 * (`demoteDeviceToGhost`) is the "keep my data" alternative — see
 * [com.meshcoretwo.services.persistence.DeviceStore.demoteDeviceToGhost]/
 * [com.meshcoretwo.services.persistence.DeviceStore.reconcileGhostIdentity] and [reconcileIdentity]
 * (`ConnectionManagerIdentity.kt`) — [forgetDevice] takes a `deleteData` flag for this choice, and
 * [deleteDevice] demotes rather than hard-deletes.
 *
 * **Trimmed/deferred** (all pre-existing gaps, not new to this slice):
 * - **Device-update convenience methods** depending on [DeviceDto] fields added across several
 *   later slices (`autoAddConfig`/`autoAddMaxHops`/`pathHashMode`/`clientRepeat`/pre-repeat-
 *   settings — see [DeviceDto]'s class doc) — each applied through `SettingsViewModel.patchDevice`
 *   rather than a dedicated `ConnectionManager` method like Swift's `updateAutoAddConfig`/
 *   `updateClientRepeat`/`updatePathHashMode`/`savePreRepeatSettings`/`clearPreRepeatSettings` —
 *   see `SettingsViewModel.setAdvancedRadioSettings`'s doc for why. `defaultFloodScopeName`/
 *   `knownRegions` (added for Phase 5 slice 32) are the one exception: [updateDevice]'s whole-row
 *   replace isn't enough for those two, because of the cross-field invariant on removal (clearing
 *   the default scope both locally and on the radio) — see `ConnectionManagerRegions.kt`'s
 *   [com.meshcoretwo.services.connection.removeKnownRegion] instead.
 * - **`DevicePairingDelegate`** (`didRemoveDeviceWithID`/`didFailPairingForDeviceWithID`) — these
 *   only ever fire from a real system pairing registry; never on a platform without one (true on
 *   Swift's own macOS path too), so there is nothing to wire them to here.
 */

// MARK: - Pairing

/**
 * Discovers a new device through [ConnectionManager.pairingService], then connects through the
 * shared [connect] ceremony (`forceFullSync = true, forceReconnect = true`) so the pairing connect
 * coordinates with in-flight auto-reconnects/switch-device handling and the circuit breaker,
 * rather than bypassing them. Ported from `pairNewDevice`.
 *
 * @throws DevicePairingError.AlreadyInProgress on re-entry.
 * @throws PairingError.DeviceConnectedToOtherApp when another app holds the radio.
 * @throws PairingError.ConnectionFailed for any other connection failure (auth, timeout,
 *   transport error) — check [PairingError.isAuthenticationFailure] for the auth-alert path.
 * @throws CancellationException if the surrounding coroutine is cancelled mid-flight.
 */
suspend fun ConnectionManager.pairNewDevice() {
    withContext(confinedDispatcher) { pairNewDeviceImpl() }
}

private suspend fun ConnectionManager.pairNewDeviceImpl() {
    if (isPairingInProgress) throw DevicePairingError.AlreadyInProgress
    isPairingInProgress = true
    try {
        connectionIntent = ConnectionIntent.WantsConnection()
        persistIntent()

        stopBLEScanningImpl()

        pairingService.activate()
        val deviceAddress = pairingService.discoverDevice()

        if (waitForOtherAppReconnectionImpl(deviceAddress)) {
            throw PairingError.DeviceConnectedToOtherApp(deviceAddress)
        }

        try {
            connectImpl(deviceAddress, forceFullSync = true, forceReconnect = true)
        } catch (error: CancellationException) {
            cleanupPartialPairingImpl(deviceAddress)
            throw error
        } catch (error: Exception) {
            if (error.asBleError() is BleError.DeviceConnectedToOtherApp) {
                // No cleanup here — the bond is good; the user retries after dismissing the
                // other-app warning. Removing it would force a fresh pair instead.
                throw PairingError.DeviceConnectedToOtherApp(deviceAddress)
            }
            if (!currentCoroutineContext().isActive) {
                cleanupPartialPairingImpl(deviceAddress)
                throw CancellationException("pairNewDevice cancelled")
            }
            throw PairingError.ConnectionFailed(deviceAddress, error)
        }
    } finally {
        isPairingInProgress = false
    }
}

/**
 * Removes a partially-paired device from the pairing seam when pairing is cancelled mid-flight
 * before a usable connection exists (a no-op call on Android — see this file's class doc — kept
 * for parity with the shape of the Swift ceremony) and resets connection state as a defensive
 * backstop. Ported from `cleanupPartialPairing`.
 */
private suspend fun ConnectionManager.cleanupPartialPairingImpl(deviceAddress: String) {
    pairingService.removeDevice(deviceAddress)
    setConnectionState(DeviceConnectionState.DISCONNECTED)
}

/**
 * Removes a device that failed to connect after pairing, for the guided "remove and retry"
 * recovery. Ported from `removeFailedPairing`, keyed by MAC address (from [PairingError.deviceAddress])
 * instead of Swift's peripheral UUID — see [ConnectionManager]'s "Identifier translation" doc. A
 * fresh (never-before-seen) device has no persisted row yet, so its in-memory bond shield (if any)
 * is cleared directly rather than through [ConnectionManager.clearPersistedConnection], which needs
 * an existing row to resolve the MAC address from.
 */
suspend fun ConnectionManager.removeFailedPairing(deviceAddress: String) {
    withContext(confinedDispatcher) {
        pairingService.removeDevice(deviceAddress)
        val deviceID = deviceStore.fetchDeviceByBleAddress(deviceAddress)?.id
        if (deviceID != null) clearPersistedConnection(deviceID) else stateMachine.clearBondVerification(deviceAddress)
    }
}

// MARK: - Other-App Detection

/**
 * Polls for other-app reconnection after pairing disrupts an existing BLE connection to this
 * device — gives that app's own auto-reconnect a window to reappear. Ported from
 * `waitForOtherAppReconnection`/`defaultWaitForOtherAppReconnection` (the `#if DEBUG` override
 * strategy isn't ported — Kotlin tests call [ConnectionManager.stateMachine] directly instead, the
 * same "real service, fake wire" pattern used throughout this port).
 *
 * @return `true` if the device was detected as connected to another app.
 */
internal suspend fun ConnectionManager.waitForOtherAppReconnection(deviceAddress: String): Boolean =
    withContext(confinedDispatcher) { waitForOtherAppReconnectionImpl(deviceAddress) }

private suspend fun ConnectionManager.waitForOtherAppReconnectionImpl(deviceAddress: String): Boolean {
    val maxChecks = 6
    val intervalMillis = 400L

    for (check in 1..maxChecks) {
        if (!currentCoroutineContext().isActive) return false

        if (stateMachine.isDeviceConnectedToSystem(deviceAddress)) return true

        if (check < maxChecks) delay(intervalMillis)
    }
    return false
}

// MARK: - Forget Device

/**
 * Forgets the connected device, removing it from the pairing seam and local storage. Ported from
 * `forgetDevice(deleteData:)`; [deleteData] `true` (the default, matching this port's only
 * production call site's current behavior unchanged) hard-deletes the row and cascades its data via
 * [com.meshcoretwo.services.persistence.DeviceStore.deleteDeviceAndData]; `false` demotes it to a
 * ghost via [com.meshcoretwo.services.persistence.DeviceStore.demoteDeviceToGhost] instead,
 * preserving its contacts/messages/channels for [reconcileIdentity] to reattach later. Settings'
 * Danger Zone dialog offers both choices (`ForgetDeviceDialog`, `app`-layer).
 *
 * @throws ConnectionError.NotConnected if no device is connected.
 */
suspend fun ConnectionManager.forgetDevice(deleteData: Boolean = true) {
    withContext(confinedDispatcher) {
        val device = connectedDevice ?: throw ConnectionError.NotConnected
        val deviceAddress = device.bleAddress
        if (deviceAddress == null || !pairingService.isDeviceConnectable(deviceAddress)) {
            throw ConnectionError.DeviceNotFound
        }

        disconnectImpl(DisconnectReason.FORGET_DEVICE)
        pairingService.removeDevice(deviceAddress)
        if (deleteData) deviceStore.deleteDeviceAndData(device.id) else deviceStore.demoteDeviceToGhost(device.id)
        clearPersistedConnection(device.id)
    }
}

/**
 * Forgets a device by domain ID, removing it from the pairing seam and local storage (factory-reset
 * path). Best-effort — does not throw. See [forgetDevice]'s doc for why this always hard-deletes
 * the row (and cascades its data) rather than ghost-demoting it. Ported from `forgetDevice(id:)`.
 */
suspend fun ConnectionManager.forgetDevice(id: UUID) {
    withContext(confinedDispatcher) {
        disconnectImpl(DisconnectReason.FACTORY_RESET)
        val deviceAddress = deviceStore.fetchDeviceById(id)?.bleAddress
        if (deviceAddress != null) pairingService.removeDevice(deviceAddress)
        deviceStore.deleteDeviceAndData(id)
        clearPersistedConnection(id)
    }
}

// MARK: - Node Management

/**
 * Returns the number of non-favorite contacts for the current device. Ported from
 * `unfavoritedNodeCount`.
 *
 * @throws ConnectionError.NotConnected if no device is connected.
 */
suspend fun ConnectionManager.unfavoritedNodeCount(): Int = withContext(confinedDispatcher) {
    val radioID = connectedDevice?.radioID ?: throw ConnectionError.NotConnected
    val activeServices = services ?: throw ConnectionError.NotConnected
    activeServices.contactService.getContacts(radioID).count { !it.isFavorite }
}

/**
 * Removes all non-favorite contacts from the device and app, along with their messages. Ported
 * from `removeUnfavoritedNodes`.
 *
 * @throws ConnectionError.NotConnected if no device is connected.
 */
suspend fun ConnectionManager.removeUnfavoritedNodes(): RemoveUnfavoritedResult =
    removeContactsImpl { !it.isFavorite }

/**
 * Removes non-favorite contacts whose [ContactDto.recencyTimestamp] is older than [days]. Ported
 * from `removeStaleNodes(olderThanDays:)`.
 *
 * @throws ConnectionError.NotConnected if no device is connected.
 */
suspend fun ConnectionManager.removeStaleNodes(days: Int): RemoveUnfavoritedResult {
    val cutoff = (Instant.now().epochSecond - days.toLong() * 86_400).coerceAtLeast(0).toUInt()
    return removeContactsImpl { it.matchesStaleNodePrune(cutoff) }
}

/**
 * Shared implementation for removing contacts matching [predicate]. Ported from
 * `ConnectionManager.removeContacts(matching:onRemove:)`, trimmed of its per-contact `onRemove`
 * logging callback — no structured per-node logger call site exists on this port yet. Never
 * removes the ZephCore V-contact: `CMD_REMOVE` would turn the firmware feature off.
 *
 * @throws ConnectionError.NotConnected if no device is connected.
 */
private suspend fun ConnectionManager.removeContactsImpl(predicate: (ContactDto) -> Boolean): RemoveUnfavoritedResult =
    withContext(confinedDispatcher) {
        val device = connectedDevice ?: throw ConnectionError.NotConnected
        val activeServices = services ?: throw ConnectionError.NotConnected
        val radioID = device.radioID

        val allContacts = activeServices.contactService.getContacts(radioID)
        val targets = allContacts.filter { predicate(it) && !VContactIdentity.isVContact(it.publicKey, device.publicKey) }
        if (targets.isEmpty()) return@withContext RemoveUnfavoritedResult(removed = 0, total = 0)

        var removedCount = 0
        for (contact in targets) {
            if (!currentCoroutineContext().isActive) throw CancellationException("removeContactsImpl cancelled")

            try {
                activeServices.contactService.removeContact(radioID, contact.publicKey)
                removedCount++
            } catch (error: ContactServiceError.ContactNotFound) {
                try {
                    activeServices.contactService.removeLocalContact(contact.id, contact.publicKey)
                    removedCount++
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Best-effort, matching Swift's log-and-continue.
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return@withContext RemoveUnfavoritedResult(removed = removedCount, total = targets.size)
            }
        }

        RemoveUnfavoritedResult(removed = removedCount, total = targets.size)
    }

// MARK: - Accessory Management

/** Whether a connect attempt to [deviceAddress] is permitted. Always `true` — see this file's class doc. Ported from `hasAccessory`. */
fun ConnectionManager.hasAccessory(deviceAddress: String): Boolean = pairingService.isDeviceConnectable(deviceAddress)

/** Fetches all previously paired devices from storage. Available even when disconnected, for device-selection UI. Ported from `fetchSavedDevices`. */
suspend fun ConnectionManager.fetchSavedDevices(): List<DeviceDto> =
    withContext(confinedDispatcher) { deviceStore.fetchDevices() }

/**
 * Removes a previously paired device from the saved-devices list, demoting it to a ghost (see
 * [com.meshcoretwo.services.persistence.DeviceStore.demoteDeviceToGhost]) rather than hard-deleting
 * it — unconditionally, unlike [forgetDevice]'s explicit choice, matching Swift's own unconditional
 * `deleteDevice`. No Android UI calls this yet (there's no saved-devices management screen in this
 * port — see this file's class doc); it's only exercised by tests today. Ported from `deleteDevice`.
 */
suspend fun ConnectionManager.deleteDevice(id: UUID) {
    withContext(confinedDispatcher) {
        deviceStore.demoteDeviceToGhost(id)
        clearPersistedConnection(id)
    }
}

/** Devices registered with the pairing seam. Always empty — see this file's class doc. Ported from `pairedAccessoryInfos`. */
fun ConnectionManager.pairedAccessoryInfos(): List<Pair<String, String>> = pairingService.registeredDeviceInfos()

/**
 * Renames the currently connected device via the pairing seam's rename surface. A no-op on Android
 * — [com.meshcoretwo.services.pairing.BleScanPairingService.supportsSystemRename] is `false`; the
 * UI should hide the rename action rather than offer a control that silently does nothing (matching
 * Swift's own macOS-path guidance). Ported from `renameCurrentDevice`.
 *
 * @throws ConnectionError.NotConnected if no device is connected.
 */
suspend fun ConnectionManager.renameCurrentDevice() {
    withContext(confinedDispatcher) {
        val deviceAddress = connectedDevice?.bleAddress ?: throw ConnectionError.NotConnected
        if (!pairingService.isDeviceConnectable(deviceAddress)) throw ConnectionError.DeviceNotFound
        pairingService.renameDevice(deviceAddress)
    }
}

// MARK: - Stale Pairings

/** Clears all stale pairings from the pairing seam. A no-op on Android — see this file's class doc. Ported from `clearStalePairings`. */
suspend fun ConnectionManager.clearStalePairings() = pairingService.clearStaleRegistrations()

// MARK: - Device Updates

/**
 * Updates the connected device with a new [DeviceDto] and persists it. Called by device-settings
 * services after a local change succeeds. Ported from `updateDevice(with:)`.
 */
suspend fun ConnectionManager.updateDevice(device: DeviceDto) {
    withContext(confinedDispatcher) {
        connectedDevice = device
        try {
            deviceStore.saveDevice(device)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort, matching Swift's log-and-continue.
        }
    }
}
