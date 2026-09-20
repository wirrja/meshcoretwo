// SPDX-License-Identifier: GPL-3.0-only

@file:Suppress("MissingPermission")

package com.meshcoretwo.services.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "BleStateMachine"

/**
 * Manages BLE connections using an explicit state machine, mirroring `BLEStateMachine.swift`.
 *
 * ## Kotlin/Android-specific notes (see also [BlePhase], [BlePhaseKind], [ReconnectPolicy])
 *
 * Swift's `BLEStateMachine` is an `actor`; this is a plain class guarded by a [Mutex] (the same
 * pattern as [com.meshcoretwo.protocol.MockTransport]), since — like that transport — connect,
 * disconnect, and send genuinely suspend waiting on hardware callbacks, so a coarse lock held
 * only around brief synchronous state access (never across an awaited callback) is the correct
 * translation of actor reentrancy. Every phase-holding wait uses [CompletableDeferred] rather
 * than a raw `CancellableContinuation` — see [BlePhase]'s doc for why.
 *
 * **Reconnection strategy deliberately differs from iOS.** iOS 17+'s
 * `CBConnectPeripheralOptionEnableAutoReconnect` hands reconnection to the OS: CoreBluetooth
 * keeps a pending connect armed indefinitely and resumes it automatically once the peripheral is
 * back in range, even across the app being suspended. Android's nearest analogue,
 * `BluetoothDevice.connectGatt(context, autoConnect = true, ...)`, does not behave the same way
 * in practice: its initial connection latency is high and inconsistent, and its background
 * reconnection behavior varies significantly across OEM Bluetooth stacks — a real concern given
 * this project's explicit Huawei/non-GMS target. Instead, every connection attempt here —
 * initial and reconnect alike — uses `autoConnect = false` for a fast, direct attempt, and an
 * unexpected disconnect drives this state machine's own [ReconnectPolicy]-bounded retry loop
 * with a fresh `connectGatt` call each attempt (closing the previous `BluetoothGatt` client
 * first, since Android's GATT client pool is small and finite on most stacks).
 *
 * **No state-restoration phase.** CoreBluetooth's background-relaunch restoration
 * (`willRestoreState`) has no Android equivalent — ported behavior notes live on [BlePhaseKind].
 *
 * **Adds MTU negotiation** ([BlePhase.NegotiatingMtu]), which iOS does not need (CoreBluetooth
 * negotiates the ATT MTU automatically).
 *
 * **Deferred, not yet ported** (tracked in PLAN.md): write-without-response flow control
 * (`sendWithoutResponse`/pipelined reads). [send] always uses acknowledged writes for now. The
 * RSSI-based bond-verification refresh subsystem, write pacing, `shutdown()`, and
 * foreground/background app-lifecycle hooks — previously deferred here pending Phase 3
 * `ConnectionManager` persistence — are now implemented (see [BleStateMachineOps], the
 * `ConnectionManager`-facing seam these live behind).
 *
 * **Runtime permission (`BLUETOOTH_CONNECT`, API 31+) is gated once**, in [waitForPoweredOn]
 * (itself always called at the top of [connect]) — see [hasConnectPermission]. Every GATT call
 * downstream of a successful connect, including ones issued from GATT callbacks in
 * `BleStateMachineCallbackHandlers.kt`, therefore only ever runs on a `BluetoothGatt` obtained
 * after that check passed, which is why both files carry a file-level
 * `@Suppress("MissingPermission")` instead of re-checking before each individual call — lint
 * cannot trace the permission check across a suspend/coroutine-callback boundary, but the
 * invariant holds by construction.
 */
class BleStateMachine(
    context: Context,
    private val connectionTimeoutMs: Long = 10_000,
    private val serviceDiscoveryTimeoutMs: Long = 40_000,
    private val reconnectDiscoveryTimeoutMs: Long = 15_000,
    private val writeTimeoutMs: Long = 5_000,
    internal val requestedMtu: Int = 517,
) : BleStateMachineOps {
    internal val appContext = context.applicationContext

    /** Runs work that must outlive a single suspend call — GATT callbacks, timeouts, retries. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutex = Mutex()
    internal val callback = BleGattCallback(this)

    private val bluetoothManager: BluetoothManager
        get() = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    internal val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    // MARK: - State (mutex-guarded; see class doc for the locking discipline)

    internal var phase: BlePhase = BlePhase.Idle
        private set

    /**
     * Monotonically increasing generation counter. Incremented on each new connection or
     * reconnect cycle. Used to reject stale disconnect/timeout callbacks from a superseded
     * cycle.
     */
    internal var connectionGeneration: Long = 0
        private set

    internal val reconnectPolicy = ReconnectPolicy()

    /**
     * Single-reference field for the connected phase's data channel, read directly from the
     * GATT callback thread without the mutex — mirroring Swift's dedicated
     * `dataContinuationLock`, kept separate from mutex-guarded [phase] for the same reason: a
     * notification callback must never block on other in-flight state-machine work.
     */
    @Volatile
    internal var activeDataChannel: Channel<ByteArray>? = null

    // MARK: - Write serialization

    private var pendingWriteDeferred: CompletableDeferred<Unit>? = null
    private val writeSequenceCounter = AtomicLong(0)
    internal var pendingWriteSequence: Long = 0
        private set
    private val writeWaiters = ArrayDeque<CompletableDeferred<Unit>>()
    private var writeTimeoutJob: Job? = null

    // MARK: - Timeouts

    private var serviceDiscoveryTimeoutJob: Job? = null
    private var reconnectDiscoveryTimeoutJob: Job? = null
    private var rssiKeepaliveJob: Job? = null

    // MARK: - Callbacks

    internal var onDisconnection: ((String, BleError?) -> Unit)? = null
    internal var onReconnection: ((String, Flow<ByteArray>) -> Unit)? = null
    internal var onBluetoothStateChange: ((Int) -> Unit)? = null
    internal var onBluetoothPoweredOn: (() -> Unit)? = null
    internal var onAutoReconnecting: ((String, String) -> Unit)? = null
    internal var onBondRefreshed: ((String) -> Unit)? = null
    private var onDeviceDiscovered: ((DiscoveredDevice) -> Unit)? = null

    private var isActivated = false

    // MARK: - App-session-live / bond verification / app lifecycle

    /**
     * MAC address of the device a completed app-layer session is currently live for, or null.
     * Mutex-guarded like [phase]: set by [setAppSessionLive] (external, from `ConnectionManager`
     * after a successful handshake) and cleared automatically whenever [phase] exits
     * [BlePhase.Connected] (see [cleanupPhaseResourcesLocked]) — a same-device reconnect's
     * pre-handshake keepalive window must not be able to refresh a stale stamp.
     */
    internal var appSessionLiveDeviceAddress: String? = null
        private set

    /**
     * Whether the app is in the foreground. Read by [ReconnectPolicy.resolveConnectFailure] to
     * decide whether an exhausted retry budget should hold (backgrounded) or escalate.
     */
    internal var isAppActive: Boolean = true
        private set

    private var writePacingDelayMs: Long = 0
    private var earliestNextWriteMillis: Long = 0

    // MARK: - Scanning (orthogonal to the connection lifecycle — works while connected)

    /**
     * [isCurrentlyScanning]/[pendingScanRequest] are read and written both from callers of
     * [startScanning]/[stopScanning] (any thread) and from [handleAdapterStateChange] (the
     * state machine's own coroutine scope), so — like [activeDataChannel] — they are `@Volatile`
     * rather than mutex-guarded: each is a single flag flip with no invariant spanning the two,
     * so a coarse lock would add ceremony without preventing anything a volatile field doesn't
     * already prevent.
     */
    @Volatile
    internal var isCurrentlyScanning = false
        private set

    @Volatile
    internal var pendingScanRequest = false

    private var scanCallback: ScanCallback? = null

    // MARK: - Activation

    /** Activates the state machine, registering for adapter state changes. Safe to call more than once. */
    override fun activate() {
        if (isActivated) return
        isActivated = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                scope.launch { handleAdapterStateChange(state) }
            }
        }
        appContext.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    // MARK: - Public API

    override val isConnected: Boolean get() = phase is BlePhase.Connected

    override val isAutoReconnecting: Boolean get() = phase is BlePhase.AutoReconnecting

    override val connectedDeviceAddress: String? get() = phase.deviceAddress

    /**
     * Returns a fresh [Flow] over the currently connected phase's data channel, or `null` if not
     * connected. [com.meshcoretwo.services.transport.BleMeshTransport] uses this to re-vend a data
     * stream for a rebuilt [com.meshcoretwo.protocol.MeshCoreSession] after its predecessor's
     * `stop()` cancelled the previous stream's collector — the underlying channel itself is
     * untouched by that stop, since the phase (and therefore the link) never dropped.
     */
    fun renewDataStream(): Flow<ByteArray>? = activeDataChannel?.receiveAsFlow()

    override val linkDiagnostics: BleLinkDiagnostics
        get() = BleLinkDiagnostics(
            adapterState = adapterStateName,
            phase = phase.kind,
            deviceState = phase.associatedGatt?.let { deviceStateName(it.device) },
        )

    override val isBluetoothPoweredOff: Boolean get() = adapter?.isEnabled == false

    private val adapterStateName: String
        get() = when (adapter?.state) {
            null -> "notActivated"
            BluetoothAdapter.STATE_OFF -> "STATE_OFF"
            BluetoothAdapter.STATE_ON -> "STATE_ON"
            BluetoothAdapter.STATE_TURNING_ON -> "STATE_TURNING_ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "STATE_TURNING_OFF"
            else -> "unknown(${adapter?.state})"
        }

    private fun deviceStateName(device: BluetoothDevice): String = when (bluetoothManager.getConnectionState(device, BluetoothProfile.GATT)) {
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "unknown"
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean =
        bluetoothManager.getConnectionState(device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED

    fun setDisconnectionHandler(handler: (String, BleError?) -> Unit) {
        onDisconnection = handler
    }

    fun setReconnectionHandler(handler: (String, Flow<ByteArray>) -> Unit) {
        onReconnection = handler
    }

    override fun setBluetoothStateChangeHandler(handler: (Int) -> Unit) {
        onBluetoothStateChange = handler
    }

    override fun setBluetoothPoweredOnHandler(handler: () -> Unit) {
        onBluetoothPoweredOn = handler
    }

    override fun setAutoReconnectingHandler(handler: (String, String) -> Unit) {
        onAutoReconnecting = handler
    }

    override fun setBondRefreshedHandler(handler: ((String) -> Unit)?) {
        onBondRefreshed = handler
    }

    override fun setWritePacingDelay(delayMs: Long) {
        writePacingDelayMs = delayMs
    }

    override fun setDeviceDiscoveredHandler(handler: (DiscoveredDevice) -> Unit) {
        onDeviceDiscovered = handler
    }

    /**
     * Starts scanning for BLE peripherals advertising the Nordic UART service. Requires
     * [activate] to have run and Bluetooth to be powered on — if it isn't yet, the request is
     * remembered and [handleAdapterStateChange] retries it once the adapter reports powered on,
     * mirroring `startScanning`/`pendingScanRequest` in `BLEStateMachine.swift`.
     *
     * Silently declines (logging only) if `BLUETOOTH_SCAN` (API 31+) isn't granted — requesting
     * that permission is a UI-layer concern (see PLAN.md's Phase 2 status) this transport has no
     * business initiating itself.
     */
    override fun startScanning() {
        activate()
        if (adapter?.isEnabled != true) {
            Log.i(TAG, "Cannot start scanning: Bluetooth not powered on, will start when ready")
            pendingScanRequest = true
            return
        }
        if (!hasScanPermission()) {
            Log.w(TAG, "Cannot start scanning: BLUETOOTH_SCAN not granted")
            return
        }
        pendingScanRequest = false
        if (isCurrentlyScanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return

        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleServiceUuid.NORDIC_UART)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val newCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!isCurrentlyScanning) return
                val device = result.device
                val name = if (hasConnectPermission()) device.name else null
                onDeviceDiscovered?.invoke(DiscoveredDevice(device.address, name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed to start: errorCode=$errorCode")
                isCurrentlyScanning = false
                scanCallback = null
            }
        }

        isCurrentlyScanning = true
        scanCallback = newCallback
        Log.i(TAG, "Starting BLE scan for device discovery")
        scanner.startScan(filters, settings, newCallback)
    }

    /** Stops an active BLE scan. Idempotent. */
    override fun stopScanning() {
        pendingScanRequest = false
        if (!isCurrentlyScanning) return
        isCurrentlyScanning = false
        val scanner = adapter?.bluetoothLeScanner
        val callbackToStop = scanCallback
        scanCallback = null
        if (scanner != null && callbackToStop != null) {
            scanner.stopScan(callbackToStop)
        }
    }

    /** See [BleStateMachineOps.isDeviceConnectedToSystem]'s platform-gap note. */
    override fun isDeviceConnectedToSystem(deviceAddress: String): Boolean {
        activate()
        return systemConnectedDevices().any { it.address == deviceAddress }
    }

    override fun systemConnectedDeviceAddresses(): List<String> {
        activate()
        return systemConnectedDevices().map { it.address }
    }

    /** Devices this app is currently GATT-connected to. Empty if `BLUETOOTH_CONNECT` isn't granted. */
    private fun systemConnectedDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission()) return emptyList()
        return bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
    }

    /**
     * Best-effort adoption of a peripheral already GATT-connected to this app process but with
     * no live state-machine session — e.g. a process restart that skipped normal
     * connect/reconnect. Mirrors `startAdoptingSystemConnectedPeripheral` in
     * `BLEStateMachine.swift`: a synchronous precondition check (relaxed, unlocked read of
     * [phase] — the same convention [isConnected] and friends already use for external queries)
     * followed by a fire-and-forget coroutine that reuses [establishConnection], the same
     * discovery chain [connect] uses.
     */
    override fun startAdoptingSystemConnectedPeripheral(deviceAddress: String): Boolean {
        activate()
        if (phase !is BlePhase.Idle) {
            Log.i(TAG, "Not adopting $deviceAddress: state machine busy (${phase.kind})")
            return false
        }
        val device = systemConnectedDevices().find { it.address == deviceAddress } ?: return false
        scope.launch {
            try {
                val flow = establishConnection(device)
                onReconnection?.invoke(device.address, flow)
            } catch (error: BleError) {
                Log.w(TAG, "Adoption failed for $deviceAddress: ${error.message}")
            }
        }
        return true
    }

    /**
     * Whether this app holds the runtime permission every GATT call needs. Only meaningful on
     * API 31+ (`BLUETOOTH_CONNECT` is a dangerous, runtime-granted permission there); on earlier
     * API levels the manifest's install-time `BLUETOOTH`/`BLUETOOTH_ADMIN` declarations already
     * cover it, so there is nothing to check.
     */
    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Same reasoning as [hasConnectPermission], for the separate `BLUETOOTH_SCAN` permission. */
    private fun hasScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Waits for the Bluetooth adapter to be powered on.
     *
     * Unlike iOS, where CoreBluetooth folds authorization into `CBManagerState` itself (an
     * unauthorized app can never observe `.poweredOn`), Android's runtime permission and adapter
     * power state are independent axes — a user can grant Bluetooth power while denying this
     * app's `BLUETOOTH_CONNECT` permission. Both terminal conditions are therefore checked before
     * the power-state fast path, not folded into it.
     */
    suspend fun waitForPoweredOn() {
        activate()
        if (adapter == null) throw BleError.BluetoothUnavailable
        if (!hasConnectPermission()) throw BleError.BluetoothUnauthorized
        if (adapter?.isEnabled == true) return

        val deferred = CompletableDeferred<Unit>()
        mutex.withLock {
            if (phase !is BlePhase.Idle) throw BleError.ConnectionFailed("Already in operation: ${phase.kind}")
            transitionLocked(BlePhase.WaitingForBluetooth(deferred))
        }
        deferred.await()
    }

    /** Connects to a device and returns a flow of data received from it. */
    suspend fun connect(deviceAddress: String): Flow<ByteArray> {
        mutex.withLock {
            if (phase !is BlePhase.Idle) throw BleError.ConnectionFailed("Already in operation: ${phase.kind}")
        }

        waitForPoweredOn()

        // Re-validate after the suspension: another caller (or Bluetooth power-on handling)
        // could have claimed the machine while this one was waiting.
        mutex.withLock {
            if (phase !is BlePhase.Idle) throw BleError.ConnectionFailed("Already in operation: ${phase.kind}")
        }

        val bluetoothAdapter = adapter ?: throw BleError.BluetoothUnavailable
        val device = try {
            bluetoothAdapter.getRemoteDevice(deviceAddress)
        } catch (error: IllegalArgumentException) {
            throw BleError.DeviceNotFound
        }

        return establishConnection(device)
    }

    /**
     * Advances the connection generation, runs the connect+discovery chain for [device], and
     * transitions to [BlePhase.Connected]. Factored out of [connect] so
     * [startAdoptingSystemConnectedPeripheral] can reuse the same chain from a phase that is
     * already known idle, without duplicating the address-resolution/power-on preconditions
     * [connect] itself owns.
     */
    private suspend fun establishConnection(device: BluetoothDevice): Flow<ByteArray> {
        mutex.withLock { advanceConnectionGenerationLocked() }

        connectToDevice(device, autoConnect = false)

        val channel = Channel<ByteArray>(capacity = 512)
        mutex.withLock {
            val complete = phase as? BlePhase.DiscoveryComplete
                ?: throw BleError.ConnectionFailed("Unexpected state after service discovery: ${phase.kind}")
            activeDataChannel = channel
            transitionLocked(BlePhase.Connected(complete.gatt, complete.tx, complete.rx, channel))
        }
        startRssiKeepalive()
        return channel.receiveAsFlow()
    }

    /** Disconnects from the current device. Idempotent. */
    suspend fun disconnect() {
        cancelPendingWriteOperations(BleError.NotConnected)

        val gattToClose = mutex.withLock {
            val current = phase.associatedGatt
            if (current != null) transitionLocked(BlePhase.Disconnecting(current))
            current
        }
        if (gattToClose != null) {
            gattToClose.disconnect()
            delay(100)
            gattToClose.close()
        }
        mutex.withLock { transitionLocked(BlePhase.Idle) }
    }

    /**
     * Sends data to the connected device, serializing concurrent callers.
     *
     * Applies [writePacingDelayMs] (set via [setWritePacingDelay]) as a pre-write delay gate:
     * some platforms (ESP32) need spacing between back-to-back BLE writes. Swift's `claimWriteSlot`
     * awaits this pacing from inside the actor-isolated method body itself; Kotlin's
     * [kotlinx.coroutines.sync.Mutex.withLock] lambda is not a suspend context (see
     * [claimWriteSlot]'s own timeout job for why other suspending work there is spun into its own
     * coroutine instead), so the delay happens here, before the write slot is even claimed —
     * equivalent pacing effect, without needing a suspending lock body.
     */
    suspend fun send(data: ByteArray) {
        if (writePacingDelayMs > 0) {
            val waitMs = earliestNextWriteMillis - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
        }
        claimWriteSlot(data)
    }

    /**
     * Serializes concurrent writes by waiting for any pending write to complete, then claims the
     * write slot and issues the GATT write. The whole decision — check pending, either enqueue
     * as a waiter or claim the slot and issue the write — happens inside one locked section so
     * two concurrent callers can never both believe they claimed the slot.
     */
    private suspend fun claimWriteSlot(data: ByteArray) {
        val myGeneration = connectionGeneration
        while (true) {
            val (awaited, isWaiter) = mutex.withLock {
                if (connectionGeneration != myGeneration) throw BleError.NotConnected
                val connected = phase as? BlePhase.Connected ?: throw BleError.NotConnected

                if (pendingWriteDeferred != null) {
                    val waiter = CompletableDeferred<Unit>()
                    writeWaiters.add(waiter)
                    return@withLock waiter to true
                }

                val sequence = writeSequenceCounter.incrementAndGet()
                val writeDeferred = CompletableDeferred<Unit>()
                pendingWriteSequence = sequence
                pendingWriteDeferred = writeDeferred
                callback.recordIssuedWriteSequence(sequence)

                @Suppress("DEPRECATION")
                connected.tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                connected.tx.value = data
                @Suppress("DEPRECATION")
                val issued = connected.gatt.writeCharacteristic(connected.tx)
                if (!issued) {
                    pendingWriteDeferred = null
                    throw BleError.WriteError("writeCharacteristic returned false")
                }
                if (writePacingDelayMs > 0) earliestNextWriteMillis = System.currentTimeMillis() + writePacingDelayMs

                writeTimeoutJob?.cancel()
                writeTimeoutJob = scope.launch {
                    delay(writeTimeoutMs)
                    mutex.withLock {
                        if (pendingWriteSequence != sequence) return@withLock
                        val pending = pendingWriteDeferred ?: return@withLock
                        pendingWriteDeferred = null
                        writeTimeoutJob = null
                        pending.completeExceptionally(BleError.OperationTimeout)
                        resumeNextWriteWaiterLocked()
                    }
                }
                writeDeferred to false
            }
            awaited.await()
            if (!isWaiter) return
            // A waiter's turn arrived; loop back and try to claim the now-free slot.
        }
    }

    private fun resumeNextWriteWaiterLocked() {
        writeWaiters.pollFirst()?.complete(Unit)
    }

    /**
     * Completes the pending write, tagged by [sequence] from [BleGattCallback]'s FIFO so a
     * callback arriving after its write already timed out (and a new write may already be
     * pending) is recognized as stale and ignored.
     */
    internal suspend fun handleCharacteristicWrite(gatt: BluetoothGatt, status: Int, sequence: Long) {
        mutex.withLock {
            if (pendingWriteSequence != sequence) return@withLock
            val pending = pendingWriteDeferred ?: return@withLock
            pendingWriteDeferred = null
            writeTimeoutJob?.cancel()
            writeTimeoutJob = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pending.complete(Unit)
            } else {
                Log.w(TAG, "writeCharacteristic failed for ${gatt.device.address}: status=$status")
                pending.completeExceptionally(BleError.WriteError("writeCharacteristic failed: status=$status"))
            }
            resumeNextWriteWaiterLocked()
        }
    }

    internal suspend fun cancelPendingWriteOperations(error: BleError) {
        mutex.withLock { cancelPendingWriteOperationsLocked(error) }
    }

    /** Same as [cancelPendingWriteOperations], for call sites that already hold [mutex]. */
    private fun cancelPendingWriteOperationsLocked(error: BleError) {
        writeTimeoutJob?.cancel()
        writeTimeoutJob = null
        callback.clearIssuedWriteSequences()
        pendingWriteDeferred?.let {
            pendingWriteDeferred = null
            it.completeExceptionally(error)
        }
        while (writeWaiters.isNotEmpty()) {
            writeWaiters.pollFirst()?.complete(Unit)
        }
    }

    // MARK: - Connection generation

    internal fun advanceConnectionGenerationLocked() {
        connectionGeneration += 1
        reconnectPolicy.generationAdvanced()
    }

    // MARK: - Transitions (caller must hold [mutex])

    internal fun transitionLocked(newPhase: BlePhase) {
        val old = phase
        cleanupPhaseResourcesLocked(old, newPhase)
        phase = newPhase
    }

    private fun cleanupPhaseResourcesLocked(oldPhase: BlePhase, newPhase: BlePhase) {
        if (!newPhase.isDiscoveryChain) {
            serviceDiscoveryTimeoutJob?.cancel()
            serviceDiscoveryTimeoutJob = null
        }
        if (newPhase !is BlePhase.AutoReconnecting) {
            reconnectDiscoveryTimeoutJob?.cancel()
            reconnectDiscoveryTimeoutJob = null
        }
        when (oldPhase) {
            is BlePhase.Connecting -> oldPhase.timeoutJob.cancel()
            is BlePhase.Connected -> {
                rssiKeepaliveJob?.cancel()
                rssiKeepaliveJob = null
                activeDataChannel = null
                oldPhase.dataChannel.close()
                // Phase exit from Connected must drop the session-live signal so a same-device
                // reconnect's pre-handshake keepalive window cannot refresh a stale stamp.
                appSessionLiveDeviceAddress = null
            }
            else -> {}
        }
    }

    internal fun startRssiKeepalive() {
        rssiKeepaliveJob?.cancel()
        rssiKeepaliveJob = scope.launch {
            while (true) {
                delay(15_000)
                val gatt = (phase as? BlePhase.Connected)?.gatt ?: break
                gatt.readRemoteRssi()
            }
        }
    }

    internal fun deliverReceivedData(data: ByteArray) {
        activeDataChannel?.trySend(data)
    }

    // MARK: - Connecting

    private suspend fun connectToDevice(device: BluetoothDevice, autoConnect: Boolean) {
        val deferred = CompletableDeferred<Unit>()
        mutex.withLock {
            val timeoutJob = scope.launch {
                delay(connectionTimeoutMs)
                handleConnectionTimeout(device.address)
            }
            val gatt = device.connectGatt(appContext, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            transitionLocked(BlePhase.Connecting(gatt, deferred, timeoutJob))
        }
        deferred.await()
    }

    private suspend fun handleConnectionTimeout(deviceAddress: String) {
        mutex.withLock {
            val connecting = phase as? BlePhase.Connecting ?: return
            if (connecting.gatt.device.address != deviceAddress) return
            connecting.gatt.disconnect()
            connecting.gatt.close()
            transitionLocked(BlePhase.Idle)
            connecting.continuation.completeExceptionally(BleError.ConnectionTimeout)
        }
    }

    internal fun armServiceDiscoveryTimeout(gatt: BluetoothGatt) {
        serviceDiscoveryTimeoutJob?.cancel()
        serviceDiscoveryTimeoutJob = scope.launch {
            delay(serviceDiscoveryTimeoutMs)
            handleServiceDiscoveryTimeout(gatt)
        }
    }

    private suspend fun handleServiceDiscoveryTimeout(gatt: BluetoothGatt) {
        mutex.withLock {
            if (serviceDiscoveryTimeoutJob == null) return
            val (expectedGatt, continuation) = when (val current = phase) {
                is BlePhase.DiscoveringServices -> current.gatt to current.continuation
                is BlePhase.NegotiatingMtu -> current.gatt to current.continuation
                is BlePhase.SubscribingToNotifications -> current.gatt to current.continuation
                else -> return
            }
            if (expectedGatt.device.address != gatt.device.address) return

            when (val decision = reconnectPolicy.resolveServiceDiscoveryStall(isDeviceConnected(gatt.device))) {
                is ReconnectPolicy.ServiceDiscoveryStallDecision.ExtendDiscoveryWindow -> armServiceDiscoveryTimeout(gatt)
                is ReconnectPolicy.ServiceDiscoveryStallDecision.TearDown -> {
                    gatt.disconnect()
                    gatt.close()
                    transitionLocked(BlePhase.Idle)
                    continuation.completeExceptionally(decision.error)
                }
            }
        }
    }

    internal fun armReconnectDiscoveryTimeout(device: BluetoothDevice, generation: Long) {
        reconnectDiscoveryTimeoutJob?.cancel()
        reconnectDiscoveryTimeoutJob = scope.launch {
            delay(reconnectDiscoveryTimeoutMs)
            handleReconnectDiscoveryTimeout(device, generation)
        }
    }

    private suspend fun handleReconnectDiscoveryTimeout(device: BluetoothDevice, generation: Long) {
        mutex.withLock {
            if (reconnectDiscoveryTimeoutJob == null) return
            if (generation != connectionGeneration) return
            val current = phase as? BlePhase.AutoReconnecting ?: return
            if (current.device.address != device.address) return

            when (val decision = reconnectPolicy.resolveAutoReconnectStall(isDeviceConnected(device))) {
                is ReconnectPolicy.AutoReconnectStallDecision.WaitForPendingConnect -> armReconnectDiscoveryTimeout(device, generation)
                is ReconnectPolicy.AutoReconnectStallDecision.ExtendDiscoveryWindow -> armReconnectDiscoveryTimeout(device, generation)
                is ReconnectPolicy.AutoReconnectStallDecision.TearDown -> {
                    current.gatt?.disconnect()
                    current.gatt?.close()
                    transitionLocked(BlePhase.Idle)
                    onDisconnection?.invoke(device.address, decision.error)
                }
            }
        }
    }

    /** Cancels the current operation, resuming any pending continuation with [error]. Caller must hold [mutex]. */
    internal fun cancelCurrentOperationLocked(error: BleError) {
        cancelPendingWriteOperationsLocked(error)
        when (val current = phase) {
            is BlePhase.WaitingForBluetooth -> current.continuation.completeExceptionally(error)
            is BlePhase.Connecting -> {
                current.timeoutJob.cancel()
                current.continuation.completeExceptionally(error)
            }
            is BlePhase.DiscoveringServices -> current.continuation.completeExceptionally(error)
            is BlePhase.NegotiatingMtu -> current.continuation.completeExceptionally(error)
            is BlePhase.SubscribingToNotifications -> current.continuation.completeExceptionally(error)
            is BlePhase.Connected -> current.dataChannel.close()
            else -> {}
        }
        transitionLocked(BlePhase.Idle)
    }

    internal suspend fun withStateLock(block: suspend () -> Unit) {
        mutex.withLock { block() }
    }

    // MARK: - Bond verification / app-session-live (see [BleStateMachineOps] for the "why")

    override suspend fun recordBondVerification(deviceAddress: String, atMillis: Long) {
        mutex.withLock { reconnectPolicy.recordBondVerification(deviceAddress, atMillis) }
    }

    override suspend fun clearBondVerification(deviceAddress: String) {
        mutex.withLock { reconnectPolicy.clearBondVerification(deviceAddress) }
    }

    override suspend fun setAppSessionLive(deviceAddress: String?) {
        mutex.withLock { appSessionLiveDeviceAddress = deviceAddress }
    }

    override suspend fun hasBondVerification(deviceAddress: String): Boolean =
        mutex.withLock { reconnectPolicy.hasBondVerification(deviceAddress) }

    override suspend fun isAppSessionLive(deviceAddress: String): Boolean =
        mutex.withLock { appSessionLiveDeviceAddress == deviceAddress }

    override suspend fun shouldPersistBondRefresh(deviceAddress: String): Boolean =
        mutex.withLock {
            reconnectPolicy.hasBondVerification(deviceAddress) && appSessionLiveDeviceAddress == deviceAddress
        }

    /**
     * Refreshes [deviceAddress]'s bond-verification stamp if a live app session matches it,
     * called from [handleReadRemoteRssi] after a successful RSSI keepalive read. Internal (not
     * on [BleStateMachineOps]): this is the state machine's own half of the RSSI-triggered
     * bond-refresh pipeline, not a `ConnectionManager`-facing operation — `ConnectionManager`
     * only observes its outcome via [onBondRefreshed].
     */
    internal suspend fun refreshBondVerificationIfSessionLive(deviceAddress: String): Boolean = mutex.withLock {
        if (phase !is BlePhase.Connected) return@withLock false
        if (appSessionLiveDeviceAddress != deviceAddress) return@withLock false
        reconnectPolicy.refreshBondVerification(deviceAddress, System.currentTimeMillis())
    }

    // MARK: - Shutdown / app lifecycle

    override suspend fun shutdown() {
        stopScanning()
        mutex.withLock {
            val deviceAddress = phase.deviceAddress
            cancelCurrentOperationLocked(BleError.NotConnected)
            if (deviceAddress != null) onDisconnection?.invoke(deviceAddress, null)
        }
    }

    override suspend fun appDidEnterBackground() {
        mutex.withLock {
            isAppActive = false
            // Keepalive persists — only the foreground-only auto-reconnect discovery watchdog
            // is cancelled; the RSSI keepalive keeps the BLE link alive in the background.
            reconnectDiscoveryTimeoutJob?.cancel()
            reconnectDiscoveryTimeoutJob = null
        }
    }

    override suspend fun appDidBecomeActive() {
        mutex.withLock {
            isAppActive = true
            when (val current = phase) {
                is BlePhase.Connected -> if (rssiKeepaliveJob == null) startRssiKeepalive()
                is BlePhase.AutoReconnecting -> armReconnectDiscoveryTimeout(current.device, connectionGeneration)
                else -> {}
            }
        }
    }
}
