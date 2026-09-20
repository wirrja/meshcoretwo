// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * A channel sender name the user has blocked. Channel messages carry no sender key, so blocking
 * is name-based only, matched exactly as stored (case-sensitive — `"Alice"` and `"ALICE"` are
 * distinct blocks, matching Swift's exact-string predicate). Ported from
 * `BlockedChannelSender.swift`'s `@Model` (SwiftData) to a Room `@Entity`.
 */
@Entity(
    tableName = "blocked_channel_senders",
    indices = [
        Index(value = ["radioID", "name"], unique = true),
    ],
)
data class BlockedChannelSenderEntity(
    @PrimaryKey val id: UUID,
    val name: String,
    /** The device this block applies to — partition key, not the volatile BLE address. */
    val radioID: UUID,
    val dateBlocked: Instant,
)
