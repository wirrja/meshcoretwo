// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Core device statistics.
 *
 * Core stats (9 bytes payload, little-endian per Python reader.py):
 * - Bytes 0-1: UInt16 - battery_mv
 * - Bytes 2-5: UInt32 - uptime_secs
 * - Bytes 6-7: UInt16 - errors
 * - Byte 8: UInt8 - queue_len
 */
data class CoreStats(
    /** The battery level in millivolts. */
    val batteryMV: UShort,
    /** The device uptime in seconds. */
    val uptimeSeconds: UInt,
    /** Total count of errors encountered. */
    val errors: UShort,
    /** The current length of the transmit queue. */
    val queueLength: UByte,
)

/**
 * Radio statistics.
 *
 * Radio stats (12 bytes payload, little-endian per Python reader.py):
 * - Bytes 0-1: Int16 - noise_floor (dBm)
 * - Byte 2: Int8 - last_rssi (dBm)
 * - Byte 3: Int8 - last_snr (raw, divide by 4.0 for dB)
 * - Bytes 4-7: UInt32 - tx_air_secs
 * - Bytes 8-11: UInt32 - rx_air_secs
 */
data class RadioStats(
    /** The noise floor in dBm. */
    val noiseFloor: Short,
    /** The last received signal strength indicator in dBm. */
    val lastRSSI: Byte,
    /** The last recorded signal-to-noise ratio. */
    val lastSNR: Double,
    /** Total transmit airtime in seconds. */
    val txAirtimeSeconds: UInt,
    /** Total receive airtime in seconds. */
    val rxAirtimeSeconds: UInt,
)

/**
 * Packet statistics.
 *
 * Packet stats (24 bytes payload, little-endian per Python reader.py):
 * - Bytes 0-3: UInt32 - recv
 * - Bytes 4-7: UInt32 - sent
 * - Bytes 8-11: UInt32 - flood_tx
 * - Bytes 12-15: UInt32 - direct_tx
 * - Bytes 16-19: UInt32 - flood_rx
 * - Bytes 20-23: UInt32 - direct_rx
 * - Bytes 24-27: UInt32 - recv_errors (optional, present when frame >= 28 bytes)
 */
data class PacketStats(
    /** Total packets received. */
    val received: UInt,
    /** Total packets sent. */
    val sent: UInt,
    /** Total flood packets transmitted. */
    val floodTx: UInt,
    /** Total direct packets transmitted. */
    val directTx: UInt,
    /** Total flood packets received. */
    val floodRx: UInt,
    /** Total direct packets received. */
    val directRx: UInt,
    /** Total RadioLib receive errors (CRC failures, malformed packets). */
    val receiveErrors: UInt = 0u,
)
