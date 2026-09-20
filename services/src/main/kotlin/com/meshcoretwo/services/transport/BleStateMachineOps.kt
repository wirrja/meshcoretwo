// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

/**
 * Operations the not-yet-ported `ConnectionManager` needs from [BleStateMachine], factored into
 * an interface so `ConnectionManager` can be unit-tested against a fake without real BLE
 * hardware — the same seam `ConnectionManager+Testing.swift`'s `MockBLEStateMachine` occupies on
 * iOS.
 *
 * Ported from `BLEStateMachineProtocol.swift`, which declares `protocol BLEStateMachineProtocol:
 * Actor`. Kotlin has no actor isolation to declare; [BleStateMachine] gets the same "one
 * operation touches shared state at a time" guarantee from its own [kotlinx.coroutines.sync.Mutex]
 * instead, so callers here see plain (or `suspend`, where the implementation must acquire that
 * mutex) function signatures rather than an isolation annotation.
 *
 * **Identifier note:** every Swift method here takes a `deviceID: UUID` — `CBPeripheral`'s
 * `identifier`, which is both CoreBluetooth's own system-level peripheral handle *and* the value
 * iOS's `DeviceDto.id` is seeded from. Android's BLE stack has no such thing: a peripheral is
 * identified only by its MAC address string, and the Android port's own domain `DeviceDto.id`
 * (see [com.meshcoretwo.services.connection.LastConnectionStore]) is an app-generated UUID
 * unrelated to any transport address. Every method here therefore takes a MAC address `String`
 * — the identifier [BleStateMachine] actually knows a device by — and translating a
 * `ConnectionManager`-level device UUID to/from that address (the same lookup it already needs
 * before calling [BleStateMachine.connect]) is `ConnectionManager`'s job, not this seam's.
 *
 * Deliberately excludes `connect`/`disconnect`/`send`/`setDisconnectionHandler`/
 * `setReconnectionHandler`: on iOS those live on the concrete `BLEStateMachine`, reached through
 * `BLEMeshTransport`'s `MeshTransport` conformance, not through this protocol — `ConnectionManager`
 * talks to the transport layer for the connect/disconnect/send dataflow and to this seam only for
 * BLE-specific plumbing (diagnostics, scanning, bond verification, backgrounding). The Android
 * port mirrors that split: [BleMeshTransport] wraps [BleStateMachine] for the former.
 */
interface BleStateMachineOps {
    /** Whether the state machine is currently connected to a device. */
    val isConnected: Boolean

    /** Whether the state machine is currently running its own reconnect-with-backoff loop. */
    val isAutoReconnecting: Boolean

    /** MAC address of the currently connected device, or null if not connected. */
    val connectedDeviceAddress: String?

    /** One consistent snapshot of adapter state, phase, and device state, read in a single hop. */
    val linkDiagnostics: BleLinkDiagnostics

    /** Whether the Bluetooth adapter is in the powered-off state. */
    val isBluetoothPoweredOff: Boolean

    /**
     * Checks if [deviceAddress] is GATT-connected to this app process.
     *
     * **Platform gap:** iOS's `retrieveConnectedPeripherals(withServices:)` reports devices
     * connected to *any* app via CoreBluetooth's system-wide Bluetooth daemon. Android's
     * `BluetoothManager.getConnectedDevices` only reports this app's own GATT client
     * connections — there is no system API to ask "is some *other* app connected to this
     * device." This method is therefore narrower than its iOS counterpart: a `false` result
     * does not rule out another app holding the link, only that this app isn't.
     */
    fun isDeviceConnectedToSystem(deviceAddress: String): Boolean

    /**
     * Returns the MAC addresses of all peripherals this app is currently GATT-connected to via
     * Nordic UART. Used for diagnostics — see [isDeviceConnectedToSystem]'s platform-gap note.
     */
    fun systemConnectedDeviceAddresses(): List<String>

    /**
     * Starts a best-effort adoption of a peripheral this app is already GATT-connected to but
     * has no live [BleStateMachine] session for (e.g. after a process restart that didn't go
     * through normal connect/reconnect). Runs the same discovery chain a fresh [connect][]
     * would, firing the reconnection handler on success instead of returning a `Flow` directly.
     *
     * @return `true` if an adoption attempt was started (fire-and-forget; the state machine was
     * idle and the device was found among this app's GATT connections), `false` otherwise.
     */
    fun startAdoptingSystemConnectedPeripheral(deviceAddress: String): Boolean

    /** Activates the state machine, registering for adapter state changes. Safe to call more than once. */
    fun activate()

    /** Sets a handler for auto-reconnecting events: `(deviceAddress, errorInfo)`. */
    fun setAutoReconnectingHandler(handler: (String, String) -> Unit)

    /** Sets a handler called when Bluetooth powers on. */
    fun setBluetoothPoweredOnHandler(handler: () -> Unit)

    /**
     * Records that [deviceAddress]'s bond completed a verified encrypted session at
     * [atMillis] (epoch milliseconds). Seeded from persistence at wiring time and refreshed
     * after every verified session.
     */
    suspend fun recordBondVerification(deviceAddress: String, atMillis: Long)

    /** Clears [deviceAddress]'s bond-verification record when its pairing is forgotten. */
    suspend fun clearBondVerification(deviceAddress: String)

    /**
     * Tells the state machine whether a completed app-layer session is live for [deviceAddress]
     * (non-null) or that no session is live (null). Called from `ConnectionManager` so RSSI
     * bond refresh cannot race a stale flag.
     */
    suspend fun setAppSessionLive(deviceAddress: String?)

    /** Whether [deviceAddress] currently has a bond-verification stamp. */
    suspend fun hasBondVerification(deviceAddress: String): Boolean

    /** Whether the session-live signal currently matches [deviceAddress]. */
    suspend fun isAppSessionLive(deviceAddress: String): Boolean

    /**
     * Single-hop check: [deviceAddress] has a bond-verification stamp AND its session-live
     * signal matches. Used so a clear landing between two separate checks cannot re-persist a
     * forgotten shield.
     */
    suspend fun shouldPersistBondRefresh(deviceAddress: String): Boolean

    /**
     * Sets a handler called when an existing bond verification was refreshed (never created)
     * while a live app session is present — fired from a successful RSSI keepalive read.
     */
    fun setBondRefreshedHandler(handler: ((String) -> Unit)?)

    /** Sets a handler for Bluetooth adapter state changes ([android.bluetooth.BluetoothAdapter] state constants). */
    fun setBluetoothStateChangeHandler(handler: (Int) -> Unit)

    /** Sets the delay between write operations for pacing (0 disables pacing). */
    fun setWritePacingDelay(delayMs: Long)

    /** Sets a handler called when a device is discovered during scanning. */
    fun setDeviceDiscoveredHandler(handler: (DiscoveredDevice) -> Unit)

    /** Starts scanning for BLE peripherals. Works while connected. */
    fun startScanning()

    /** Stops an active BLE scan. */
    fun stopScanning()

    /**
     * Gracefully shuts down the state machine, resuming all pending operations. Call before
     * dropping the last reference to it.
     */
    suspend fun shutdown()

    /**
     * Notifies the state machine that the app entered the background. Cancels foreground-only
     * timeouts (auto-reconnect discovery) while preserving the RSSI keepalive for background
     * connection maintenance.
     */
    suspend fun appDidEnterBackground()

    /**
     * Notifies the state machine that the app became active. Defensively restarts the RSSI
     * keepalive if connected, and re-arms the auto-reconnect discovery timeout if reconnecting.
     */
    suspend fun appDidBecomeActive()
}
