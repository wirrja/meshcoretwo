// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel

/**
 * Represents the complete BLE connection lifecycle as explicit states. Each state owns exactly
 * the resources it needs.
 *
 * Ported from `BLEPhase.swift`, with Android's `BluetoothGatt`/`BluetoothGattService`/
 * `BluetoothGattCharacteristic` standing in for `CBPeripheral`/`CBService`/`CBCharacteristic`.
 * Swift's `CheckedContinuation` becomes [CompletableDeferred] rather than
 * `kotlinx.coroutines.CancellableContinuation`: a raw continuation's completing lambda is not a
 * suspend context, so it cannot itself acquire [BleStateMachine]'s mutex — the same reasoning
 * that led `RequestResponseSerializer` in `protocol` to the same choice. A phase transition
 * registers the deferred synchronously while already holding the mutex; the caller then awaits
 * it unlocked, and any GATT callback resolves it later from its own locked section. The
 * *domain* timeouts here — connection, discovery — are still explicit [Job]s alongside the
 * deferred, because they race against an external event, not against the deferred's own
 * lifetime.
 *
 * Adds [NegotiatingMtu] between service discovery and characteristic discovery: Android's ATT
 * MTU defaults to 23 bytes (20-byte usable payload) and must be explicitly raised via
 * `BluetoothGatt.requestMtu()`; iOS negotiates this automatically and has no equivalent phase.
 *
 * Drops Swift's `restoringState`: see [BlePhaseKind]'s doc for why.
 */
sealed class BlePhase {
    /** Initial state, no operations in progress. */
    object Idle : BlePhase()

    /** Waiting for the Bluetooth adapter to be powered on. */
    data class WaitingForBluetooth(val continuation: CompletableDeferred<Unit>) : BlePhase()

    /** Actively connecting to a peripheral. */
    data class Connecting(
        val gatt: BluetoothGatt,
        val continuation: CompletableDeferred<Unit>,
        val timeoutJob: Job,
    ) : BlePhase()

    /** Connected, discovering services. */
    data class DiscoveringServices(val gatt: BluetoothGatt, val continuation: CompletableDeferred<Unit>) : BlePhase()

    /**
     * Services found, requesting a larger ATT MTU. The service's characteristics are already
     * fully known at this point (see [BlePhaseKind]'s doc) — once MTU settles, the handler
     * resolves TX/RX from [BluetoothGattService] synchronously and moves straight to
     * [SubscribingToNotifications].
     */
    data class NegotiatingMtu(val gatt: BluetoothGatt, val continuation: CompletableDeferred<Unit>) : BlePhase()

    /** TX/RX resolved, subscribing to notifications. */
    data class SubscribingToNotifications(
        val gatt: BluetoothGatt,
        val tx: BluetoothGattCharacteristic,
        val rx: BluetoothGattCharacteristic,
        val continuation: CompletableDeferred<Unit>,
    ) : BlePhase()

    /**
     * Discovery chain complete, continuation consumed. Transitional phase between notification
     * subscription success and `connect()` creating the data channel. Holds characteristics
     * without a continuation, preventing double-resume if `cancelCurrentOperation` runs before
     * `connect()` transitions to [Connected].
     */
    data class DiscoveryComplete(
        val gatt: BluetoothGatt,
        val tx: BluetoothGattCharacteristic,
        val rx: BluetoothGattCharacteristic,
    ) : BlePhase()

    /** Fully connected and ready for communication. */
    data class Connected(
        val gatt: BluetoothGatt,
        val tx: BluetoothGattCharacteristic,
        val rx: BluetoothGattCharacteristic,
        val dataChannel: Channel<ByteArray>,
    ) : BlePhase()

    /**
     * This state machine's own reconnect-with-backoff in progress (see [ReconnectPolicy]).
     * Progressively populated as discovery completes. [gatt] is the last GATT client used for
     * this device, retained only to [BluetoothGatt.close] before a fresh reconnect attempt —
     * unlike iOS's OS-managed auto-reconnect, this device always issues its own fresh
     * `connectGatt` calls (see [BleStateMachine] class doc for why).
     */
    data class AutoReconnecting(
        val device: BluetoothDevice,
        val gatt: BluetoothGatt?,
        val tx: BluetoothGattCharacteristic?,
        val rx: BluetoothGattCharacteristic?,
    ) : BlePhase()

    /** Intentionally disconnecting. */
    data class Disconnecting(val gatt: BluetoothGatt) : BlePhase()

    /** The case without its GATT payload, safe to cross into diagnostics/logging. */
    val kind: BlePhaseKind
        get() = when (this) {
            is Idle -> BlePhaseKind.IDLE
            is WaitingForBluetooth -> BlePhaseKind.WAITING_FOR_BLUETOOTH
            is Connecting -> BlePhaseKind.CONNECTING
            is DiscoveringServices -> BlePhaseKind.DISCOVERING_SERVICES
            is NegotiatingMtu -> BlePhaseKind.NEGOTIATING_MTU
            is SubscribingToNotifications -> BlePhaseKind.SUBSCRIBING_TO_NOTIFICATIONS
            is DiscoveryComplete -> BlePhaseKind.DISCOVERY_COMPLETE
            is Connected -> BlePhaseKind.CONNECTED
            is AutoReconnecting -> BlePhaseKind.AUTO_RECONNECTING
            is Disconnecting -> BlePhaseKind.DISCONNECTING
        }

    /**
     * Whether this phase is part of the service-discovery/MTU/notification-subscription chain.
     * Used to preserve the discovery timeout when transitioning within the chain.
     */
    val isDiscoveryChain: Boolean
        get() = this is DiscoveringServices || this is NegotiatingMtu || this is SubscribingToNotifications

    /** Whether this phase represents an active operation (not idle). */
    val isActive: Boolean get() = this !is Idle

    /**
     * The GATT client associated with this phase, if any. Named distinctly from each case's own
     * `gatt` field — Kotlin treats a same-named member in a sealed subclass as needing to
     * override this one, which a plain (non-`open`) property can't support.
     */
    val associatedGatt: BluetoothGatt?
        get() = when (this) {
            is Connecting -> gatt
            is DiscoveringServices -> gatt
            is NegotiatingMtu -> gatt
            is SubscribingToNotifications -> gatt
            is DiscoveryComplete -> gatt
            is Connected -> gatt
            is AutoReconnecting -> gatt
            is Disconnecting -> gatt
            is Idle, is WaitingForBluetooth -> null
        }

    /** The device address associated with this phase, if any. */
    val deviceAddress: String?
        get() = when (this) {
            is AutoReconnecting -> device.address
            else -> associatedGatt?.device?.address
        }
}
