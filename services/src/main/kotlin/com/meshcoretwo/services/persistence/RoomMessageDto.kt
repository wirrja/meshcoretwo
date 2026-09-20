// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * An immutable snapshot of a room message — see [ContactDto]'s doc for why this crosses the
 * DAO/store boundary instead of the Room entity. Ported from `RoomMessageDTO` (`RoomMessage.swift`).
 */
data class RoomMessageDto(
    val id: UUID = UUID.randomUUID(),
    val sessionID: UUID,
    val authorKeyPrefix: ByteArray,
    val authorName: String? = null,
    val text: String,
    val timestamp: UInt,
    val createdAt: Instant = Instant.now(),
    val isFromSelf: Boolean = false,
    val status: MessageStatus = MessageStatus.DELIVERED,
    val ackCode: UInt? = null,
    val roundTripTime: UInt? = null,
    val retryAttempt: Int = 0,
    val maxRetryAttempts: Int = 0,
    val failureSeen: Boolean = false,
) {
    /** Deduplication key combining timestamp, author, and content hash. Ported from `RoomMessage.generateDeduplicationKey`. */
    val deduplicationKey: String get() = generateRoomMessageDeduplicationKey(timestamp, authorKeyPrefix, text)

    /** Display name for author (resolved name or hex prefix). */
    val authorDisplayName: String get() = authorName ?: authorKeyPrefix.joinToString("") { "%02X".format(it) }

    /** Date representation of [timestamp]. */
    val date: Instant get() = Instant.ofEpochSecond(timestamp.toLong())

    // ByteArray fields have reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoomMessageDto) return false
        return id == other.id &&
            sessionID == other.sessionID &&
            authorKeyPrefix.contentEquals(other.authorKeyPrefix) &&
            authorName == other.authorName &&
            text == other.text &&
            timestamp == other.timestamp &&
            createdAt == other.createdAt &&
            isFromSelf == other.isFromSelf &&
            status == other.status &&
            ackCode == other.ackCode &&
            roundTripTime == other.roundTripTime &&
            retryAttempt == other.retryAttempt &&
            maxRetryAttempts == other.maxRetryAttempts &&
            failureSeen == other.failureSeen
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sessionID.hashCode()
        result = 31 * result + authorKeyPrefix.contentHashCode()
        result = 31 * result + (authorName?.hashCode() ?: 0)
        result = 31 * result + text.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + isFromSelf.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (ackCode?.hashCode() ?: 0)
        result = 31 * result + (roundTripTime?.hashCode() ?: 0)
        result = 31 * result + retryAttempt
        result = 31 * result + maxRetryAttempts
        result = 31 * result + failureSeen.hashCode()
        return result
    }
}

/**
 * Generates a deduplication key for room-message uniqueness: timestamp + author prefix + first
 * 4 bytes of a SHA-256 content hash. Ported from `RoomMessage.generateDeduplicationKey`.
 */
fun generateRoomMessageDeduplicationKey(timestamp: UInt, authorKeyPrefix: ByteArray, text: String): String {
    val authorHex = authorKeyPrefix.joinToString("") { "%02X".format(it) }
    val contentHash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val hashPrefix = contentHash.copyOf(4).joinToString("") { "%02X".format(it) }
    return "$timestamp-$authorHex-$hashPrefix"
}

/** Maps a persisted row to the immutable snapshot services consume. */
fun RoomMessageEntity.toDto(): RoomMessageDto = RoomMessageDto(
    id = id,
    sessionID = sessionID,
    authorKeyPrefix = authorKeyPrefix,
    authorName = authorName,
    text = text,
    timestamp = timestamp.toUInt(),
    createdAt = createdAt,
    isFromSelf = isFromSelf,
    status = MessageStatus.fromRawValue(statusRawValue) ?: MessageStatus.DELIVERED,
    ackCode = ackCode?.toUInt(),
    roundTripTime = roundTripTime?.toUInt(),
    retryAttempt = retryAttempt,
    maxRetryAttempts = maxRetryAttempts,
    failureSeen = failureSeen,
)

/** Maps an immutable snapshot to the persisted row shape, preserving [RoomMessageDto.deduplicationKey]. */
fun RoomMessageDto.toEntity(): RoomMessageEntity = RoomMessageEntity(
    id = id,
    sessionID = sessionID,
    authorKeyPrefix = authorKeyPrefix,
    authorName = authorName,
    text = text,
    timestamp = timestamp.toLong(),
    createdAt = createdAt,
    isFromSelf = isFromSelf,
    deduplicationKey = deduplicationKey,
    statusRawValue = status.rawValue,
    ackCode = ackCode?.toLong(),
    roundTripTime = roundTripTime?.toLong(),
    retryAttempt = retryAttempt,
    maxRetryAttempts = maxRetryAttempts,
    failureSeen = failureSeen,
)
