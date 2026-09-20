// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import com.meshcoretwo.services.persistence.ContactDto
import java.time.Instant
import java.util.UUID

/**
 * Narrow interface [IncomingMessageService] uses to short-circuit and index reaction messages,
 * satisfied by `ReactionService` (in the `reactions` package). Declared here — not as a direct
 * dependency on `ReactionService` itself — so the `messages` package doesn't need to know the
 * `reactions` package exists when a caller has no use for it. Mirrors
 * [IncomingMessageService]'s `pendingAdvertResolver` narrow-function-type trick, using an
 * interface instead of four separate lambdas since the four methods share enough context to read
 * better grouped.
 */
interface ReactionHandling {
    /** @return `true` if [text] was consumed as a reaction to one of [contact]'s messages (caller must not persist it as an ordinary message). */
    suspend fun handleDirectReaction(text: String, contact: ContactDto, radioID: UUID): Boolean

    /** @return `true` if [text] was consumed as a reaction (caller must not persist it as an ordinary message). */
    suspend fun handleChannelReaction(
        text: String,
        channelIndex: UByte,
        senderNodeName: String?,
        selfNodeName: String,
        receiveTime: Instant,
        radioID: UUID,
    ): Boolean

    /** Indexes a freshly-saved direct message for future reaction targeting, flushing (persisting) any reactions that were waiting on it. */
    suspend fun indexDirectMessage(messageID: UUID, contactID: UUID, text: String, timestamp: UInt)

    /** Indexes a freshly-saved channel message for future reaction targeting, flushing (persisting) any reactions that were waiting on it. */
    suspend fun indexChannelMessage(messageID: UUID, channelIndex: UByte, senderName: String, text: String, timestamp: UInt)
}
