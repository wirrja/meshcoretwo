// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.pairing

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android's `DevicePairingService` — the platform-neutral seam `ConnectionManager` depends on for
 * device discovery and system-pairing-registry management. Ported from
 * `BluetoothScanPairingService.swift`, the macOS "Designed for iPad" implementation — not the iOS
 * `AccessorySetupPairingService` one. Android has no AccessorySetupKit equivalent (no app-visible
 * system pairing registry at all), which is exactly the macOS path's own premise, so it — not the
 * iOS ASK path — is the correct reference implementation here; see `DevicePairingService.swift`'s
 * doc for why the seam's contract is deliberately identifier-based rather than tied to any
 * platform accessory type. The seam's identifier is a BLE MAC address (`String`), not a UUID (see
 * `ConnectionManager`'s "Identifier translation" doc).
 *
 * This class owns only the presentation handshake, exactly like its Swift original:
 * [discoverDevice] raises [isPresenting] and suspends; the UI layer observes [isPresenting],
 * presents a scan picker driven by [com.meshcoretwo.services.connection.startBLEScanning], and
 * calls [select]/[cancel] to resolve it. Every system-registry operation below is an inert no-op —
 * there is no app-visible bond registry to manage on this platform (matching every
 * `hasSystemPairingRegistry == false` branch in the Swift original).
 *
 * There is, and can only ever be, one implementation of this seam on Android — unlike iOS/macOS,
 * which pick between two at construction via `DevicePairingFactory` — so this is a concrete class,
 * not an interface-plus-implementation pair (matching this port's established precedent: an
 * abstraction with a single, permanent implementation and no need for a test double isn't
 * introduced — see `BleStateMachineProtocol`'s Phase 2 note in PLAN.md). No fake is needed for
 * `ConnectionManager`'s tests either: this class has no real BLE/Android dependency of its own, so
 * a test drives the real instance directly via [select]/[cancel], exactly as
 * `BluetoothScanPairingServiceTests` does in Swift.
 */
class BleScanPairingService {
    private val lock = Any()
    private val presenting = MutableStateFlow(false)

    /** `true` while the in-app scan picker should be presented. Observed by the UI layer. */
    val isPresenting: StateFlow<Boolean> = presenting.asStateFlow()

    private var discoveryContinuation: CancellableContinuation<String>? = null

    /**
     * Set the instant the discovery coroutine's cancellation fires, before [cancel] resolves the
     * continuation, so a [select] racing that cancellation on another thread resolves as
     * cancelled rather than with a stale selection. Guarded by [lock] because both `resume` paths
     * (UI-thread [select]/[cancel], coroutine-machinery cancellation) can run concurrently.
     */
    private var cancellationRequested = false

    /**
     * Presents the in-app scan picker and suspends for the user's selection.
     *
     * Single-flight invariant: the only production caller is
     * [com.meshcoretwo.services.connection.pairNewDevice], serialized by its
     * `isPairingInProgress` gate, so two discoveries never overlap at runtime — the prior
     * continuation has always resolved before the next call begins. The stranded-discovery
     * resolution below and [cancellationRequested] are defensive: they keep a single continuation
     * consistent against cancellation, not against concurrent callers.
     *
     * @throws DevicePairingError.Cancelled if the user cancels or the calling coroutine is cancelled.
     */
    suspend fun discoverDevice(): String {
        synchronized(lock) { cancellationRequested = false }
        // Resolve any stranded prior discovery before starting one.
        resolveDiscovery(Result.failure(DevicePairingError.Cancelled))

        return suspendCancellableCoroutine { continuation ->
            synchronized(lock) { discoveryContinuation = continuation }
            presenting.value = true
            continuation.invokeOnCancellation {
                synchronized(lock) { cancellationRequested = true }
                cancel()
            }
        }
    }

    /** Called by the scan picker when the user selects a device. */
    fun select(deviceAddress: String) = resolveDiscovery(Result.success(deviceAddress))

    /** Called when the user cancels or dismisses the scan picker. */
    fun cancel() = resolveDiscovery(Result.failure(DevicePairingError.Cancelled))

    private fun resolveDiscovery(result: Result<String>) {
        presenting.value = false
        val continuation = synchronized(lock) {
            val existing = discoveryContinuation ?: return@synchronized null
            discoveryContinuation = null
            existing
        } ?: return

        // A selection that lands after cancellation has been signalled must not win the race:
        // surface the cancellation instead of the stale selection.
        if (result.isSuccess && synchronized(lock) { cancellationRequested }) {
            continuation.resumeWith(Result.failure(DevicePairingError.Cancelled))
            return
        }
        continuation.resumeWith(result)
    }

    // MARK: - System pairing registry (always inert — no AccessorySetupKit equivalent)

    val isSessionActive: Boolean get() = false
    val registeredDeviceCount: Int get() = 0
    val hasSystemPairingRegistry: Boolean get() = false
    val supportsSystemRename: Boolean get() = false

    suspend fun activate() {}

    fun isDeviceConnectable(deviceAddress: String): Boolean = true

    /** Registered devices as (address, name) pairs, for device-list fallbacks. Always empty — see class doc. */
    fun registeredDeviceInfos(): List<Pair<String, String>> = emptyList()

    suspend fun removeDevice(deviceAddress: String) {}

    suspend fun renameDevice(deviceAddress: String) {}

    suspend fun clearStaleRegistrations() {}
}
