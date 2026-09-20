// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

/** Message direction. Ported from `MessageDirection` (`Message.swift`). */
enum class MessageDirection(val rawValue: Int) {
    INCOMING(0),
    OUTGOING(1),
    ;

    companion object {
        fun fromRawValue(value: Int): MessageDirection? = entries.find { it.rawValue == value }
    }
}
