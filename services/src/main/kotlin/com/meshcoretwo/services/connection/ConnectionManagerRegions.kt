// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.settings.RegionDiscoveryService
import kotlinx.coroutines.CancellationException

/**
 * Known-flood-region list maintenance for the connected device. Ported from the
 * `addKnownRegion`/`removeKnownRegion` pair in `ConnectionManager+Pairing.swift`, as a separate
 * file (rather than folded into [ConnectionManagerPairing.kt]) since it's really
 * `DefaultFloodScopeSection`/[RegionDiscoveryService] support, not pairing.
 *
 * Uses [updateDevice] (a full-row persist) instead of Swift's incremental
 * `dataStore.addDeviceKnownRegion`/`removeDeviceKnownRegion` column writes — same simplification
 * already applied to every other single-field [com.meshcoretwo.services.persistence.DeviceDto]
 * setter added since `autoAddConfig` (see [ConnectionManagerPairing.kt]'s class doc).
 *
 * [addKnownRegion]/[removeKnownRegion] both notify `rxLogService.updateKnownRegions(_:)`
 * ([ConnectionManagerDiagnostics.kt]'s `rxLogService`) after persisting, matching Swift — this
 * was deferred until the "Incoming Region" chat-footer slice gave
 * [com.meshcoretwo.services.rxlog.RxLogService] a region-reprocess pipeline to notify at all.
 */

/**
 * Runs [RegionDiscoveryService.discover] for the connected device. Ported from
 * `DefaultFloodScopeSection.runDiscovery`'s device/session/service lookups, hoisted here so
 * `app`-layer callers don't need direct access to the internal [ConnectionManager.session].
 *
 * @return [RegionDiscoveryService.Outcome.SendFailed] if no device is connected, matching
 *   Swift's silent `guard let ... else { return }` (no user-facing message for that case either).
 */
suspend fun ConnectionManager.discoverRegions(): RegionDiscoveryService.Outcome {
    val device = connectedDevice ?: return RegionDiscoveryService.Outcome.SendFailed
    val activeSession = session ?: return RegionDiscoveryService.Outcome.SendFailed
    val activeContactService = contactService ?: return RegionDiscoveryService.Outcome.SendFailed
    return RegionDiscoveryService.discover(
        session = activeSession,
        contactService = activeContactService,
        discoveredNodeStore = discoveredNodeStore,
        radioID = device.radioID,
        knownRegions = device.knownRegions,
        supportsAdHocRequest = device.supportsAdHocRepeaterRequest,
    )
}

/**
 * Appends [region] to the connected device's known-regions list and persists. No-ops if the
 * region is already present. Ported from `addKnownRegion`.
 */
suspend fun ConnectionManager.addKnownRegion(region: String) {
    val device = connectedDevice ?: return
    if (device.knownRegions.contains(region)) return
    val updated = device.copy(knownRegions = device.knownRegions + region)
    updateDevice(updated)
    rxLogService?.updateKnownRegions(updated.knownRegions)
}

/**
 * Removes [region] from the connected device's known-regions list and persists. If [region] is
 * the device's current default flood scope, also clears the scope on the radio (best-effort) so
 * firmware state doesn't dangle on a deleted name. Also resets any channel pinned to [region] back
 * to [com.meshcoretwo.services.persistence.ChannelFloodScope.Inherit] — ported from
 * `removeKnownRegion` plus the channel-reset half of `PersistenceStore+Devices.swift`'s
 * `removeDeviceKnownRegion`, which Swift folds into the same persistence write. This half was left
 * unported by the original "Manage Regions" slice, since no channel could hold a `Region` scope
 * until the "Per-channel flood scope" slice added `ChannelInfoScreen`'s picker.
 */
suspend fun ConnectionManager.removeKnownRegion(region: String) {
    val device = connectedDevice ?: return
    val wasDefaultFloodScope = device.defaultFloodScopeName == region
    val updated = device.copy(
        knownRegions = device.knownRegions - region,
        defaultFloodScopeName = if (wasDefaultFloodScope) null else device.defaultFloodScopeName,
    )
    updateDevice(updated)
    rxLogService?.updateKnownRegions(updated.knownRegions)
    if (wasDefaultFloodScope) {
        try {
            settingsService?.setDefaultFloodScopeVerified(null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort, matching Swift's log-and-continue.
        }
    }
    try {
        channelService?.resetChannelsScopedToRegion(device.radioID, region)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Best-effort — a stray Region(region) preference left behind is a display-only
        // inconsistency (ChannelFloodScopeResolver still resolves it the same way `.region(name)`
        // did before removal), not a stuck state; the next successful call fixes it.
    }
}
