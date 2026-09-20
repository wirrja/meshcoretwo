// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Auto-add configuration received from the device.
 *
 * Bundles the bitmask (which node types to auto-add) with the max hops filter.
 *
 * Ported ahead of the rest of `Events/DevicePayloads.swift` because [PacketBuilder.setAutoAddConfig]
 * needs it; the remaining payload types there (`StatusResponse`, `OwnerInfoResponse`, ...) land
 * with the response parsers.
 */
class AutoAddConfig(
    /** Bitmask controlling auto-add behavior; see the `*_BIT` constants for the wire format. */
    val bitmask: UByte,
    /** Maximum hops for auto-add filtering. 0 = no limit, 1 = direct only, N = up to N-1 hops (max 64). */
    val maxHops: UByte = 0u,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AutoAddConfig) return false
        return bitmask == other.bitmask && maxHops == other.maxHops
    }

    override fun hashCode(): Int = 31 * bitmask.hashCode() + maxHops.hashCode()

    companion object {
        // `const val` is not allowed on UByte (only primitive types and String), so these are
        // plain vals — still compiled to a single static field, just not inlined at call sites.
        /** [bitmask] bit: overwrite the oldest non-favorite node when storage is full. */
        val OVERWRITE_OLDEST_BIT: UByte = 0x01u
        /** [bitmask] bit: auto-add Chat (contact) nodes. */
        val CONTACTS_BIT: UByte = 0x02u
        /** [bitmask] bit: auto-add Repeater nodes. */
        val REPEATERS_BIT: UByte = 0x04u
        /** [bitmask] bit: auto-add Room Server nodes. */
        val ROOM_SERVERS_BIT: UByte = 0x08u
        /** [bitmask] bit: auto-add Sensor nodes. */
        val SENSORS_BIT: UByte = 0x10u
    }
}
