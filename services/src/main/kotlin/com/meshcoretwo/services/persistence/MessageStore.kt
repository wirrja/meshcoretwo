// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.withTransaction
import com.meshcoretwo.services.backup.BackupDedupKeys
import com.meshcoretwo.services.backup.MessageBatchInsertResult
import com.meshcoretwo.services.backup.PerTypeCounts
import com.meshcoretwo.services.messages.DeduplicationKey
import com.meshcoretwo.services.utilities.ReactionParser
import java.time.Instant
import java.util.UUID

/**
 * Message persistence, wrapping [MessageDao] with the status-write guard logic Swift keeps in
 * `PersistenceStore+Messages.swift`. Scoped to what `MessageService`'s ported methods (single-
 * attempt direct-message send, channel send, ACK tracking) actually call — see [MessageEntity]'s
 * class doc for what's deferred along with the fields those methods would need. Mention-tracking
 * and contact/channel-sender/channel message deletion (added for the mention-count/blocked-sender
 * backfill slice) are scoped the same way, all cascading Reaction/MessageRepeat/PendingSend in one
 * transaction via [deleteCascading].
 */
class MessageStore(private val database: MeshCoreDatabase) {
    private val dao get() = database.messageDao()

    /**
     * Whether a message with this deduplication key already exists for [radioID]. Scoped
     * per-radio because the content-based key is radio-agnostic, and two companion radios in
     * the same area can receive the same over-the-air packet.
     */
    suspend fun isDuplicateMessage(deduplicationKey: String, radioID: UUID): Boolean =
        dao.countByDeduplicationKey(deduplicationKey, radioID) > 0

    /** Saves a new message. Always an insert — Swift's `saveMessage` never upserts by id either. */
    suspend fun saveMessage(dto: MessageDto) = dao.insert(dto.toEntity())

    suspend fun fetchMessage(id: UUID): MessageDto? = dao.fetchMessage(id)?.toDto()

    /**
     * The message this deduplication key already belongs to on [radioID], if any — same scope as
     * [isDuplicateMessage], which is implemented as a count over the same predicate. Ported from
     * `fetchMessage(deduplicationKey:radioID:)`: a later flood copy of an incoming message needs
     * the row it collides with, not just the fact that it collides.
     */
    suspend fun fetchMessage(deduplicationKey: String, radioID: UUID): MessageDto? =
        dao.findByDeduplicationKey(deduplicationKey, radioID)?.toDto()

    /**
     * Fills in [MessageDto.pathNodes]/[MessageDto.pathLength] only while the path is still
     * unknown, leaving `snr`/`heardRepeats` alone. Ported from `adoptIncomingPathIfUnknown`: a
     * `null` path is "not correlated yet", not "arrived over 0 hops", so the first observation
     * becomes the message's own path rather than an extra arrival hanging off nothing.
     *
     * @return Whether the row was actually updated.
     */
    suspend fun adoptIncomingPathIfUnknown(id: UUID, pathNodes: ByteArray, pathLength: UByte): Boolean {
        val existing = dao.fetchMessage(id) ?: return false
        if (existing.pathNodes != null) return false
        dao.update(existing.copy(pathNodes = pathNodes, pathLength = pathLength.toInt()))
        return true
    }

    /** Deletes a single message by id. Ported from `deleteMessage(id:)` — see [MessageService.deleteMessage] for the cascade/recompute this port layers on top. */
    suspend fun deleteMessage(id: UUID) = dao.deleteMessage(id)

    /**
     * Writes a freshly-resolved region onto every incoming channel message matching
     * `(channelIndex, senderTimestamp)` — the region-reprocess sweep's channel-message
     * correlation target. Ported from `batchUpdateChannelMessageRegion`. A no-op write (matching
     * row already carries this exact region) is skipped so the sweep's "touched" id list only
     * ever contains messages that actually changed.
     *
     * @return Ids of every message actually updated, for the caller's "re-bake open chats" signal.
     */
    suspend fun batchUpdateChannelMessageRegion(
        radioID: UUID,
        channelIndex: UByte,
        senderTimestamp: UInt,
        regionScope: String?,
        regionScopeMatches: List<String>,
    ): List<UUID> {
        val matchesString = regionScopeMatches.joinToString(",")
        val changed = dao.findChannelMessagesForRegionCorrelation(radioID, channelIndex.toInt(), senderTimestamp.toLong(), MessageDirection.INCOMING.rawValue)
            .filter { it.regionScope != regionScope || it.regionScopeMatches != matchesString }
        if (changed.isEmpty()) return emptyList()
        dao.updateAll(changed.map { it.copy(regionScope = regionScope, regionScopeMatches = matchesString) })
        return changed.map { it.id }
    }

    /**
     * Writes a freshly-resolved region onto every incoming direct message matching the RX-log
     * entry's sender timestamp and sender-prefix byte — the region-reprocess sweep's DM-message
     * correlation target. Ported from `batchUpdateDMMessageRegion`. Same no-op-skip reasoning as
     * [batchUpdateChannelMessageRegion].
     *
     * @return Ids of every message actually updated, for the caller's "re-bake open chats" signal.
     */
    suspend fun batchUpdateDMMessageRegion(
        radioID: UUID,
        senderPrefixByte: UByte,
        senderTimestamp: UInt,
        regionScope: String?,
        regionScopeMatches: List<String>,
    ): List<UUID> {
        val matchesString = regionScopeMatches.joinToString(",")
        val prefixByte = senderPrefixByte.toByte()
        val changed = dao.findDMMessagesForRegionCorrelation(radioID, senderTimestamp.toLong(), MessageDirection.INCOMING.rawValue)
            .filter { it.senderKeyPrefix?.getOrNull(0) == prefixByte }
            .filter { it.regionScope != regionScope || it.regionScopeMatches != matchesString }
        if (changed.isEmpty()) return emptyList()
        dao.updateAll(changed.map { it.copy(regionScope = regionScope, regionScopeMatches = matchesString) })
        return changed.map { it.id }
    }

    /**
     * Every message across every device, for backup export. Backfills [MessageDto
     * .deduplicationKey] on a pre-migration incoming row that never got one, so import can match
     * it — an outgoing message is left as-is, since restore keys those on UUID identity instead
     * (see [com.meshcoretwo.services.backup.BackupDedupKeys.messageBackupKey]). Ported from
     * `fetchAllMessagesForBackup`, minus its "skip external-storage link-preview blobs" step —
     * this port has no media/link-preview attachments to skip.
     */
    suspend fun fetchAllMessagesForBackup(): List<MessageDto> = dao.fetchAll().map { entity ->
        val dto = entity.toDto()
        if (dto.direction == MessageDirection.OUTGOING || dto.deduplicationKey != null) return@map dto
        dto.copy(
            deduplicationKey = DeduplicationKey.contentBased(
                contactID = dto.contactID,
                channelIndex = dto.channelIndex,
                senderNodeName = dto.senderNodeName,
                timestamp = dto.timestamp,
                content = dto.text,
            ),
        )
    }

    /**
     * Fetches messages for a contact, newest [limit] (paged by [offset]) but returned oldest
     * first for display, with same-sender clusters reordered by sender timestamp (see
     * [MessageDto.reorderSameSenderClusters]).
     */
    suspend fun fetchMessages(contactID: UUID, limit: Int = 50, offset: Int = 0): List<MessageDto> {
        val newestFirst = dao.fetchMessagesForContact(contactID, limit, offset).map { it.toDto() }
        return MessageDto.reorderSameSenderClusters(newestFirst.asReversed())
    }

    /** Fetches messages for a channel; see the contact variant for ordering. */
    suspend fun fetchMessages(radioID: UUID, channelIndex: UByte, limit: Int = 50, offset: Int = 0): List<MessageDto> {
        val newestFirst = dao.fetchMessagesForChannel(radioID, channelIndex.toInt(), limit, offset).map { it.toDto() }
        return MessageDto.reorderSameSenderClusters(newestFirst.asReversed())
    }

    /**
     * Every message across [radioIDs], keyed by [BackupDedupKeys.messageBackupKey] — backup
     * import's existing-row lookup. Returns both the key set and a key -> local-ids map, so a
     * single scan serves both duplicate-detection and (for a colliding key) picking a
     * deterministic winning local id. Ported from `existingMessageLookups`.
     */
    suspend fun existingMessageLookups(radioIDs: Set<UUID>): Pair<Set<String>, Map<String, List<UUID>>> {
        if (radioIDs.isEmpty()) return emptySet<String>() to emptyMap()
        val keys = mutableSetOf<String>()
        val idsByKey = mutableMapOf<String, MutableList<UUID>>()
        for (message in dao.fetchAll(radioIDs.toList()).map { it.toDto() }) {
            val key = BackupDedupKeys.messageBackupKey(message)
            keys.add(key)
            idsByKey.getOrPut(key) { mutableListOf() }.add(message.id)
        }
        return keys to idsByKey
    }

    /**
     * Resolves duplicates and inserts the rest, rewriting `replyToID` onto the winning local
     * parent so reply chains survive an import where the parent already exists locally. Ported
     * from `batchInsertMessages`.
     */
    suspend fun batchInsertMessages(dtos: List<MessageDto>, existingKeys: Set<String>, existingIdsByKey: Map<String, List<UUID>>): MessageBatchInsertResult {
        val knownKeys = existingKeys.toMutableSet()
        val idsByKey = existingIdsByKey.mapValuesTo(mutableMapOf()) { it.value.toMutableList() }
        val messageIdByBackupId = mutableMapOf<UUID, UUID>()
        val knownIds = existingIdsByKey.values.flatMapTo(mutableSetOf()) { it }
        val toInsert = mutableListOf<MessageDto>()
        var skipped = 0

        // Pass 1: resolve duplicates and build the backup -> local ID remap before any insert, so
        // pass 2 can rewrite replyToID onto winning local UUIDs.
        for (dto in dtos) {
            val key = BackupDedupKeys.messageBackupKey(dto)
            if (key in knownKeys) {
                // Deterministic tiebreak when multiple locals share a key.
                val winning = idsByKey[key]?.minByOrNull { it.toString() }
                if (winning != null && winning != dto.id) {
                    messageIdByBackupId[dto.id] = winning
                }
                skipped++
                continue
            }
            // Message.id is the primary key: a backup row that reuses an id already claimed (by a
            // local row or an earlier backup row) under a different dedup key would abort the whole
            // restore on the constraint. Skip it instead. Ported from upstream `3fdef597`.
            if (!knownIds.add(dto.id)) {
                skipped++
                continue
            }
            knownKeys.add(key)
            idsByKey.getOrPut(key) { mutableListOf() }.add(dto.id)
            toInsert.add(dto)
        }

        // Pass 2: insert, rewriting replyToID through the remap so retained messages point at the
        // winning local parent rather than a skipped backup UUID.
        for (dto in toInsert) {
            val replyTo = dto.replyToID
            val winning = replyTo?.let { messageIdByBackupId[it] }
            dao.insert((if (winning != null) dto.copy(replyToID = winning) else dto).toEntity())
        }

        return MessageBatchInsertResult(PerTypeCounts(inserted = toInsert.size, skipped = skipped), messageIdByBackupId)
    }

    /** Updates message status unconditionally (no-op if the row doesn't exist). */
    suspend fun updateMessageStatus(id: UUID, status: MessageStatus) {
        dao.fetchMessage(id)?.let { dao.update(it.copy(statusRawValue = status.rawValue)) }
    }

    /**
     * Updates message status unless delivery has already won the race.
     *
     * @return `true` if the row's status was changed, `false` if no row was updated (either
     *   already [MessageStatus.DELIVERED], or no row exists for [id]). Callers must gate failure
     *   side effects (e.g. a `.failed` broadcast) on the return value so they never surface a
     *   failed event for a delivered or absent row.
     */
    suspend fun updateMessageStatusUnlessDelivered(id: UUID, status: MessageStatus): Boolean {
        val existing = dao.fetchMessage(id) ?: return false
        if (existing.statusRawValue == MessageStatus.DELIVERED.rawValue) return false
        dao.update(existing.copy(statusRawValue = status.rawValue))
        return true
    }

    /**
     * Advances a message to [status] with retry-attempt bookkeeping, skipping the write on a
     * terminal row ([MessageStatus.DELIVERED]/[MessageStatus.FAILED]) so a stale retry iteration
     * can't clobber a winning ACK, nor resurrect a row the expiry checker already failed in the
     * retry loop's `waitForEvent` await-gap. Ported from `updateMessageRetryStatus`.
     */
    suspend fun updateMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) {
        val existing = dao.fetchMessage(id) ?: return
        if (existing.statusRawValue == MessageStatus.DELIVERED.rawValue || existing.statusRawValue == MessageStatus.FAILED.rawValue) return
        dao.update(existing.copy(statusRawValue = status.rawValue, retryAttempt = retryAttempt, maxRetryAttempts = maxRetryAttempts))
    }

    /**
     * Rolls a non-terminal row back to [MessageStatus.SENT] once the retry budget is spent
     * without an ACK. No-ops (returns `false`) on an already-terminal row so this can't clobber a
     * winning ACK or a checker-driven failure that landed first. Ported from `clearRetryingToSent`.
     *
     * @return `true` if the row's status was changed.
     */
    suspend fun clearRetryingToSent(id: UUID): Boolean {
        val existing = dao.fetchMessage(id) ?: return false
        if (existing.statusRawValue == MessageStatus.DELIVERED.rawValue || existing.statusRawValue == MessageStatus.FAILED.rawValue) return false
        dao.update(existing.copy(statusRawValue = MessageStatus.SENT.rawValue))
        return true
    }

    /** Updates a message's wire timestamp, for resending with a fresh send-time. Ported from `updateMessageTimestamp`. */
    suspend fun updateMessageTimestamp(id: UUID, timestamp: UInt) {
        dao.fetchMessage(id)?.let { dao.update(it.copy(timestamp = timestamp.toLong())) }
    }

    /**
     * Increments a message's [MessageDto.sendCount] and returns the new value, or `0` if the row
     * doesn't exist. Ported from `incrementMessageSendCount`.
     */
    suspend fun incrementMessageSendCount(id: UUID): Int {
        val existing = dao.fetchMessage(id) ?: return 0
        val updated = existing.copy(sendCount = existing.sendCount + 1)
        dao.update(updated)
        return updated.sendCount
    }

    /**
     * Updates a message's ACK info. Both [MessageStatus.DELIVERED] and [MessageStatus.FAILED]
     * are terminal for this write: once either is recorded, a write with a *different* status
     * is silently dropped so the authoritative delivery/failure state can't be overwritten by a
     * late, racing update — matching `updateMessageAck`'s `if message.status == .delivered ||
     * message.status == .failed, status != message.status { return }` in Swift.
     */
    suspend fun updateMessageAck(id: UUID, ackCode: UInt, status: MessageStatus, roundTripTime: UInt? = null) {
        val existing = dao.fetchMessage(id) ?: return
        val isTerminal = existing.statusRawValue == MessageStatus.DELIVERED.rawValue || existing.statusRawValue == MessageStatus.FAILED.rawValue
        if (isTerminal && status.rawValue != existing.statusRawValue) return
        dao.update(
            existing.copy(
                ackCode = ackCode.toLong(),
                statusRawValue = status.rawValue,
                roundTripTime = roundTripTime?.toLong(),
            ),
        )
    }

    /** Marks a message as read (no-op if the row doesn't exist). */
    suspend fun markMessageAsRead(id: UUID) {
        dao.fetchMessage(id)?.let { if (!it.isRead) dao.update(it.copy(isRead = true)) }
    }

    /** Updates a message's cached reaction-summary string (no-op if the row doesn't exist). */
    suspend fun updateMessageReactionSummary(id: UUID, summary: String?) {
        dao.fetchMessage(id)?.let { dao.update(it.copy(reactionSummary = summary)) }
    }

    /**
     * Increments a message's [MessageDto.heardRepeats] count and returns the new value, or `0` if
     * the row doesn't exist. Ported from `incrementMessageHeardRepeats`.
     */
    suspend fun incrementMessageHeardRepeats(id: UUID): Int {
        val existing = dao.fetchMessage(id) ?: return 0
        val updated = existing.copy(heardRepeats = existing.heardRepeats + 1)
        dao.update(updated)
        return updated.heardRepeats
    }

    /** Sets a message's [MessageDto.heardRepeats] to an explicit value (no-op if the row doesn't exist). Ported from `updateMessageHeardRepeats`. */
    suspend fun updateMessageHeardRepeats(id: UUID, heardRepeats: Int) {
        dao.fetchMessage(id)?.let { dao.update(it.copy(heardRepeats = heardRepeats)) }
    }

    /**
     * Finds the most-recently-created outgoing channel message matching an exact channel index,
     * sender timestamp, and text on [radioID] — used to correlate a heard repeat back to the
     * message that was sent. Ported from `findSentChannelMessage`.
     */
    suspend fun findSentChannelMessage(radioID: UUID, channelIndex: UByte, timestamp: UInt, text: String): MessageDto? =
        dao.findSentChannelMessage(radioID, channelIndex.toInt(), timestamp.toLong(), text, MessageDirection.OUTGOING.rawValue)?.toDto()

    /** Deletes every message for a channel. Ported from `deleteMessagesForChannel`. */
    suspend fun deleteMessagesForChannel(radioID: UUID, channelIndex: UByte) = database.withTransaction {
        deleteCascading(dao.fetchMessageIdsForChannel(radioID, channelIndex.toInt()))
    }

    /**
     * Deletes [messageIDs] with their Reaction/MessageRepeat/PendingSend rows. Runs inside the
     * caller's transaction — every public delete-many method here wraps it in one, and
     * [ChannelStore]'s slot-wipe shares it. Chunked under SQLite's 999-variable cap on older Android.
     */
    internal suspend fun deleteCascading(messageIDs: List<UUID>) {
        for (chunk in messageIDs.chunked(SUBCASCADE_CHUNK_SIZE)) {
            database.pendingSendDao().deleteForMessages(chunk)
            database.messageRepeatDao().deleteForMessages(chunk)
            database.reactionDao().deleteForMessages(chunk)
            dao.deleteMessages(chunk)
        }
    }

    /**
     * Deletes every message for a contact (leaves the contact row itself in place). Ported from
     * `deleteMessagesForContact`, cascading Reaction/MessageRepeat/PendingSend in the same
     * transaction like Swift does.
     */
    suspend fun deleteMessagesForContact(contactID: UUID) = database.withTransaction {
        deleteCascading(dao.fetchMessageIdsForContact(contactID))
    }

    /**
     * Deletes every channel message (never a DM) whose `senderNodeName` matches [senderNodeName]
     * on [radioID] — used when blocking a channel sender. Ported from `deleteChannelMessages
     * (fromSender:radioID:)`, with the same cascade as [deleteMessagesForContact].
     */
    suspend fun deleteChannelMessagesFromSender(radioID: UUID, senderNodeName: String) = database.withTransaction {
        deleteCascading(dao.fetchChannelMessageIdsFromSender(radioID, senderNodeName))
    }

    /** Marks a message's self-mention as seen (no-op if the row doesn't exist). Ported from `markMentionSeen`. */
    suspend fun markMentionSeen(messageID: UUID) {
        dao.fetchMessage(messageID)?.let { if (!it.mentionSeen) dao.update(it.copy(mentionSeen = true)) }
    }

    /** Unseen self-mention message ids for a contact, oldest first. Ported from `fetchUnseenMentionIDs`. */
    suspend fun fetchUnseenMentionIDs(contactID: UUID): List<UUID> = dao.fetchUnseenMentionIDs(contactID)

    /** Unseen self-mention message ids for a channel, oldest first. Ported from `fetchUnseenChannelMentionIDs`. */
    suspend fun fetchUnseenChannelMentionIDs(radioID: UUID, channelIndex: UByte): List<UUID> =
        dao.fetchUnseenChannelMentionIDs(radioID, channelIndex.toInt())

    /**
     * Fetches channel-message candidates within a timestamp window, newest first — raw
     * candidates for a caller to hash-match itself. Ported from `fetchChannelMessageCandidates`.
     */
    suspend fun fetchChannelMessageCandidates(radioID: UUID, channelIndex: UByte, timestampWindow: UIntRange, limit: Int): List<MessageDto> =
        dao.fetchChannelMessageCandidates(radioID, channelIndex.toInt(), timestampWindow.first.toLong(), timestampWindow.last.toLong(), limit)
            .map { it.toDto() }

    /** Fetches DM-message candidates within a timestamp window, newest first. Ported from `fetchDMMessageCandidates`. */
    suspend fun fetchDMMessageCandidates(radioID: UUID, contactID: UUID, timestampWindow: UIntRange, limit: Int): List<MessageDto> =
        dao.fetchDMMessageCandidates(radioID, contactID, timestampWindow.first.toLong(), timestampWindow.last.toLong(), limit)
            .map { it.toDto() }

    /**
     * Finds a channel message matching a parsed native-format reaction within [timestampWindow]:
     * an outgoing candidate must match [localNodeName], an incoming one must match
     * [targetSender]; either way its content+[com.meshcoretwo.services.persistence.MessageDto.reactionTimestamp]
     * hash must equal [messageHash]. Ported from `findChannelMessageForReaction`.
     *
     * Unlike [findDMMessageForReaction], this does **not** skip candidates that are themselves
     * reaction text — ported as-is from Swift, which has the same asymmetry; not "fixed" here,
     * since the task is a behavior port, not an improvement pass.
     */
    suspend fun findChannelMessageForReaction(
        radioID: UUID,
        channelIndex: UByte,
        targetSender: String,
        messageHash: String,
        localNodeName: String?,
        timestampWindow: UIntRange,
        limit: Int = 200,
    ): MessageDto? {
        val candidates = fetchChannelMessageCandidates(radioID, channelIndex, timestampWindow, limit)
        for (candidate in candidates) {
            if (candidate.direction == MessageDirection.OUTGOING) {
                if (localNodeName == null || targetSender != localNodeName) continue
            } else {
                if (candidate.senderNodeName != targetSender) continue
            }
            val candidateHash = ReactionParser.generateMessageHash(candidate.text, candidate.reactionTimestamp)
            if (candidateHash != messageHash) continue
            return candidate
        }
        return null
    }

    /**
     * Finds a DM message matching a reaction hash within [timestampWindow], skipping candidates
     * that are themselves reaction text. Ported from `findDMMessageForReaction`.
     */
    suspend fun findDMMessageForReaction(
        radioID: UUID,
        contactID: UUID,
        messageHash: String,
        timestampWindow: UIntRange,
        limit: Int = 200,
    ): MessageDto? {
        val candidates = fetchDMMessageCandidates(radioID, contactID, timestampWindow, limit)
        for (candidate in candidates) {
            if (ReactionParser.isReactionText(candidate.text, isDM = true)) continue
            val candidateHash = ReactionParser.generateMessageHash(candidate.text, candidate.reactionTimestamp)
            if (candidateHash == messageHash) return candidate
        }
        return null
    }

    /**
     * Recomputes cached [MessageDto.heardRepeats]/[MessageDto.reactionSummary] for messages that
     * received a new child row during backup import — reaches into `MessageRepeat`/`Reaction`
     * directly via [database] rather than depending on [MessageRepeatStore]/[ReactionStore], the
     * same cross-table precedent as [RemoteNodeSessionStore.deleteSession]'s room-message cascade.
     * Ported from `recomputeMessageCaches`.
     */
    suspend fun recomputeMessageCaches(messageIds: Set<UUID>) {
        if (messageIds.isEmpty()) return
        val idList = messageIds.toList()
        val messages = dao.fetchByIds(idList)
        val repeatCountsByMessageId = database.messageRepeatDao().fetchForMessages(idList).groupingBy { it.messageID }.eachCount()
        val reactionsByMessageId = database.reactionDao().fetchForMessages(idList).groupBy { it.messageID }

        for (message in messages) {
            val newHeardRepeats = repeatCountsByMessageId[message.id] ?: 0
            val withRepeats = if (message.heardRepeats != newHeardRepeats) message.copy(heardRepeats = newHeardRepeats) else message

            val reactions = reactionsByMessageId[message.id]
            val newSummary = if (reactions.isNullOrEmpty()) null else buildReactionSummary(reactions)
            val updated = if (withRepeats.reactionSummary != newSummary) withRepeats.copy(reactionSummary = newSummary) else withRepeats

            if (updated !== message) dao.update(updated)
        }
    }

    /**
     * Groups [reactions] by emoji, sorted by count descending with ties broken by earliest
     * `receivedAt` ascending — same grouping rule as `ReactionService`'s private `buildSummary`,
     * duplicated here rather than shared to avoid a `persistence` -> `utilities` ->
     * `persistence`-shaped import cycle ([ReactionParser] only formats already-ordered pairs and
     * has no [ReactionDto]/[ReactionEntity] dependency of its own).
     */
    private fun buildReactionSummary(reactions: List<ReactionEntity>): String {
        val ordered = reactions.groupBy { it.emoji }
            .map { (emoji, items) -> Triple(emoji, items.size, items.minOf { it.receivedAt }) }
            .sortedWith(compareByDescending<Triple<String, Int, Instant>> { it.second }.thenBy { it.third })
            .map { it.first to it.second }
        return ReactionParser.buildSummary(ordered)
    }
}

/** Visible module-wide (not just this file) so backup import's batch-insert can reuse it instead of a duplicate mapping. */
internal fun MessageDto.toEntity(): MessageEntity = MessageEntity(
    id = id,
    radioID = radioID,
    contactID = contactID,
    channelIndex = channelIndex?.toInt(),
    text = text,
    timestamp = timestamp.toLong(),
    createdAt = createdAt,
    sortDate = sortDate,
    directionRawValue = direction.rawValue,
    statusRawValue = status.rawValue,
    textTypeRawValue = textType.value.toInt(),
    ackCode = ackCode?.toLong(),
    pathLength = pathLength.toInt(),
    snr = snr,
    pathNodes = pathNodes,
    senderKeyPrefix = senderKeyPrefix,
    senderNodeName = senderNodeName,
    isRead = isRead,
    replyToID = replyToID,
    roundTripTime = roundTripTime?.toLong(),
    sendCount = sendCount,
    retryAttempt = retryAttempt,
    maxRetryAttempts = maxRetryAttempts,
    deduplicationKey = deduplicationKey,
    reactionSummary = reactionSummary,
    senderTimestamp = senderTimestamp?.toLong(),
    routeTypeRawValue = routeType?.value?.toInt() ?: -1,
    heardRepeats = heardRepeats,
    containsSelfMention = containsSelfMention,
    mentionSeen = mentionSeen,
    regionScope = regionScope,
    regionScopeMatches = regionScopeMatches.joinToString(","),
)

/** Stays under SQLite's 999-variable cap on older Android builds. */
private const val SUBCASCADE_CHUNK_SIZE = 500
