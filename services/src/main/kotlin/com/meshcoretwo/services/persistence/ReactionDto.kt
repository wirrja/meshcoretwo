// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant
import java.util.UUID

/** An immutable snapshot of a reaction — see [ContactDto]'s doc for why this crosses the DAO/store boundary. Ported from `ReactionDTO`. */
data class ReactionDto(
    val id: UUID,
    val messageID: UUID,
    val emoji: String,
    val senderName: String,
    val messageHash: String,
    val rawText: String,
    val receivedAt: Instant,
    val channelIndex: UByte?,
    val contactID: UUID?,
    val radioID: UUID,
)

/** Maps a persisted row to the immutable snapshot services consume. */
fun ReactionEntity.toDto(): ReactionDto = ReactionDto(
    id = id,
    messageID = messageID,
    emoji = emoji,
    senderName = senderName,
    messageHash = messageHash,
    rawText = rawText,
    receivedAt = receivedAt,
    channelIndex = channelIndex?.toUByte(),
    contactID = contactID,
    radioID = radioID,
)

/** Maps a domain snapshot to the Room row shape. */
fun ReactionDto.toEntity(): ReactionEntity = ReactionEntity(
    id = id,
    messageID = messageID,
    emoji = emoji,
    senderName = senderName,
    messageHash = messageHash,
    rawText = rawText,
    receivedAt = receivedAt,
    channelIndex = channelIndex?.toInt(),
    contactID = contactID,
    radioID = radioID,
)
