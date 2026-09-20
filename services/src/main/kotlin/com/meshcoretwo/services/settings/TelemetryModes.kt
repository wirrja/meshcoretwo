// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

/**
 * Packed telemetry mode configuration. Ported from `TelemetryModes.swift`. Each field is masked to
 * its 2-bit range (0-3) on construction, matching the firmware's packed-byte wire format.
 */
class TelemetryModes(base: UByte = 0u, location: UByte = 0u, environment: UByte = 0u) {
    val base: UByte = base and MASK
    val location: UByte = location and MASK
    val environment: UByte = environment and MASK

    /** Packed value for protocol encoding. */
    val packed: UByte
        get() = ((environment.toInt() shl 4) or (location.toInt() shl 2) or base.toInt()).toUByte()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TelemetryModes) return false
        return base == other.base && location == other.location && environment == other.environment
    }

    override fun hashCode(): Int {
        var result = base.hashCode()
        result = 31 * result + location.hashCode()
        result = 31 * result + environment.hashCode()
        return result
    }

    companion object {
        private val MASK: UByte = 0b11u

        /** Unpacks a wire-format byte into its base/location/environment components. */
        fun fromPacked(packed: UByte): TelemetryModes = TelemetryModes(
            base = packed and MASK,
            location = ((packed.toInt() shr 2) and 0b11).toUByte(),
            environment = ((packed.toInt() shr 4) and 0b11).toUByte(),
        )
    }
}
