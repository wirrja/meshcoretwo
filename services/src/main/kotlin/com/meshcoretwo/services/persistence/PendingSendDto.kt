// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant
import java.util.UUID

/** Discriminator for which `SendQueue` a row drains into. Ported from `PendingSendKind`. */
enum class PendingSendKind(val rawValue: Int) {
    DM(0),
    CHANNEL(1),
    ;

    companion object {
        fun fromRawValue(rawValue: Int): PendingSendKind = entries.firstOrNull { it.rawValue == rawValue } ?: DM
    }
}

/** An immutable snapshot of a [PendingSendEntity] — see [ContactDto]'s doc for why this crosses the DAO/store boundary. Ported from `PendingSendDTO`. */
data class PendingSendDto(
    val id: UUID,
    val radioID: UUID,
    val messageID: UUID,
    val kind: PendingSendKind,
    val contactID: UUID?,
    val channelIndex: UByte?,
    val isResend: Boolean,
    val messageText: String,
    val messageTimestamp: UInt,
    val localNodeName: String?,
    val sequence: Int,
    val enqueuedAt: Instant,
    val attemptCount: Int = 0,
)

fun PendingSendEntity.toDto(): PendingSendDto = PendingSendDto(
    id = id,
    radioID = radioID,
    messageID = messageID,
    kind = PendingSendKind.fromRawValue(kindRawValue),
    contactID = contactID,
    channelIndex = channelIndex?.toUByte(),
    isResend = isResend,
    messageText = messageText,
    messageTimestamp = messageTimestamp.toUInt(),
    localNodeName = localNodeName,
    sequence = sequence,
    enqueuedAt = enqueuedAt,
    attemptCount = attemptCount,
)

fun PendingSendDto.toEntity(): PendingSendEntity = PendingSendEntity(
    id = id,
    radioID = radioID,
    messageID = messageID,
    kindRawValue = kind.rawValue,
    contactID = contactID,
    channelIndex = channelIndex?.toInt(),
    isResend = isResend,
    messageText = messageText,
    messageTimestamp = messageTimestamp.toLong(),
    localNodeName = localNodeName,
    sequence = sequence,
    enqueuedAt = enqueuedAt,
    attemptCount = attemptCount,
)
