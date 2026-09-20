// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sendqueue

import java.util.UUID

/**
 * Channel-message envelope for `SendQueue<ChannelMessageEnvelope>`. Ported from
 * `ChannelMessageEnvelope.swift`.
 *
 * [isResend] is load-bearing: a resend must call
 * [com.meshcoretwo.services.messages.MessageService.resendChannelMessage] (which stamps a fresh
 * timestamp so the mesh dedup ring does not silently drop the retry), while a fresh send must
 * call [com.meshcoretwo.services.messages.MessageService.sendPendingChannelMessage] which
 * preserves the original timestamp.
 *
 * [messageText], [messageTimestamp], and [localNodeName] are captured at enqueue time so the
 * post-send `reactionService.indexChannelMessage(...)` call can read them without depending on
 * caller state at drain time.
 */
data class ChannelMessageEnvelope(
    val messageID: UUID,
    val channelIndex: UByte,
    val isResend: Boolean,
    val messageText: String,
    val messageTimestamp: UInt,
    val localNodeName: String?,
)
