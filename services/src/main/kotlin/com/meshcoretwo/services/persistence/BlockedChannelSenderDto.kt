// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant
import java.util.UUID

/** An immutable snapshot of a blocked channel sender — see [ContactDto]'s doc for why this crosses the DAO/store boundary. Ported from `BlockedChannelSenderDTO`. */
data class BlockedChannelSenderDto(
    val id: UUID,
    val name: String,
    val radioID: UUID,
    val dateBlocked: Instant,
)

fun BlockedChannelSenderEntity.toDto(): BlockedChannelSenderDto = BlockedChannelSenderDto(
    id = id,
    name = name,
    radioID = radioID,
    dateBlocked = dateBlocked,
)

/** The reverse of [BlockedChannelSenderEntity.toDto] — backup import's batch-insert needs to persist an arbitrary [BlockedChannelSenderDto]. */
fun BlockedChannelSenderDto.toEntity(): BlockedChannelSenderEntity = BlockedChannelSenderEntity(
    id = id,
    name = name,
    radioID = radioID,
    dateBlocked = dateBlocked,
)
