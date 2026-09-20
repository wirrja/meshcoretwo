// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.protocol.decodePathLen
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.ContactPathHop
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageRepeatDto
import java.time.Duration

/**
 * Display helpers for the extra flood paths of an incoming message (each a [MessageRepeatDto]).
 * Ported from `MessagePathArrival.swift`, trimmed to what the Path Details list draws — the
 * capsule/map selection logic that file also carries has no reader in this port.
 */

/** Every arrival of [message]: the message's own path plus one per extra. Outgoing rows have no "own" arrival, their echoes are all in the repeats. Ported from `MessagePathArrivals.arrivalCount`. */
fun arrivalCount(message: MessageDto): Int = if (message.isOutgoing) message.heardRepeats else 1 + message.heardRepeats

/** Each hop of an extra path, splitting [MessageRepeatDto.pathNodes] by the hash size its length byte encodes. Empty for a 0-hop arrival. */
val MessageRepeatDto.pathHops: List<ContactPathHop>
    get() {
        val size = decodePathLen(pathLength)?.hashSize ?: 1
        if (size <= 0) return emptyList()
        return pathNodes.toList().chunked(size).map { chunk ->
            val bytes = chunk.toByteArray()
            ContactPathHop(data = bytes, hex = bytes.hexString.uppercase())
        }
    }

/** Hop count of an extra path, from the length byte (lower 6 bits) rather than the node bytes, so a path with an unreadable hash size still shows a count. */
val MessageRepeatDto.arrivalHopCount: Int
    get() = decodePathLen(pathLength)?.hopCount ?: (pathLength.toInt() and 63)

/** Compact "how long after the first arrival" text — `+3s`, `+1m 05s`, `+2h 03m`. Sub-second offsets round down to `+0s`. Ported from `MessagePathArrivalCapsules`' offset formatting. */
fun formatArrivalOffset(offset: Duration): String {
    val total = offset.seconds.coerceAtLeast(0)
    return when {
        total < 60 -> "+${total}s"
        total < 3600 -> "+${total / 60}m %02ds".format(total % 60)
        else -> "+${total / 3600}h %02dm".format((total % 3600) / 60)
    }
}
