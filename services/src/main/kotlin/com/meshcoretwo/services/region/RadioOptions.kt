// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

import java.util.Locale

/** Standard LoRa radio parameter options for configuration UI. Ported from `RadioOptions.swift`. */
object RadioOptions {
    /**
     * Available bandwidth options in Hz (internal representation for picker tags). Display values:
     * 7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125, 250, 500 kHz.
     *
     * Note: these values are passed directly to the protocol layer. Despite the misleading
     * parameter name `bandwidthKHz` in [com.meshcoretwo.protocol.PacketBuilder.setRadio], the
     * firmware actually expects bandwidth in Hz.
     */
    val bandwidthsHz: List<UInt> = listOf(7800u, 10400u, 15600u, 20800u, 31250u, 41700u, 62500u, 125_000u, 250_000u, 500_000u)

    /** Valid spreading factor range (SF5-SF12). */
    val spreadingFactors: IntRange = 5..12

    /** Valid coding rate range (5-8, representing 4/5 through 4/8). */
    val codingRates: IntRange = 5..8

    /**
     * Formats a bandwidth Hz value for display (e.g. 7800 -> "7.8", 125000 -> "125"). Uses a
     * `when` over known values to ensure deterministic, O(1) output.
     */
    fun formatBandwidth(hz: UInt): String = when (hz) {
        7800u -> "7.8"
        10400u -> "10.4"
        15600u -> "15.6"
        20800u -> "20.8"
        31250u -> "31.25"
        41700u -> "41.7"
        62500u -> "62.5"
        125_000u -> "125"
        250_000u -> "250"
        500_000u -> "500"
        // Fallback for unexpected values (e.g. from nearestBandwidth edge cases).
        else -> {
            val khz = hz.toDouble() / 1000.0
            if (khz % 1.0 == 0.0) khz.toLong().toString() else String.format(Locale.US, "%.2f", khz).trimEnd('0').trimEnd('.')
        }
    }

    /**
     * Finds the nearest valid bandwidth for a device value that may not be in the standard list.
     * Handles firmware float precision issues where values like 7800 Hz may be stored as 7.8 kHz
     * (float) and returned as 7799 or 7801 Hz.
     */
    fun nearestBandwidth(hz: UInt): UInt {
        if (hz in bandwidthsHz) return hz
        return bandwidthsHz.minByOrNull { if (it > hz) it - hz else hz - it } ?: 250_000u
    }
}
