// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * A message in a room-server conversation. Ported from `RoomMessage.swift`'s `@Model`
 * (SwiftData) to a Room `@Entity`. Unlike [MessageEntity], this table isn't scoped by
 * `radioID` — [sessionID] alone identifies the conversation, matching
 * `PersistenceStore+Rooms.swift`'s `RoomMessage` queries, all of which key off `sessionID` only.
 *
 * `timestamp`/`ackCode`/`roundTripTime` (`UInt32` on the wire) are stored as [Long] — Room's KSP
 * processor cannot handle Kotlin unsigned types as column types (see [ContactEntity]'s class doc).
 */
@Entity(
    tableName = "room_messages",
    indices = [
        Index(value = ["sessionID", "timestamp"]),
        Index(value = ["sessionID", "deduplicationKey"]),
    ],
)
data class RoomMessageEntity(
    @PrimaryKey val id: UUID,
    /** References [RemoteNodeSessionEntity.id]. */
    val sessionID: UUID,
    /** 4-byte original author's public key prefix from the server push. */
    val authorKeyPrefix: ByteArray,
    /** Resolved author name (from contacts), or `null`. */
    val authorName: String?,
    val text: String,
    /** Message timestamp (server time, epoch seconds). */
    val timestamp: Long,
    val createdAt: Instant,
    val isFromSelf: Boolean,
    /** Deduplication key combining timestamp, author, and content hash. */
    val deduplicationKey: String,
    val statusRawValue: Int,
    val ackCode: Long?,
    val roundTripTime: Long?,
    val retryAttempt: Int,
    val maxRetryAttempts: Int,
    /** Whether the user has opened the room after this outgoing send failed. */
    val failureSeen: Boolean,
)
