// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import java.time.Instant
import java.util.UUID

/**
 * Tracks pending ACKs for a single outgoing direct message across retry attempts. Ported from
 * `PendingAck.swift` (a plain, uncompared `struct` there too, hence a plain class here rather
 * than a `data class` — its `ackCodes` list of [ByteArray] would get reference-equality-based
 * `equals`/`hashCode` from the synthesized ones anyway).
 *
 * The firmware hashes the retry attempt index into the expected-ACK CRC, so a single logical
 * message can produce multiple [ackCodes] (one per attempt) — though this vertical slice's
 * single-attempt [MessageService.sendDirectMessage] only ever records one.  DM-only:
 * channel/room broadcasts do not generate ACKs and are not tracked here.
 */
class PendingAck(
    val messageID: UUID,
    val contactID: UUID,
    ackCode: ByteArray,
    var sentAt: Instant,
    /** The latest send attempt's ACK-wait derived from the firmware's `est_timeout` hint, in milliseconds. */
    var timeoutMs: Long,
) {
    private val ackCodes = mutableListOf(ackCode)
    var isDelivered: Boolean = false

    fun addAckCode(code: ByteArray) {
        if (!matches(code)) ackCodes.add(code)
    }

    fun matches(code: ByteArray): Boolean = ackCodes.any { it.contentEquals(code) }
}
