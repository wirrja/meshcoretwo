// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

/**
 * [BlePhase] without its GATT payload: the projection callers outside the state machine branch
 * on and log.
 *
 * Ported from `BLEPhaseKind.swift`. Omits Swift's `restoringState`: that phase exists only for
 * CoreBluetooth's `willRestoreState` background-relaunch callback, which has no Android
 * equivalent — an Android process that dies loses its `BluetoothGatt` entirely, and keeping a
 * link alive across process death is a foreground-`Service` concern layered above this state
 * machine (Phase 3), not a transport-level restoration phase.
 *
 * Also omits a separate "discovering characteristics" phase: unlike CoreBluetooth's
 * `discoverCharacteristics(_:for:)` (its own async step with its own delegate callback),
 * Android's `BluetoothGattService` already carries every characteristic once
 * `onServicesDiscovered` succeeds — there is nothing further to await, so [BleStateMachine]
 * resolves TX/RX synchronously inside the MTU-negotiation callback.
 */
enum class BlePhaseKind {
    IDLE,
    WAITING_FOR_BLUETOOTH,
    CONNECTING,
    DISCOVERING_SERVICES,
    NEGOTIATING_MTU,
    SUBSCRIBING_TO_NOTIFICATIONS,
    DISCOVERY_COMPLETE,
    CONNECTED,
    AUTO_RECONNECTING,
    DISCONNECTING,
}
