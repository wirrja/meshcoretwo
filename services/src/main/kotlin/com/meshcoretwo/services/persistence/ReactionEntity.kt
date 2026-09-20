// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * An emoji reaction to a channel or DM message. Ported from `Reaction.swift`'s `@Model`
 * (SwiftData) to a Room `@Entity`. Indices mirror Swift's `#Index` triple exactly: by
 * [messageID] alone (fetch a message's reactions), by [radioID]/[contactID]/[messageID]
 * (device-scoped conversation queries), and by [messageID]/[senderName]/[emoji] (the
 * `reactionExists` dedup check).
 *
 * [channelIndex] is [Int]? rather than `UByte`? — same lossless-signed-widening convention as
 * every other entity in this module (see [Converters]'s doc for why Room/KSP can't take
 * unsigned column types directly).
 */
@Entity(
    tableName = "reactions",
    indices = [
        Index(value = ["messageID"]),
        Index(value = ["radioID", "contactID", "messageID"]),
        Index(value = ["messageID", "senderName", "emoji"]),
    ],
)
data class ReactionEntity(
    @PrimaryKey val id: UUID,
    /** Target message id. */
    val messageID: UUID,
    val emoji: String,
    /** Sender's node name (or `displayName` for a DM contact). */
    val senderName: String,
    /** 8-char Crockford Base32 content hash from the wire format. */
    val messageHash: String,
    /** Original raw reaction text, for fallback display. */
    val rawText: String,
    val receivedAt: Instant,
    /** Channel index (null for DM reactions). */
    val channelIndex: Int?,
    /** Contact id (null for channel reactions). */
    val contactID: UUID?,
    /** The device this reaction belongs to — partition key, not the volatile BLE address. */
    val radioID: UUID,
)
