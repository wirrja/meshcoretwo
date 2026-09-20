// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

/**
 * Permission levels for room server access. Ported from `RoomPermissionLevel` (`ProtocolTypes.swift`).
 * Declared in ascending rawValue order so Kotlin's default `Enum.compareTo` (ordinal-based) already
 * gives the same ordering as Swift's hand-written `Comparable` (`lhs.rawValue < rhs.rawValue`) —
 * no explicit `Comparable` override needed.
 */
enum class RoomPermissionLevel(val rawValue: UByte) {
    GUEST(0x00u),
    READ_WRITE(0x01u),
    ADMIN(0x02u),
    ;

    val canPost: Boolean get() = this >= READ_WRITE

    val isAdmin: Boolean get() = this == ADMIN

    companion object {
        fun fromRawValue(value: UByte): RoomPermissionLevel? = entries.find { it.rawValue == value }
    }
}
