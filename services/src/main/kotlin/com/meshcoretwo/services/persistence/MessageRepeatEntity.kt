// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * A single heard repeat of a sent channel message — an observation of the message being
 * re-broadcast by a repeater, correlated from an [RxLogEntity]. Ported from `MessageRepeat.swift`'s
 * `@Model` (SwiftData) to a Room `@Entity`.
 *
 * No `@ForeignKey`/cascade-delete onto [MessageEntity] (unlike Swift's
 * `@Relationship(deleteRule: .cascade)`) — matching [ReactionEntity]'s precedent of a plain
 * [messageID] column with no Room-level FK. [com.meshcoretwo.services.messages.MessageService
 * .deleteMessage] (the one caller that deletes a message row) reproduces the cascade explicitly via
 * [MessageRepeatStore.deleteMessageRepeats] instead of a declared FK.
 *
 * Indices mirror Swift's `#Index<MessageRepeat>` pair exactly: `(messageID, receivedAt)` for
 * [HeardRepeatsService][com.meshcoretwo.services.repeats.HeardRepeatsService]'s `refreshRepeats`
 * fetch, and `(rxLogEntryID)` alone for the `messageRepeatExists` dedup check.
 *
 * [pathLength] (`UByte` on the wire) is stored as [Int] — same lossless-signed-widening convention
 * as every other entity in this module (see [ContactEntity]'s class doc for why Room's KSP
 * processor can't take unsigned column types directly).
 */
@Entity(
    tableName = "message_repeats",
    indices = [
        Index(value = ["messageID", "receivedAt"]),
        Index(value = ["rxLogEntryID"]),
    ],
)
data class MessageRepeatEntity(
    @PrimaryKey val id: UUID,
    /** The parent message's id (matches [MessageEntity.id]; kept as a plain column, not a relationship — see class doc). */
    val messageID: UUID,
    val receivedAt: Instant,
    /** Repeater public key prefixes (1-3 bytes per hop depending on hash mode). */
    val pathNodes: ByteArray,
    /** Encoded path length byte (upper 2 bits = hash mode, lower 6 bits = hop count). */
    val pathLength: Int,
    val snr: Double?,
    val rssi: Int?,
    /** Link to the [RxLogEntity] this repeat was correlated from, for dedup via [messageRepeatExists]-style lookups. */
    val rxLogEntryID: UUID?,
)
