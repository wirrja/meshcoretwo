// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import com.meshcoretwo.services.persistence.MessageStatus
import java.util.UUID

/**
 * Outbound-message lifecycle notifications broadcast by [MessageService]. Ported from
 * `MessageStatusEvent.swift`. Subscribe via [MessageService.statusEvents]; the flow is
 * multicast (a [kotlinx.coroutines.flow.MutableSharedFlow]), so every subscriber sees every
 * event.
 *
 * [Retrying] and [RoutingChanged] are only ever emitted by the app-layer retry loop
 * (`sendMessageWithRetry`), which this vertical slice doesn't port yet (see
 * [MessageService]'s class doc) — they're declared now so the event type is complete and
 * consumers can exhaustively `when` over it without a source-breaking change later.
 */
sealed class MessageStatusEvent {
    /**
     * A message reached a resolved status: [MessageStatus.SENT] once the radio queues a DM or
     * channel broadcast, [MessageStatus.DELIVERED] once a DM's end-to-end ACK arrives. Channel
     * broadcasts have no recipient ACK, so `SENT` is their terminal success state.
     */
    data class StatusResolved(val messageID: UUID, val status: MessageStatus, val roundTripTime: UInt?) : MessageStatusEvent()

    /** A resend completed with [MessageStatus.SENT] committed. */
    data class Resent(val messageID: UUID) : MessageStatusEvent()

    /** A retry attempt is in flight; use for UI retry progress. */
    data class Retrying(val messageID: UUID, val attempt: Int, val maxAttempts: Int) : MessageStatusEvent()

    /** A contact's routing switched between direct and flood during a send. */
    data class RoutingChanged(val contactID: UUID, val isFlood: Boolean) : MessageStatusEvent()

    /** A message failed after exhausting retries or a terminal send error. */
    data class Failed(val messageID: UUID) : MessageStatusEvent()
}
