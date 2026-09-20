// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.protocol.ContactType

/**
 * Discriminates between remote node types for role-specific handling. Ported from
 * `RemoteNodeRole` (`ProtocolTypes.swift`).
 */
enum class RemoteNodeRole(val rawValue: UByte) {
    REPEATER(0x02u),
    ROOM_SERVER(0x03u),
    ;

    companion object {
        fun fromRawValue(value: UByte): RemoteNodeRole? = entries.find { it.rawValue == value }

        /** Initializes from a contact's type; `null` for [ContactType.CHAT], which has no remote-node role. */
        fun fromContactType(type: ContactType): RemoteNodeRole? = when (type) {
            ContactType.REPEATER -> REPEATER
            ContactType.ROOM -> ROOM_SERVER
            ContactType.CHAT -> null
        }
    }
}
