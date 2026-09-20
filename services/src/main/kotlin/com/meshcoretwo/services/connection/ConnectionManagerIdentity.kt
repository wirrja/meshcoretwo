// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.ServiceContainer
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Ghost-identity reconciliation — the [ConnectionManager] half of PLAN.md's `reconcileIdentity`/
 * `demoteDeviceToGhost` epic (see [com.meshcoretwo.services.persistence.DeviceStore
 * .reconcileGhostIdentity] for the persistence half). Installed as
 * [com.meshcoretwo.services.nodeconfig.NodeConfigService.setOnPostIdentityImport]'s callback by
 * `buildServicesAndSaveDeviceImpl` (`ConnectionManagerSession.kt`) — see its doc — so this only
 * fires after a config import pushes a private key onto the connected radio and the import
 * included the node-identity section (see
 * [com.meshcoretwo.services.nodeconfig.resolveEffectiveRadioID]'s doc for the exact gate). Ported
 * from `ConnectionManager.reconcileIdentity`.
 *
 * Unlike this file's siblings (`ConnectionManagerPairing.kt` etc.), this isn't called from
 * anywhere already running on [ConnectionManager.confinedDispatcher] —
 * `buildServicesAndSaveDeviceImpl` only *installs* the callback;
 * [com.meshcoretwo.services.nodeconfig.NodeConfigService.importConfig] (the actual caller, reached
 * directly from the `app`-layer import ViewModel, not through any `ConnectionManager`-dispatched
 * wrapper) invokes it later, on its own caller's dispatcher — so this function confines itself,
 * matching every other public [ConnectionManager] entry point, rather than assuming an
 * already-confined caller the way an `...Impl` function would.
 *
 * @param expectedServices The [ServiceContainer] captured at the connect episode this callback was
 *   installed for. Guarded against (via reference identity) both before and after the one suspend
 *   call below, matching `promoteToReadyImpl`'s guard shape (`ConnectionManagerSession.kt`) — a
 *   concurrent disconnect/reconnect during the DB round trip swaps in a different container, and
 *   this reconcile result must not then be applied to a connection it no longer belongs to.
 * @param deviceID The domain identity ([com.meshcoretwo.services.persistence.DeviceDto.id]) of the
 *   device row being connected, captured at the same time as [expectedServices].
 * @return the reconciled `radioID`, or `null` if reconciliation didn't apply (guard failed, or no
 *   matching ghost) — [com.meshcoretwo.services.nodeconfig.resolveEffectiveRadioID] falls back to
 *   the original `radioID` on `null`.
 */
internal suspend fun ConnectionManager.reconcileIdentity(expectedServices: ServiceContainer, deviceID: UUID): UUID? =
    withContext(confinedDispatcher) {
        if (!connectionIntent.wantsConnection || services !== expectedServices) return@withContext null

        val newPublicKey = expectedServices.settingsService.getSelfInfo().publicKey
        val reconciledRadioID = deviceStore.reconcileGhostIdentity(deviceID, newPublicKey) ?: return@withContext null

        if (!connectionIntent.wantsConnection || services !== expectedServices) return@withContext null

        val device = deviceStore.fetchDeviceById(deviceID) ?: return@withContext null
        connectedDevice = device
        persistConnection(deviceID, reconciledRadioID, device.nodeName)
        reconciledRadioID
    }
