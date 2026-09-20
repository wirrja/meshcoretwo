// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.protocol.decodePathLen
import com.meshcoretwo.protocol.hexString
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * An immutable snapshot of a message — see [ContactDto]'s doc for why this crosses the DAO/store
 * boundary instead of the Room entity. Ported from `MessageDTO` (`Message.swift`), trimmed to
 * the fields [MessageEntity] carries (see its class doc for what's deferred).
 */
data class MessageDto(
    val id: UUID,
    val radioID: UUID,
    val contactID: UUID?,
    val channelIndex: UByte?,
    val text: String,
    val timestamp: UInt,
    val createdAt: Instant,
    val sortDate: Instant,
    val direction: MessageDirection,
    val status: MessageStatus,
    val textType: TextType,
    val ackCode: UInt?,
    val pathLength: UByte,
    val snr: Double?,
    val pathNodes: ByteArray?,
    val senderKeyPrefix: ByteArray?,
    val senderNodeName: String?,
    val isRead: Boolean,
    val replyToID: UUID?,
    val roundTripTime: UInt?,
    val sendCount: Int,
    val retryAttempt: Int,
    val maxRetryAttempts: Int,
    val deduplicationKey: String?,
    val reactionSummary: String?,
    val senderTimestamp: UInt?,
    /** Route type of the correlated [RxLogEntity], or `null` if this message was never correlated. */
    val routeType: RouteType?,
    /** Count of heard repeats correlated to this (outgoing channel) message. */
    val heardRepeats: Int,
    /** Whether this message's text contains `@[selfNodeName]`, computed at ingestion. */
    val containsSelfMention: Boolean = false,
    /** Whether a self-mention has been shown to the user. Meaningless when [containsSelfMention] is `false`. */
    val mentionSeen: Boolean = false,
    /** See [MessageEntity.regionScope]'s doc. */
    val regionScope: String? = null,
    /** See [MessageEntity.regionScopeMatches]'s doc. */
    val regionScopeMatches: List<String> = emptyList(),
) {
    val isOutgoing: Boolean get() = direction == MessageDirection.OUTGOING

    val isChannelMessage: Boolean get() = channelIndex != null

    val isPending: Boolean get() = status == MessageStatus.PENDING || status == MessageStatus.SENDING

    val hasFailed: Boolean get() = status == MessageStatus.FAILED

    /** Hop count decoded from [pathLength]. Ported from `MessageDTO.hopCount`. */
    val hopCount: Int get() = decodePathLen(pathLength)?.hopCount ?: 0

    /** Hash size per hop in bytes (1, 2, or 3), decoded from [pathLength]. Ported from `MessageDTO.pathHashSize`. */
    val pathHashSize: Int get() = decodePathLen(pathLength)?.hashSize ?: 1

    /**
     * Hash size per hop when [pathLength] encodes a valid hash mode; `null` for reserved modes or
     * the no-path marker. Ported from `MessageDTO.pathHashSizeIfKnown`.
     */
    val pathHashSizeIfKnown: Int? get() = decodePathLen(pathLength)?.hashSize

    /**
     * Whether [timestamp] was replaced with the receive time because the sender's clock was off.
     * Derived rather than stored: ingestion writes [senderTimestamp] only on correction (the
     * stored `timestampCorrected` flag on iOS), so a differing value is the same signal.
     */
    val timestampCorrected: Boolean get() = !isOutgoing && senderTimestamp != null && senderTimestamp != timestamp

    /** Raw send time the sender stamped on the wire, uncorrected. Ported from `MessageDTO.wireSentDate`. */
    val wireSentInstant: Instant get() = Instant.ofEpochSecond((senderTimestamp ?: timestamp).toLong())

    /**
     * Whether this message was flood-routed (broadcast), by the same priority Swift uses:
     * [channelIndex] (channels are always flood) → [routeType] from the correlated RX-log entry →
     * [pathLength] sentinel inference. Ported from `MessageDTO.isFloodRouted`; matches
     * [com.meshcoretwo.protocol.Contact.isFloodRouted]'s pathLength-sentinel half.
     */
    val isFloodRouted: Boolean
        get() = when {
            channelIndex != null -> true
            routeType != null -> routeType == RouteType.FLOOD || routeType == RouteType.TC_FLOOD
            else -> pathLength != PacketBuilder.FLOOD_PATH_SENTINEL
        }

    /** Whether this message used a pre-built path (hops consumed in transit). Ported from `MessageDTO.isDirectRouted`. */
    val isDirectRouted: Boolean get() = !isFloodRouted

    /**
     * Each hop of [pathNodes] as its raw hash bytes plus uppercase hex, e.g. `[(0xA3, "A3"), (0x7F,
     * "7F")]` — needed to match a hop against a repeater's public-key prefix (via
     * `NeighborNameResolver.resolvePath` in the `app` module, same as [ContactDto.pathHops]).
     * Ported from `MessageDTO.pathHops`.
     */
    val pathHops: List<ContactPathHop>
        get() {
            val nodes = pathNodes ?: return emptyList()
            if (pathHashSize <= 0) return emptyList()
            val byteLength = minOf(pathHashSize * hopCount, nodes.size)
            val bytes = nodes.copyOfRange(0, byteLength)
            return bytes.toList().chunked(pathHashSize).map { chunk ->
                val chunkBytes = chunk.toByteArray()
                ContactPathHop(data = chunkBytes, hex = chunkBytes.hexString.uppercase())
            }
        }

    /**
     * The timestamp reaction hashing must key off: the sender's originally-claimed time when it
     * was clock-corrected, otherwise [timestamp] itself. Ported from `MessageDTO.reactionTimestamp`.
     */
    val reactionTimestamp: UInt get() = senderTimestamp ?: timestamp

    /**
     * A sent outgoing reaction renders as a badge elsewhere, so a timeline should hide this row —
     * unless it failed, so the user can still see it to retry. Ported from
     * `MessageDTO.isHiddenOutgoingReaction(isDM:)`.
     */
    fun isHiddenOutgoingReaction(isDM: Boolean): Boolean {
        if (direction != MessageDirection.OUTGOING) return false
        val isReaction = if (isDM) {
            com.meshcoretwo.services.utilities.ReactionParser.parseDM(text) != null
        } else {
            com.meshcoretwo.services.utilities.ReactionParser.parse(text) != null
        }
        if (!isReaction) return false
        return status != MessageStatus.FAILED
    }

    // ByteArray fields have reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageDto) return false
        return id == other.id &&
            radioID == other.radioID &&
            contactID == other.contactID &&
            channelIndex == other.channelIndex &&
            text == other.text &&
            timestamp == other.timestamp &&
            createdAt == other.createdAt &&
            sortDate == other.sortDate &&
            direction == other.direction &&
            status == other.status &&
            textType == other.textType &&
            ackCode == other.ackCode &&
            pathLength == other.pathLength &&
            snr == other.snr &&
            (pathNodes?.contentEquals(other.pathNodes ?: ByteArray(0)) ?: (other.pathNodes == null)) &&
            (senderKeyPrefix?.contentEquals(other.senderKeyPrefix ?: ByteArray(0)) ?: (other.senderKeyPrefix == null)) &&
            senderNodeName == other.senderNodeName &&
            isRead == other.isRead &&
            replyToID == other.replyToID &&
            roundTripTime == other.roundTripTime &&
            sendCount == other.sendCount &&
            retryAttempt == other.retryAttempt &&
            maxRetryAttempts == other.maxRetryAttempts &&
            deduplicationKey == other.deduplicationKey &&
            reactionSummary == other.reactionSummary &&
            senderTimestamp == other.senderTimestamp &&
            routeType == other.routeType &&
            heardRepeats == other.heardRepeats &&
            containsSelfMention == other.containsSelfMention &&
            mentionSeen == other.mentionSeen &&
            regionScope == other.regionScope &&
            regionScopeMatches == other.regionScopeMatches
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + (contactID?.hashCode() ?: 0)
        result = 31 * result + (channelIndex?.hashCode() ?: 0)
        result = 31 * result + text.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + sortDate.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + textType.hashCode()
        result = 31 * result + (ackCode?.hashCode() ?: 0)
        result = 31 * result + pathLength.hashCode()
        result = 31 * result + (snr?.hashCode() ?: 0)
        result = 31 * result + (pathNodes?.contentHashCode() ?: 0)
        result = 31 * result + (senderKeyPrefix?.contentHashCode() ?: 0)
        result = 31 * result + (senderNodeName?.hashCode() ?: 0)
        result = 31 * result + isRead.hashCode()
        result = 31 * result + (replyToID?.hashCode() ?: 0)
        result = 31 * result + (roundTripTime?.hashCode() ?: 0)
        result = 31 * result + sendCount
        result = 31 * result + retryAttempt
        result = 31 * result + maxRetryAttempts
        result = 31 * result + (deduplicationKey?.hashCode() ?: 0)
        result = 31 * result + (reactionSummary?.hashCode() ?: 0)
        result = 31 * result + (senderTimestamp?.hashCode() ?: 0)
        result = 31 * result + (routeType?.hashCode() ?: 0)
        result = 31 * result + heardRepeats
        result = 31 * result + containsSelfMention.hashCode()
        result = 31 * result + mentionSeen.hashCode()
        result = 31 * result + (regionScope?.hashCode() ?: 0)
        result = 31 * result + regionScopeMatches.hashCode()
        return result
    }

    companion object {
        /** Maximum gap (seconds), measured on [sortDate], within which consecutive same-sender messages are re-sorted. */
        private val SAME_SENDER_REORDER_WINDOW: Duration = Duration.ofSeconds(5)

        /**
         * Reorders messages within narrow same-sender clusters by sender timestamp. Ported from
         * `MessageDTO.reorderSameSenderClusters`.
         *
         * Expects input already sorted by [sortDate] (the display sort key). When multiple
         * messages from the same sender fall within a short window, mesh relay may deliver them
         * out of order; this detects those clusters and re-sorts them by the sender's claimed
         * [timestamp] to restore the intended conversation order. The cluster window is measured
         * on [sortDate] — the same key the input is sorted by — so it stays non-negative and
         * cannot pull together rows that are far apart on the display axis.
         */
        fun reorderSameSenderClusters(messages: List<MessageDto>): List<MessageDto> {
            if (messages.size <= 1) return messages

            val result = messages.toMutableList()
            var clusterStart = 0

            while (clusterStart < result.size) {
                var clusterEnd = clusterStart + 1

                while (clusterEnd < result.size) {
                    val gap = Duration.between(result[clusterEnd - 1].sortDate, result[clusterEnd].sortDate)
                    if (!isSameSender(result[clusterEnd], result[clusterEnd - 1]) || gap > SAME_SENDER_REORDER_WINDOW) break
                    clusterEnd++
                }

                if (clusterEnd - clusterStart > 1) {
                    val sorted = result.subList(clusterStart, clusterEnd).sortedWith(
                        compareBy({ it.timestamp }, { it.createdAt }),
                    )
                    for (i in sorted.indices) result[clusterStart + i] = sorted[i]
                }

                clusterStart = clusterEnd
            }

            return result
        }

        private fun isSameSender(a: MessageDto, b: MessageDto): Boolean {
            if (a.direction != b.direction) return false
            if (a.isChannelMessage != b.isChannelMessage) return false

            if (a.isChannelMessage) {
                val nameA = a.senderNodeName ?: return false
                val nameB = b.senderNodeName ?: return false
                return nameA == nameB
            }

            return true
        }
    }
}

/** Maps a persisted row to the immutable snapshot services consume. */
fun MessageEntity.toDto(): MessageDto = MessageDto(
    id = id,
    radioID = radioID,
    contactID = contactID,
    channelIndex = channelIndex?.toUByte(),
    text = text,
    timestamp = timestamp.toUInt(),
    createdAt = createdAt,
    sortDate = sortDate,
    direction = MessageDirection.fromRawValue(directionRawValue) ?: MessageDirection.OUTGOING,
    status = MessageStatus.fromRawValue(statusRawValue) ?: MessageStatus.PENDING,
    textType = TextType.fromValue(textTypeRawValue.toUByte()) ?: TextType.PLAIN_TEXT,
    ackCode = ackCode?.toUInt(),
    pathLength = pathLength.toUByte(),
    snr = snr,
    pathNodes = pathNodes,
    senderKeyPrefix = senderKeyPrefix,
    senderNodeName = senderNodeName,
    isRead = isRead,
    replyToID = replyToID,
    roundTripTime = roundTripTime?.toUInt(),
    sendCount = sendCount,
    retryAttempt = retryAttempt,
    maxRetryAttempts = maxRetryAttempts,
    deduplicationKey = deduplicationKey,
    reactionSummary = reactionSummary,
    senderTimestamp = senderTimestamp?.toUInt(),
    routeType = RouteType.fromValue(routeTypeRawValue.toUByte()),
    heardRepeats = heardRepeats,
    containsSelfMention = containsSelfMention,
    mentionSeen = mentionSeen,
    regionScope = regionScope,
    regionScopeMatches = if (regionScopeMatches.isEmpty()) emptyList() else regionScopeMatches.split(","),
)
