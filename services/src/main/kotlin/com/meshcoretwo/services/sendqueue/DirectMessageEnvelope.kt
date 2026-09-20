// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sendqueue

import java.util.UUID

/**
 * Direct-message envelope for `SendQueue<DirectMessageEnvelope>`. Ported from
 * `DirectMessageEnvelope.swift`.
 *
 * Captures the target contact at enqueue time so the drain can fetch a fresh contact DTO per
 * iteration — preserves the per-iteration fetch semantics that defend against stale contact
 * state.
 *
 * [isResend] is true only when the envelope represents an explicit user-initiated resend of an
 * already-sent DM. The DM queue drain branches on this flag between
 * [com.meshcoretwo.services.messages.MessageService.sendPendingDirectMessage] (no `sendCount`
 * bump) and [com.meshcoretwo.services.messages.MessageService.resendDirectMessage] (bumps
 * `sendCount`, broadcasts `MessageStatusEvent.Resent`).
 */
data class DirectMessageEnvelope(
    val messageID: UUID,
    val contactID: UUID,
    val isResend: Boolean = false,
)
