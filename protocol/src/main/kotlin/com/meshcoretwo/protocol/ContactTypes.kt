// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// MARK: - Contact Type

/**
 * Contact type identifier for mesh network nodes.
 *
 * Maps to the 1-byte type field in the firmware contact record (offset 33).
 * Values are defined by the MeshCore protocol specification.
 */
enum class ContactType(val value: UByte) {
    CHAT(0x01u),
    REPEATER(0x02u),
    ROOM(0x03u);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(value: UByte): ContactType? = byValue[value]
    }
}

// MARK: - Contact Flags

/**
 * Bitfield flags stored in the firmware contact record (offset 34).
 *
 * - Bit 0: Favorite/pinned status
 * - Bits 1-3: Telemetry permissions (base, location, environment)
 * - Bits 4-7: Reserved
 */
@JvmInline
value class ContactFlags(val rawValue: UByte) {
    infix fun or(other: ContactFlags): ContactFlags = ContactFlags(rawValue or other.rawValue)

    operator fun contains(flag: ContactFlags): Boolean = (rawValue and flag.rawValue) == flag.rawValue

    companion object {
        /** Contact is marked as favorite (bit 0) */
        val FAVORITE = ContactFlags(0x01u)

        /** Base telemetry permission (bit 1) */
        val TELEMETRY_BASE = ContactFlags(0x02u)

        /** Location telemetry permission (bit 2) */
        val TELEMETRY_LOCATION = ContactFlags(0x04u)

        /** Environment telemetry permission (bit 3) */
        val TELEMETRY_ENVIRONMENT = ContactFlags(0x08u)

        /** All telemetry permissions (bits 1-3) */
        val TELEMETRY_ALL = TELEMETRY_BASE or TELEMETRY_LOCATION or TELEMETRY_ENVIRONMENT

        /** No flags set. */
        val NONE = ContactFlags(0x00u)
    }
}
