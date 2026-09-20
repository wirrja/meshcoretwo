// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * A message in a direct conversation or channel. Ported from `Message.swift`'s `@Model`
 * (SwiftData) to a Room `@Entity`, trimmed to what this vertical slice's `MessageService`
 * methods (single-attempt send, channel send, ACK tracking) actually read or write.
 *
 * Dropped entirely (added when the service methods that need them are ported):
 * - Link preview fields (`linkPreviewURL`/`Title`/`ImageData`/`IconData`/`Fetched`) — Swift
 *   stores the two blob fields with `@Attribute(.externalStorage)` (blob-off-row); Room has no
 *   equivalent, so a naive port would put large BLOBs directly in this table, exactly the
 *   perf problem that attribute exists to avoid. Needs its own design pass, not a speculative
 *   placeholder.
 * - `repeats: [MessageRepeat]` (`@Relationship(deleteRule: .cascade)`) — see [MessageRepeatEntity]
 *   for that side; this entity only carries the [heardRepeats] *count* the relationship's rows
 *   are tallied into, not the relationship itself (Room has no cascade-owning collection property
 *   the way SwiftData does — [com.meshcoretwo.services.repeats.HeardRepeatsService] queries
 *   [MessageRepeatEntity] separately, keyed by [MessageEntity.id]).
 * - `failureSeen` — smoke-tested in Swift alongside `mentionSeen` but not read by any mention-
 *   tracking method there either; dropped until a caller needs it.
 *
 * [reactionSummary] and [senderTimestamp] *are* carried (added for the "Reaction" vertical
 * slice) — see [com.meshcoretwo.services.reactions.ReactionService]'s class doc for what's built
 * on top of them. [routeTypeRawValue] *is* carried too (added for the "RxLog" slice) — see
 * [com.meshcoretwo.services.rxlog.RxLogService]'s class doc. [heardRepeats] *is* carried too
 * (added for the "HeardRepeats" slice) — see
 * [com.meshcoretwo.services.repeats.HeardRepeatsService]'s class doc. [containsSelfMention]/
 * [mentionSeen] *are* carried too (added for the mention-count/blocked-sender backfill slice —
 * see [com.meshcoretwo.services.contacts.ContactService]'s class doc). [regionScope]/
 * [regionScopeMatches] *are* carried too (added for the "Incoming Region" chat-footer slice,
 * PLAN.md Фаза 33.5's follow-up) — see [com.meshcoretwo.services.rxlog.RxLogService]'s class doc
 * for the resolution/reprocess pipeline that fills them in.
 *
 * As with the other entities in this module, [channelIndex]/[textTypeRawValue]/[pathLength]
 * (`UByte`) and [timestamp]/[ackCode]/[roundTripTime] (`UInt`) are stored as [Int]/[Long] —
 * Room's KSP processor cannot handle Kotlin unsigned types as column types (see
 * [Converters]'s doc).
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["radioID", "channelIndex", "sortDate"]),
        Index(value = ["contactID", "sortDate"]),
        Index(value = ["deduplicationKey"]),
        Index(value = ["contactID", "containsSelfMention", "mentionSeen"]),
        Index(value = ["radioID", "channelIndex", "containsSelfMention", "mentionSeen"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: UUID,
    /** The device this message belongs to — partition key, not the volatile BLE address. */
    val radioID: UUID,
    /** Contact id for direct messages (null for channel messages). */
    val contactID: UUID?,
    /** Channel index for channel messages (null for direct messages). */
    val channelIndex: Int?,
    val text: String,
    /** Message timestamp (device time, epoch seconds). */
    val timestamp: Long,
    val createdAt: Instant,
    /** Date used for send-time ordering of synced backlog messages. Defaults to [createdAt] for new rows. */
    val sortDate: Instant,
    val directionRawValue: Int,
    val statusRawValue: Int,
    val textTypeRawValue: Int,
    /** ACK code for tracking delivery (outgoing only). */
    val ackCode: Long?,
    val pathLength: Int,
    val snr: Double?,
    /** Path nodes for incoming messages (1 byte per hop, from RxLogEntry correlation). */
    val pathNodes: ByteArray?,
    /** Sender public key prefix (6 bytes, for incoming messages). */
    val senderKeyPrefix: ByteArray?,
    /** Sender node name (for channel messages, parsed from "NodeName: MessageText" format). */
    val senderNodeName: String?,
    val isRead: Boolean,
    val replyToID: UUID?,
    /** Round-trip time in ms (when ACK received). */
    val roundTripTime: Long?,
    /** Number of times this message has been sent (1 = original, 2+ = sent again). */
    val sendCount: Int,
    /** Current retry attempt (0 = first attempt, 1 = first retry, etc.). */
    val retryAttempt: Int,
    val maxRetryAttempts: Int,
    /** Deduplication key for preventing duplicate incoming messages. */
    val deduplicationKey: String?,
    /** Cached `"👍:3,❤️:2"`-style summary of this message's reactions, or `null`/empty if none. */
    val reactionSummary: String?,
    /**
     * The sender's originally-claimed timestamp (epoch seconds), recorded only when
     * [timestamp] was clock-corrected away from it. Reaction hashing must key off the sender's
     * *claimed* time (what they actually hashed into the wire reaction), not our corrected one —
     * see [com.meshcoretwo.services.persistence.MessageDto.reactionTimestamp].
     */
    val senderTimestamp: Long?,
    /** `RouteType.value.toInt()` from the correlated [RxLogEntity], or `-1` if uncorrelated (never a route type — `-1` isn't a valid `UByte`). */
    val routeTypeRawValue: Int,
    /** Count of [MessageRepeatEntity] rows correlated to this (outgoing channel) message. */
    val heardRepeats: Int,
    /** Whether this message's text contains `@[selfNodeName]`. See [MessageDto.reactionTimestamp]-style companion, computed once at ingestion. */
    val containsSelfMention: Boolean,
    /** Whether a self-mention has been shown to the user (cleared by opening the conversation). Meaningless when [containsSelfMention] is `false`. */
    val mentionSeen: Boolean,
    /**
     * Confident single flood-region name from RxLog correlation, or `null` when unresolved or
     * ambiguous. Read through [com.meshcoretwo.services.rxlog.RegionScopeSemantics.coalesce] with
     * [regionScopeMatches] — `null` alone is not "unknown." Incoming-only by data-pipeline design
     * (never written for an outgoing message). See [com.meshcoretwo.services.rxlog.RxLogService]'s
     * class doc for the resolution/reprocess pipeline that fills this in.
     */
    val regionScope: String? = null,
    /**
     * Sorted known public regions that verify this packet's transport code, comma-joined — same
     * storage convention as [DeviceEntity.knownRegions]. Empty when [regionScope] is set from a
     * unique match or when there's no match at all; two-plus names when ambiguous.
     */
    val regionScopeMatches: String = "",
)
