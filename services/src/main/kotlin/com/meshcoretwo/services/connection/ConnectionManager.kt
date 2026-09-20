// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import android.content.Context
import android.content.SharedPreferences
import com.meshcoretwo.protocol.MeshCoreSession
import com.meshcoretwo.protocol.SessionConfiguration
import com.meshcoretwo.services.ServiceContainer
import com.meshcoretwo.services.pairing.BleScanPairingService
import com.meshcoretwo.services.security.KeychainService
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.DeviceStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.transport.BleMeshTransportOps
import com.meshcoretwo.services.transport.BleStateMachineOps
import com.meshcoretwo.services.transport.BluetoothAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Manages the connection lifecycle for mesh devices. Ported from `ConnectionManager.swift` +
 * `ConnectionManager+BLE.swift` + `ConnectionManager+Lifecycle.swift` +
 * `ConnectionManager+BLEReconnectionDelegate.swift` + `Sync/ConnectionManager+SyncRetry.swift` +
 * `ConnectionManager+Pairing.swift` + `ConnectionManager+WiFi.swift` — see PLAN.md's
 * `ConnectionManager` recon (slice C) for the full accounting of what's split into its own file
 * (Pairing, WiFi) versus still excluded and why:
 *
 * - **WiFi** (`ConnectionManager+WiFi.swift`) — **ported**, see `ConnectionManagerWiFi.kt`'s class
 *   doc for the full accounting (heartbeat, disconnection handling, reconnect-with-backoff,
 *   foreground health check, and the one real test-seam gap this slice leaves: unlike BLE's
 *   [BleMeshTransportOps], [com.meshcoretwo.protocol.WiFiTransport] has no injected test double).
 * - **Pairing** (`ConnectionManager+Pairing.swift`) — **ported in slice D**, see
 *   `ConnectionManagerPairing.kt`'s class doc for the full accounting (what's ported, what's
 *   trimmed and why, e.g. ghost-demotion/cascade-delete/node-management bulk actions).
 * - **Simulator mode** (`simulatorConnect`, `SimulatorMockTransport`, `MockDataProvider`) — an
 *   iOS demo-mode feature with no project constraints mandate on Android; not ported.
 * - **`NodeConfigService`/`reconcileIdentity`** (identity import/export after a config restore) —
 *   **ported**, see [reconcileIdentity] (`ConnectionManagerIdentity.kt`) and
 *   [com.meshcoretwo.services.persistence.DeviceStore.demoteDeviceToGhost]/
 *   [com.meshcoretwo.services.persistence.DeviceStore.reconcileGhostIdentity] for the ghost-demotion/
 *   reconciliation mechanism this closes; true cascading delete
 *   (`deleteDeviceAndData`/bulk node-management actions) is still deferred — see
 *   `ConnectionManagerPairing.kt`'s class doc.
 * - **SwiftData migrations** (`performRadioIDMigration`, `performChannelFloodScopeMigration`, ...)
 *   — one-time fixups for pre-existing SwiftData installs. No Android build has ever shipped, so
 *   there's no legacy install to migrate; not ported. [RemoteNodeSessionStore.resetAllConnections]
 *   is kept — that one is a real per-launch hygiene step (a session's `isConnected` flag cannot
 *   have survived a fresh process start), not a migration. [ServiceContainer.warmUp] (orphaned
 *   `PendingSend` cleanup, called from [buildServicesAndSaveDeviceImpl]) is the per-connection
 *   counterpart.
 *
 * ## Identifier translation (MAC address vs. domain UUID)
 *
 * iOS's `CBPeripheral.identifier: UUID` is simultaneously the transport-level BLE identity and
 * (via `DeviceDTO.id`) the domain identity `ConnectionManager` persists connections under. Android
 * has no such coincidence: the transport only knows MAC address `String`s (see
 * [BleStateMachineOps]'s "Identifier note"), while [DeviceDto.id] is a synthetic domain UUID
 * assigned the first time a device is queried successfully (see [connectImpl]/
 * `buildServicesAndSaveDeviceImpl`, matched by public key across MAC-address changes exactly like
 * Swift matches across `CBPeripheral` re-identification after an OS restore). This class is where
 * that translation happens: every field that tracks an *in-flight connection attempt*
 * ([connectingDeviceAddress], [sessionRebuildDeviceAddress], [ReconnectionCoordinator]'s claim,
 * [surfacedAuthFailureDeviceAddress]) is keyed by MAC address, matching the transport layer；
 * everything that tracks *persisted connection state* ([connectedDevice], [lastConnectionStore])
 * is keyed by the domain UUID, matching every other persistence-layer type in this port.
 * [DeviceDto.bleAddress] is the stored mapping between the two.
 *
 * ## Concurrency
 *
 * Swift's `ConnectionManager` is `@MainActor`: every method (and every method on
 * [ReconnectionCoordinator]) runs on the same single-threaded, reentrant-at-suspension executor,
 * which is what the generation counters and identity re-checks scattered through this port's
 * `ConnectionManagerConnect.kt`/`ConnectionManagerReconnection.kt` are written to survive. This
 * class reproduces that structure with [scope], backed by a
 * `Dispatchers.Default.limitedParallelism(1)` dispatcher rather than a [kotlinx.coroutines.sync.Mutex]
 * — see [ReconnectionCoordinator]'s class doc for why a mutex would change the semantics these
 * guards depend on. Every public entry point on this class enters via `withContext(scope
 * .coroutineContext)` (see `ConnectionManagerConnect.kt`/`ConnectionManagerLifecycle.kt`/
 * `ConnectionManagerHealth.kt`) so a caller on any thread is safely serialized against every other
 * in-flight call.
 *
 * ## Split across files
 *
 * Kotlin has no partial classes, so (mirroring [com.meshcoretwo.protocol.MeshCoreSession]'s own
 * `internal suspend fun MeshCoreSession.xyz()` extension-function convention) this class's stored
 * state and its [ReconnectionDelegate] conformance (which *must* live in this file — Kotlin cannot
 * satisfy an interface via an extension function in another file) stay here; every other method is
 * an `internal` extension function in a topic-split sibling file
 * (`ConnectionManagerSession.kt`/`ConnectionManagerConnect.kt`/`ConnectionManagerHealth.kt`/
 * `ConnectionManagerLifecycle.kt`/`ConnectionManagerSyncRetry.kt`/`ConnectionManagerReconnection.kt`/
 * `ConnectionManagerWiFi.kt`),
 * mirroring Swift's own `ConnectionManager+*.swift` file split.
 */
class ConnectionManager(
    internal val context: Context,
    internal val database: MeshCoreDatabase,
    prefs: SharedPreferences,
    internal val stateMachine: BleStateMachineOps,
    internal val transport: BleMeshTransportOps,
    internal var appStateProvider: (suspend () -> Boolean)? = null,
    /** Overridable so tests can configure a short timeout — production leaves the protocol layer's own default. */
    internal val sessionConfiguration: SessionConfiguration = SessionConfiguration.default,
    /**
     * Builds the [KeychainService] each connection's [ServiceContainer] uses. Defaults to the
     * real Keystore-backed constructor; overridable so tests can supply
     * [KeychainService]'s plain-`SharedPreferences` constructor instead — Robolectric has no
     * working `AndroidKeyStore` provider, so the default throws
     * [com.meshcoretwo.services.security.KeychainError.InitializationFailed] there. See
     * [ServiceContainer]'s "Testing note" for the same gotcha one layer down.
     */
    internal val keychainServiceFactory: () -> KeychainService = { KeychainService(context) },
    /**
     * Android's device-discovery seam for [pairNewDevice] — see [BleScanPairingService]'s class
     * doc. Public (not `internal`): the UI layer observes [BleScanPairingService.isPresenting]
     * directly to drive its in-app scan picker (mirroring iOS's macOS-only `DeviceScannerSheet`,
     * the reference for "no system pairing picker" — see PLAN.md's slice-D rationale), and calls
     * [BleScanPairingService.select]/[BleScanPairingService.cancel] to resolve it.
     */
    val pairingService: BleScanPairingService = BleScanPairingService(),
) : ReconnectionDelegate {
    internal val deviceStore = DeviceStore(database)
    internal val remoteNodeSessionStore = RemoteNodeSessionStore(database)
    internal val lastConnectionStore = LastConnectionStore(prefs)
    internal val prefs: SharedPreferences = prefs

    /**
     * See this class's "Concurrency" doc. Every public entry point runs `withContext
     * (confinedDispatcher)` — not `withContext(scope.coroutineContext)`, which would also swap in
     * [scope]'s own `Job` and decouple the call from its caller's cancellation.
     */
    internal val confinedDispatcher = Dispatchers.Default.limitedParallelism(1)
    internal val scope = CoroutineScope(SupervisorJob() + confinedDispatcher)

    internal val reconnectionCoordinator = ReconnectionCoordinator(scope).also { it.delegate = this }

    // MARK: - Observable state

    private val _connectionStateEvents = MutableStateFlow(DeviceConnectionState.DISCONNECTED)

    /** Broadcasts every [connectionState] change — passed directly as `ServiceContainer`'s `connectionStateEvents` Flow. */
    val connectionStateEvents: StateFlow<DeviceConnectionState> = _connectionStateEvents.asStateFlow()

    override val connectionState: DeviceConnectionState get() = _connectionStateEvents.value

    internal var connectedDevice: DeviceDto? = null
    internal var allowedRepeatFreqRanges: List<com.meshcoretwo.protocol.FrequencyRange> = emptyList()
    internal var services: ServiceContainer? = null
    internal var session: MeshCoreSession? = null
    internal var currentTransportType: TransportType? = null
    internal var bluetoothAvailability: BluetoothAvailability = BluetoothAvailability.READY
    internal var detectedPlatform: DevicePlatform = DevicePlatform.UNKNOWN

    // MARK: - WiFi connection state (see ConnectionManagerWiFi.kt)

    internal var wifiTransport: com.meshcoretwo.protocol.WiFiTransport? = null
    internal var wifiHeartbeatJob: Job? = null
    internal var wifiReconnectJob: Job? = null
    internal var wifiReconnectAttempt: Int = 0
    internal var lastWiFiReconnectStartTimeMillis: Long? = null
    internal var isHandlingWiFiDisconnection: Boolean = false

    /** Records the last fully-clean channel sync, keyed by radio. Survives transient disconnects; cleared on explicit disconnect/device switch. */
    internal var lastCleanChannelSync: Pair<UUID, Instant>? = null

    /** Records the last attempted channel sync (including partial/failed). Used to cool down immediate channel-only retry loops. */
    internal var lastAttemptedChannelSync: Pair<UUID, Instant>? = null

    override var connectionIntent: ConnectionIntent = ConnectionIntent.restored(prefs)

    /** The device being actively connected via [connectImpl]. `null` during auto-reconnect (tracked by [reconnectionCoordinator] instead). */
    internal var connectingDeviceAddress: String? = null

    /** The device whose session is currently being rebuilt after a BLE auto-reconnect. */
    internal var sessionRebuildDeviceAddress: String? = null

    /**
     * Device whose pairing-failure recovery has already been surfaced this failure episode.
     * Cleared on ready promotion and on explicit disconnect, opening a new episode.
     */
    internal var surfacedAuthFailureDeviceAddress: String? = null

    /**
     * True while a device-pairing ceremony is running — suppresses opportunistic reconnect paths
     * so they can't race the pairing connect for the state machine's single in-flight connect
     * slot. Set/cleared by `pairNewDevice` (see `ConnectionManagerPairing.kt`).
     */
    internal var isPairingInProgress: Boolean = false

    internal val shouldDeferOpportunisticReconnect: Boolean get() = isPairingInProgress

    /** Callback when resync fails after all attempts. Installed by the `app` layer. */
    var onResyncFailed: (() -> Unit)? = null

    // MARK: - Circuit breaker

    private sealed class CircuitBreakerState {
        object Closed : CircuitBreakerState()
        data class Open(val sinceMillis: Long) : CircuitBreakerState()
        object HalfOpen : CircuitBreakerState()
    }

    private var circuitBreaker: CircuitBreakerState = CircuitBreakerState.Closed

    /** Connect-retry budget for a single [connectImpl] call. Unconditionally the "unverified" (macOS-shaped) budget on a user-initiated connect — Android has no system pairing registry. */
    internal var consecutiveRebuildFailures = 0

    /** Checks whether a connection attempt should proceed. `force` (user-initiated reconnect) bypasses the circuit breaker. */
    internal fun shouldAllowConnection(force: Boolean): Boolean {
        if (force) return true
        return when (val state = circuitBreaker) {
            is CircuitBreakerState.Closed -> true
            is CircuitBreakerState.Open -> {
                if (System.currentTimeMillis() - state.sinceMillis >= CIRCUIT_BREAKER_COOLDOWN_MILLIS) {
                    circuitBreaker = CircuitBreakerState.HalfOpen
                    true
                } else {
                    false
                }
            }
            is CircuitBreakerState.HalfOpen -> true
        }
    }

    /** Records a connection failure. Trips the breaker to open when called after all retries are exhausted. */
    internal fun recordConnectionFailure() {
        circuitBreaker = CircuitBreakerState.Open(System.currentTimeMillis())
    }

    /** Records a successful connection, resetting the circuit breaker and the rebuild-preserve budget. */
    internal fun recordConnectionSuccess() {
        consecutiveRebuildFailures = 0
        circuitBreaker = CircuitBreakerState.Closed
    }

    /** Refills the rebuild-preserve budget after a successful device switch. */
    internal fun resetPreserveBudgetAfterDeviceSwitch() = recordConnectionSuccess()

    /** Epoch bumped by every `clearPersistedConnection` so a queued bond-refresh persist that already passed its guard cannot write after a forget clears the shield. */
    internal var bondRefreshPersistEpoch: Long = 0

    // MARK: - Reconnection watchdog

    internal var reconnectionWatchdogJob: Job? = null
    internal var reconnectionWatchdogGeneration = 0

    // MARK: - Resync state

    internal var resyncAttemptCount = 0
    internal var resyncJob: Job? = null
    internal var channelRetryJob: Job? = null

    /** Session IDs that need re-authentication after a BLE reconnect. Populated on connection loss, consumed by `rebuildSessionImpl`. */
    internal var sessionsAwaitingReauth: MutableSet<UUID> = mutableSetOf()

    // MARK: - BLE scanning

    internal var bleScanJob: Job? = null
    internal var bleScanRequestID: Long = 0

    // MARK: - Callbacks (installed by the `app` layer)

    var onConnectionReady: (suspend () -> Unit)? = null
    var onConnectionLost: (suspend () -> Unit)? = null
    var onAutoReconnectStarted: (suspend () -> Unit)? = null

    /** Called when a background reconnect attempt fails because the peer invalidated its bond. Fires at most once per failure episode. */
    var onAuthenticationFailure: ((String) -> Unit)? = null
    var onDeviceSynced: (suspend () -> Unit)? = null
    var onLastConnectedDeviceCleared: (() -> Unit)? = null

    // MARK: - Last device persistence

    val lastConnectedDeviceID: UUID? get() = lastConnectionStore.deviceID
    val lastConnectedRadioID: UUID? get() = lastConnectionStore.radioID
    val lastConnectedDeviceName: String? get() = lastConnectionStore.deviceName

    /** Whether the disconnected pill should be suppressed (user explicitly disconnected). */
    val shouldSuppressDisconnectedPill: Boolean get() = connectionIntent.isUserDisconnected

    val lastDisconnectDiagnostic: String? get() = lastConnectionStore.disconnectDiagnostic

    internal val activeConnectionAttemptDeviceAddress: String?
        get() = connectingDeviceAddress ?: sessionRebuildDeviceAddress ?: reconnectionCoordinator.reconnectingDeviceAddress

    internal val activeReconnectDeviceAddress: String?
        get() = sessionRebuildDeviceAddress ?: reconnectionCoordinator.reconnectingDeviceAddress

    internal fun persistDisconnectDiagnostic(summary: String) = lastConnectionStore.persistDisconnectDiagnostic(summary)

    internal fun persistIntent() = connectionIntent.persist(prefs)

    internal fun persistConnection(deviceID: UUID, radioID: UUID, deviceName: String) =
        lastConnectionStore.persist(deviceID, radioID, deviceName)

    /**
     * Fires [onAuthenticationFailure] at most once per failure episode, tracked by
     * [surfacedAuthFailureDeviceAddress].
     */
    internal fun surfaceAuthenticationFailure(deviceAddress: String) {
        if (surfacedAuthFailureDeviceAddress == deviceAddress) return
        surfacedAuthFailureDeviceAddress = deviceAddress
        onAuthenticationFailure?.invoke(deviceAddress)
    }

    /** Clears the auth-failure surfacing latch so the next failure episode for the same device re-presents the guided recovery. */
    fun clearSurfacedAuthenticationFailure() {
        surfacedAuthFailureDeviceAddress = null
    }

    /**
     * Records that the device's BLE bond just completed a verified encrypted session, and marks
     * the app session live for RSSI bond-shield refresh.
     */
    internal suspend fun recordBondVerification(deviceID: UUID, deviceAddress: String) {
        lastConnectionStore.persistBondVerification(deviceID)
        stateMachine.recordBondVerification(deviceAddress, System.currentTimeMillis())
        stateMachine.setAppSessionLive(deviceAddress)
    }

    /**
     * Clears persisted last-connection/bond-verification state for the forgotten device.
     * [deviceAddress] is looked up from the persisted row (if any) so the in-memory bond shield —
     * keyed by MAC on the state machine — is cleared even though the caller only knows the domain
     * identity.
     */
    internal suspend fun clearPersistedConnection(deviceID: UUID) {
        bondRefreshPersistEpoch += 1
        val wasLastConnected = lastConnectionStore.deviceID == deviceID
        val deviceAddress = deviceStore.fetchDeviceById(deviceID)?.bleAddress
        if (deviceAddress != null) {
            stateMachine.clearBondVerification(deviceAddress)
            if (stateMachine.isAppSessionLive(deviceAddress)) {
                stateMachine.setAppSessionLive(null)
            }
        }
        lastConnectionStore.clear(deviceID)
        if (wasLastConnected) onLastConnectedDeviceCleared?.invoke()
    }

    /**
     * Persist path for RSSI bond refresh: snapshots [bondRefreshPersistEpoch], re-validates on the
     * state machine, then requires the same epoch before writing prefs so a clear landing during
     * the suspension cannot resurrect a forgotten cross-launch shield.
     */
    internal suspend fun persistBondRefreshIfStillValid(deviceID: UUID, deviceAddress: String) {
        val epoch = bondRefreshPersistEpoch
        if (!stateMachine.shouldPersistBondRefresh(deviceAddress)) return
        if (epoch != bondRefreshPersistEpoch) return
        lastConnectionStore.persistBondVerification(deviceID)
    }

    // MARK: - Cleanup

    /** Cleans up session and services without changing connection state (used during retries). */
    internal suspend fun cleanupResources() {
        session?.stop()
        services?.tearDown()
        session = null
        services = null
    }

    /** Full cleanup including state reset (used on explicit disconnect). */
    internal suspend fun cleanupConnection() {
        _connectionStateEvents.value = DeviceConnectionState.DISCONNECTED
        connectingDeviceAddress = null
        connectedDevice = null
        allowedRepeatFreqRanges = emptyList()
        cleanupResources()
    }

    // MARK: - ReconnectionDelegate

    override fun setConnectionState(state: DeviceConnectionState) {
        val previousState = _connectionStateEvents.value
        _connectionStateEvents.value = state
        if (state == DeviceConnectionState.DISCONNECTED && previousState != DeviceConnectionState.DISCONNECTED) {
            persistDisconnectDiagnostic(
                "source=reconnectionCoordinator.setConnectionState, previousState=$previousState, " +
                    "transport=${currentTransportType}, intent=$connectionIntent",
            )
        }
    }

    override fun setConnectedDevice(device: DeviceDto?) {
        connectedDevice = device
    }

    override suspend fun teardownSessionForReconnect() = teardownSessionForReconnectImpl()

    override suspend fun rebuildSession(deviceAddress: String) = rebuildSessionImpl(deviceAddress)

    override suspend fun disconnectTransport() = transport.disconnect()

    override suspend fun notifyAutoReconnectStarted() {
        onAutoReconnectStarted?.invoke()
    }

    override suspend fun notifyConnectionLost() {
        onConnectionLost?.invoke()
        if (connectionIntent.wantsConnection &&
            connectionState == DeviceConnectionState.DISCONNECTED &&
            (currentTransportType == null || currentTransportType == TransportType.BLUETOOTH)
        ) {
            startReconnectionWatchdogImpl()
        }
    }

    override suspend fun handleReconnectionFailure() = handleReconnectionFailureImpl()

    override suspend fun isTransportAutoReconnecting(): Boolean = stateMachine.isAutoReconnecting

    companion object {
        private const val CIRCUIT_BREAKER_COOLDOWN_MILLIS = 30_000L

        /** Applies to every attempt on a platform with a system pairing registry, and to all background reconnects. Android never has one — see this class's doc — so only [UNVERIFIED_CONNECT_ATTEMPTS] is actually reachable on a `forceReconnect` connect. */
        internal const val DEFAULT_CONNECT_ATTEMPTS = 4

        /** Bounds a user-initiated connect on a platform without a system pairing registry (always, on Android) so a tap on an out-of-range radio fails fast instead of hanging the full budget. */
        internal const val UNVERIFIED_CONNECT_ATTEMPTS = 2

        /**
         * Consecutive entries into [handleReconnectionFailureImpl] while intent still wants a
         * connection that are allowed to preserve a live link. Uses `<=` comparison: N preserve
         * attempts then sever on the (N+1)th wanting entry.
         */
        internal const val MAX_REBUILD_FAILURES_PRESERVING_LINK = 3

        internal const val MAX_RESYNC_ATTEMPTS = 3
        internal const val RESYNC_INTERVAL_MILLIS = 2_000L
        internal const val MAX_CHANNEL_RETRY_ATTEMPTS = 2
        internal const val CHANNEL_RETRY_INITIAL_DELAY_MILLIS = 2_000L
    }
}
