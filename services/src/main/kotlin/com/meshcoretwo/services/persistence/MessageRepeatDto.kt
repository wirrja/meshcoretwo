// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant
import java.util.UUID

/**
 * An immutable snapshot of a heard repeat — see [ContactDto]'s doc for why this crosses the
 * DAO/store boundary. Ported from `MessageRepeatDTO` (`MessageRepeat.swift`), trimmed to the
 * fields [HeardRepeatsService][com.meshcoretwo.services.repeats.HeardRepeatsService] actually
 * reads or writes.
 *
 * Dropped: the display-only computed properties (`hashSize`, `repeaterHash`, `hopCount`,
 * `repeaterHashFormatted`, `pathNodesHex`, `snrQuality`/`snrLevel`, `rssiFormatted`,
 * `snrFormatted`) — `hashSize`/`repeaterHash`/`hopCount`/`repeaterHashFormatted` were re-added as
 * `app`-layer extension properties once the Repeat Details row got a reader (`app`'s
 * `MessageRepeatFormatting.kt`, next to `MessageDetailsSection.kt`'s `RepeatRow`); the rest stay
 * dropped, formatted inline at that same call site instead.
 */
data class MessageRepeatDto(
    val id: UUID,
    val messageID: UUID,
    val receivedAt: Instant,
    val pathNodes: ByteArray,
    val pathLength: UByte,
    val snr: Double?,
    val rssi: Int?,
    val rxLogEntryID: UUID?,
) {
    // pathNodes has reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageRepeatDto) return false
        return id == other.id &&
            messageID == other.messageID &&
            receivedAt == other.receivedAt &&
            pathNodes.contentEquals(other.pathNodes) &&
            pathLength == other.pathLength &&
            snr == other.snr &&
            rssi == other.rssi &&
            rxLogEntryID == other.rxLogEntryID
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + messageID.hashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + pathNodes.contentHashCode()
        result = 31 * result + pathLength.hashCode()
        result = 31 * result + (snr?.hashCode() ?: 0)
        result = 31 * result + (rssi ?: 0)
        result = 31 * result + (rxLogEntryID?.hashCode() ?: 0)
        return result
    }
}

/** Maps a persisted row to the immutable snapshot services consume. */
fun MessageRepeatEntity.toDto(): MessageRepeatDto = MessageRepeatDto(
    id = id,
    messageID = messageID,
    receivedAt = receivedAt,
    pathNodes = pathNodes,
    pathLength = pathLength.toUByte(),
    snr = snr,
    rssi = rssi,
    rxLogEntryID = rxLogEntryID,
)

/** Maps a domain snapshot to the Room row shape. */
fun MessageRepeatDto.toEntity(): MessageRepeatEntity = MessageRepeatEntity(
    id = id,
    messageID = messageID,
    receivedAt = receivedAt,
    pathNodes = pathNodes,
    pathLength = pathLength.toInt(),
    snr = snr,
    rssi = rssi,
    rxLogEntryID = rxLogEntryID,
)
