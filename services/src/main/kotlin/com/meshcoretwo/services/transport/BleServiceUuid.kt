// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

import java.util.UUID

/**
 * Nordic UART Service UUIDs for MeshCore device communication.
 *
 * Ported from `BLEServiceUUID.swift`.
 *
 * ## Naming Convention
 * TX/RX are named from the **central's perspective** (this app):
 * - TX = we transmit (write) to the peripheral
 * - RX = we receive (notifications) from the peripheral
 *
 * This is inverted from the Nordic UART Service standard naming, which uses the peripheral's
 * perspective. The actual UUIDs are correct.
 */
object BleServiceUuid {
    /** Nordic UART Service UUID. */
    val NORDIC_UART: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** TX Characteristic - central writes to this (Nordic: RX Characteristic). */
    val TX_CHARACTERISTIC: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** RX Characteristic - central receives notifications (Nordic: TX Characteristic). */
    val RX_CHARACTERISTIC: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Client Characteristic Configuration Descriptor — standard BLE UUID, writing this enables notifications. */
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
