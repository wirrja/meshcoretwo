// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

/** Message delivery status. Ported from `MessageStatus` (`Message.swift`). */
enum class MessageStatus(val rawValue: Int) {
    PENDING(0),
    SENDING(1),
    SENT(2),
    DELIVERED(3),
    FAILED(4),
    RETRYING(5),
    ;

    companion object {
        fun fromRawValue(value: Int): MessageStatus? = entries.find { it.rawValue == value }
    }
}
