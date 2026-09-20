// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sendqueue

import com.meshcoretwo.services.persistence.PendingSendDto
import com.meshcoretwo.services.persistence.PendingSendKind
import java.time.Instant
import java.util.UUID

/**
 * Converts a [PendingSendDto] back into the runtime envelope shape [SendQueue] consumes. Returns
 * `null` if the DTO's discriminator does not match the requested envelope type — guards against
 * mis-routing a channel row into the DM queue or vice versa. Ported from `PendingSendEnvelope.swift`.
 */
fun PendingSendDto.directMessageEnvelope(): DirectMessageEnvelope? {
    val contact = contactID ?: return null
    if (kind != PendingSendKind.DM) return null
    return DirectMessageEnvelope(messageID = messageID, contactID = contact, isResend = isResend)
}

fun PendingSendDto.channelMessageEnvelope(): ChannelMessageEnvelope? {
    val index = channelIndex ?: return null
    if (kind != PendingSendKind.CHANNEL) return null
    return ChannelMessageEnvelope(
        messageID = messageID,
        channelIndex = index,
        isResend = isResend,
        messageText = messageText,
        messageTimestamp = messageTimestamp,
        localNodeName = localNodeName,
    )
}

/**
 * Builds a [PendingSendDto] from a [DirectMessageEnvelope]. Used by [ChatSendQueueService]
 * immediately before calling
 * [com.meshcoretwo.services.persistence.PendingSendStore.insertPendingSendAssigningSequence].
 *
 * `sequence` is a placeholder here; the store assigns the real value at insert time so two
 * concurrent enqueues can't race to produce the same sequence. `messageTimestamp` stays sentinel:
 * the DM drain keys preserve-timestamp behaviour off [PendingSendDto.attemptCount] via the
 * top-of-drain bump, so the wire timestamp does not round-trip through the DTO.
 */
fun pendingSendDto(envelope: DirectMessageEnvelope, radioID: UUID, id: UUID = UUID.randomUUID(), enqueuedAt: Instant = Instant.now()): PendingSendDto =
    PendingSendDto(
        id = id,
        radioID = radioID,
        messageID = envelope.messageID,
        kind = PendingSendKind.DM,
        contactID = envelope.contactID,
        channelIndex = null,
        isResend = envelope.isResend,
        messageText = "",
        messageTimestamp = 0u,
        localNodeName = null,
        sequence = 0,
        enqueuedAt = enqueuedAt,
    )

/**
 * Builds a [PendingSendDto] from a [ChannelMessageEnvelope]. Channel rows persist [messageText]/
 * [ChannelMessageEnvelope.messageTimestamp]/[ChannelMessageEnvelope.localNodeName] explicitly
 * because reaction indexing hashes off the post-send wire timestamp.
 */
fun pendingSendDto(envelope: ChannelMessageEnvelope, radioID: UUID, id: UUID = UUID.randomUUID(), enqueuedAt: Instant = Instant.now()): PendingSendDto =
    PendingSendDto(
        id = id,
        radioID = radioID,
        messageID = envelope.messageID,
        kind = PendingSendKind.CHANNEL,
        contactID = null,
        channelIndex = envelope.channelIndex,
        isResend = envelope.isResend,
        messageText = envelope.messageText,
        messageTimestamp = envelope.messageTimestamp,
        localNodeName = envelope.localNodeName,
        sequence = 0,
        enqueuedAt = enqueuedAt,
    )
