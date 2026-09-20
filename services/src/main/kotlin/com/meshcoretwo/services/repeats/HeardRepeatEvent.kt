// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.repeats

import java.util.UUID

/**
 * Notification that a heard repeat was recorded for a sent channel message. Broadcast by
 * [HeardRepeatsService.events]; the stream is multicast, every subscriber receives every event.
 * Ported from `HeardRepeatEvent.swift`.
 */
data class HeardRepeatEvent(
    /** The sent message the repeat was correlated to. */
    val messageID: UUID,
    /** The message's updated heard-repeat count. */
    val count: Int,
)
