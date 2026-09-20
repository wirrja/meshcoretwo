// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.reactions

import com.meshcoretwo.services.messages.MessageService
import com.meshcoretwo.services.messages.ReactionHandling
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.ReactionDto
import com.meshcoretwo.services.persistence.ReactionStore
import com.meshcoretwo.services.utilities.ReactionParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/** A channel reaction waiting for its target message to be indexed. */
data class PendingReaction(
    val parsed: ReactionParser.ParsedReaction,
    val channelIndex: UByte,
    val senderNodeName: String,
    val rawText: String,
    val radioID: UUID,
    val receivedAt: Instant,
)

/** A DM reaction waiting for its target message to be indexed. */
data class PendingDMReaction(
    val parsed: ReactionParser.ParsedDMReaction,
    val contactID: UUID,
    val senderName: String,
    val rawText: String,
    val radioID: UUID,
    val receivedAt: Instant,
)

private data class PendingReactionKey(val channelIndex: UByte, val targetSender: String, val messageHash: String)
private data class PendingDMReactionKey(val contactID: UUID, val messageHash: String)

/**
 * Handles native emoji reactions: matching an incoming reaction to its target
 * message (LRU cache → DB timestamp-window scan → pending queue), persisting it, and maintaining
 * the target message's cached [MessageDto.reactionSummary]. Ported from `ReactionService.swift`
 * (an `actor`) merged with the *native-format-only* branches of
 * `SyncCoordinator+ReactionHandlers.swift`'s `handleDMReaction`/`handleChannelReaction` — Swift
 * splits those because `SyncCoordinator` orchestrates a `ReactionService` it doesn't own; this
 * port has no `SyncCoordinator`, so, as with [com.meshcoretwo.services.messages.IncomingMessageService],
 * both fold into one class. Implements [ReactionHandling], the narrow interface
 * `IncomingMessageService` calls through.
 *
 * **Deferred, not yet ported** (tracked in PLAN.md's Phase 3 status):
 * - **"meshcore-open" reaction formats** (`MeshCoreOpenReactionParser.swift`, both its v3 and v1
 *   variants) — third-party-client interop via a different wire encoding and a reimplementation
 *   of Dart's `String.hashCode`. A reaction from that client is silently ignored rather than
 *   matched, since only the native format is recognized.
 * - **Reaction notifications** — same blocker as `IncomingMessageService`'s deferred unread/
 *   notification concerns: needs `NotificationService`, which needs Phase 5 UI to know what the
 *   user is currently viewing.
 * - **Un-reacting / editing a sent reaction** — not a cut corner: this doesn't exist in the Swift
 *   app either. The only reaction-deletion path is cascading with its parent message, which
 *   isn't ported (no message-delete cascade exists yet in this port).
 * - **The double-tap in-flight guard** from `ChatViewModel.sendReaction` — that's UI-tap-state
 *   bookkeeping belonging to a ViewModel layer this port doesn't have yet (Phase 5).
 * - **`clearPendingReactions`'s "call on disconnect" contract** — there's no connection-lifecycle
 *   owner in this port yet to call it; the method is here and correct, just unwired.
 * - **Orphan-DM adoption's reaction handling** — out of scope for the same reason
 *   `AdvertisementService`'s orphan-DM adoption is: this port only has the *live* materialization
 *   path (`materializeContactForPendingAdvert`), not backlog adoption.
 */
class ReactionService(
    private val messageStore: MessageStore,
    private val reactionStore: ReactionStore,
    private val messageCache: MessageLRUCache = MessageLRUCache(),
) : ReactionHandling {
    private val mutex = Mutex()

    // Pending queues: no TTL, session lifetime (cleared only by clearPendingReactions/eviction).
    private val pendingReactions = mutableMapOf<PendingReactionKey, MutableList<PendingReaction>>()
    private val pendingOrder = mutableListOf<PendingReactionKey>()
    private val pendingDMReactions = mutableMapOf<PendingDMReactionKey, MutableList<PendingDMReaction>>()
    private val pendingDMOrder = mutableListOf<PendingDMReactionKey>()

    private val eventsFlow = MutableSharedFlow<ReactionEvent>(extraBufferCapacity = 64)

    /** A multicast stream of persisted-reaction notifications; every subscriber sees every event. */
    fun events(): Flow<ReactionEvent> = eventsFlow

    // MARK: - ReactionHandling

    override suspend fun handleDirectReaction(text: String, contact: ContactDto, radioID: UUID): Boolean {
        val parsed = ReactionParser.parseDM(text) ?: return false

        val cachedTarget = findDMTargetMessage(parsed.messageHash, contact.id)
        if (cachedTarget != null) {
            persistReactionIfNew(reactionDto(cachedTarget, parsed.emoji, contact.displayName, parsed.messageHash, text, null, contact.id, radioID))
            return true
        }

        val window = reactionTimestampWindow(Instant.now().epochSecond.toUInt())
        val targetMessage = tryFindDMMessage(radioID, contact.id, parsed.messageHash, window)
        if (targetMessage != null) {
            persistReactionIfNew(reactionDto(targetMessage.id, parsed.emoji, contact.displayName, parsed.messageHash, text, null, contact.id, radioID))
            return true
        }

        queuePendingDMReaction(parsed, contact.id, contact.displayName, text, radioID)
        return true
    }

    override suspend fun handleChannelReaction(
        text: String,
        channelIndex: UByte,
        senderNodeName: String?,
        selfNodeName: String,
        receiveTime: Instant,
        radioID: UUID,
    ): Boolean {
        val parsed = ReactionParser.parse(text) ?: return false
        val senderName = senderNodeName ?: "Unknown"

        val cachedTarget = findTargetMessage(parsed, channelIndex)
        if (cachedTarget != null) {
            persistReactionIfNew(reactionDto(cachedTarget, parsed.emoji, senderName, parsed.messageHash, text, channelIndex, null, radioID))
            return true
        }

        val window = reactionTimestampWindow(receiveTime.epochSecond.toUInt())
        val targetMessage = tryFindChannelMessage(radioID, channelIndex, parsed.targetSender, parsed.messageHash, selfNodeName.ifEmpty { null }, window)
        if (targetMessage != null) {
            val wasNew = persistReactionIfNew(reactionDto(targetMessage.id, parsed.emoji, senderName, parsed.messageHash, text, channelIndex, null, radioID))
            if (wasNew) {
                // Re-index for future reactions; pending matches aren't re-processed here since a
                // pending reaction would also resolve via this same DB fallback (matches Swift).
                val targetSenderName = if (targetMessage.direction == MessageDirection.OUTGOING) selfNodeName.ifEmpty { null } else targetMessage.senderNodeName
                if (targetSenderName != null) {
                    messageCache.index(targetMessage.id, channelIndex, targetSenderName, targetMessage.text, targetMessage.reactionTimestamp)
                }
            }
            return true
        }

        queuePendingReaction(parsed, channelIndex, senderName, text, radioID)
        return true
    }

    override suspend fun indexDirectMessage(messageID: UUID, contactID: UUID, text: String, timestamp: UInt) {
        messageCache.indexDM(messageID, contactID, text, timestamp)

        val key = PendingDMReactionKey(contactID, ReactionParser.generateMessageHash(text, timestamp))
        val matched = mutex.withLock {
            val list = pendingDMReactions.remove(key)
            if (list != null) pendingDMOrder.remove(key)
            list
        } ?: return

        for (pending in matched) {
            persistReactionIfNew(
                reactionDto(messageID, pending.parsed.emoji, pending.senderName, pending.parsed.messageHash, pending.rawText, null, contactID, pending.radioID),
            )
        }
    }

    override suspend fun indexChannelMessage(messageID: UUID, channelIndex: UByte, senderName: String, text: String, timestamp: UInt) {
        messageCache.index(messageID, channelIndex, senderName, text, timestamp)

        val key = PendingReactionKey(channelIndex, senderName, ReactionParser.generateMessageHash(text, timestamp))
        val matched = mutex.withLock {
            val list = pendingReactions.remove(key)
            if (list != null) pendingOrder.remove(key)
            list
        } ?: return

        for (pending in matched) {
            persistReactionIfNew(
                reactionDto(messageID, pending.parsed.emoji, pending.senderNodeName, pending.parsed.messageHash, pending.rawText, pending.channelIndex, null, pending.radioID),
            )
        }
    }

    // MARK: - Wire-Format Building (for sending)

    /** Builds channel-reaction wire text for sending: `{emoji}@[{sender}]\n{hash}`. */
    fun buildReactionText(emoji: String, targetSender: String, targetText: String, targetTimestamp: UInt): String =
        "$emoji@[$targetSender]\n${ReactionParser.generateMessageHash(targetText, targetTimestamp)}"

    /** Builds DM-reaction wire text for sending (shorter, no sender). */
    fun buildDMReactionText(emoji: String, targetText: String, targetTimestamp: UInt): String =
        ReactionParser.buildDMReactionText(emoji, targetText, targetTimestamp)

    // MARK: - Sending

    /**
     * Sends a channel-message reaction — an ordinary channel broadcast in reaction wire format —
     * and optimistically persists the local reaction row. Ported from the channel path of
     * `ChatViewModel.sendReaction`, minus the in-flight double-tap guard (see class doc).
     *
     * @return `false` without sending if [localNodeName] already reacted to [targetMessage] with [emoji].
     */
    suspend fun sendChannelReaction(
        messageService: MessageService,
        emoji: String,
        targetMessage: MessageDto,
        targetSenderName: String,
        channelIndex: UByte,
        radioID: UUID,
        localNodeName: String,
    ): Boolean {
        if (reactionStore.reactionExists(targetMessage.id, localNodeName, emoji)) return false
        val text = buildReactionText(emoji, targetSenderName, targetMessage.text, targetMessage.reactionTimestamp)
        messageService.sendChannelMessage(text, channelIndex, radioID)
        persistReactionIfNew(reactionDto(targetMessage.id, emoji, localNodeName, ReactionParser.generateMessageHash(targetMessage.text, targetMessage.reactionTimestamp), text, channelIndex, null, radioID))
        return true
    }

    /**
     * Sends a DM reaction — an ordinary direct message in reaction wire format — and
     * optimistically persists the local reaction row.
     *
     * @return `false` without sending if [localNodeName] already reacted to [targetMessage] with [emoji].
     */
    suspend fun sendDMReaction(
        messageService: MessageService,
        emoji: String,
        targetMessage: MessageDto,
        contact: ContactDto,
        localNodeName: String,
    ): Boolean {
        if (reactionStore.reactionExists(targetMessage.id, localNodeName, emoji)) return false
        val text = buildDMReactionText(emoji, targetMessage.text, targetMessage.reactionTimestamp)
        messageService.sendDirectMessage(text, contact)
        persistReactionIfNew(reactionDto(targetMessage.id, emoji, localNodeName, ReactionParser.generateMessageHash(targetMessage.text, targetMessage.reactionTimestamp), text, null, contact.id, contact.radioID))
        return true
    }

    // MARK: - Reading (for UI)

    /**
     * Every reaction on [messageID], most recent first — backs the reaction-details sheet
     * (`ReactionDetailsSheet.swift`'s `dataStore.fetchReactions(for:)` call, here routed through
     * this service rather than `app` reaching into [ReactionStore] directly, per this port's
     * `app` → `services` → `protocol` dependency rule).
     */
    suspend fun fetchReactions(messageID: UUID): List<ReactionDto> = reactionStore.fetchReactions(messageID)

    // MARK: - Lifecycle

    /** Clears all pending reactions (call on disconnect — see class doc, currently unwired). */
    suspend fun clearPendingReactions() {
        mutex.withLock {
            pendingReactions.clear()
            pendingOrder.clear()
            pendingDMReactions.clear()
            pendingDMOrder.clear()
        }
    }

    // MARK: - Private Helpers

    private fun reactionDto(
        messageID: UUID,
        emoji: String,
        senderName: String,
        messageHash: String,
        rawText: String,
        channelIndex: UByte?,
        contactID: UUID?,
        radioID: UUID,
    ) = ReactionDto(
        id = UUID.randomUUID(),
        messageID = messageID,
        emoji = emoji,
        senderName = senderName,
        messageHash = messageHash,
        rawText = rawText,
        receivedAt = Instant.now(),
        channelIndex = channelIndex,
        contactID = contactID,
        radioID = radioID,
    )

    private suspend fun findTargetMessage(parsed: ReactionParser.ParsedReaction, channelIndex: UByte): UUID? {
        val candidates = messageCache.lookup(channelIndex, parsed.targetSender, parsed.messageHash)
        return candidates.maxByOrNull { it.indexedAt }?.messageID
    }

    private suspend fun findDMTargetMessage(messageHash: String, contactID: UUID): UUID? {
        val candidates = messageCache.lookupDM(contactID, messageHash)
        return candidates.maxByOrNull { it.indexedAt }?.messageID
    }

    private suspend fun tryFindChannelMessage(
        radioID: UUID,
        channelIndex: UByte,
        targetSender: String,
        messageHash: String,
        localNodeName: String?,
        window: UIntRange,
    ): MessageDto? = try {
        messageStore.findChannelMessageForReaction(radioID, channelIndex, targetSender, messageHash, localNodeName, window)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    private suspend fun tryFindDMMessage(radioID: UUID, contactID: UUID, messageHash: String, window: UIntRange): MessageDto? = try {
        messageStore.findDMMessageForReaction(radioID, contactID, messageHash, window)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    private suspend fun queuePendingReaction(parsed: ReactionParser.ParsedReaction, channelIndex: UByte, senderNodeName: String, rawText: String, radioID: UUID) {
        val key = PendingReactionKey(channelIndex, parsed.targetSender, parsed.messageHash)
        val pending = PendingReaction(parsed, channelIndex, senderNodeName, rawText, radioID, Instant.now())
        mutex.withLock {
            val list = pendingReactions.getOrPut(key) {
                pendingOrder.add(key)
                mutableListOf()
            }
            list.add(pending)
            evictIfNeededLocked(pendingReactions, pendingOrder)
        }
    }

    private suspend fun queuePendingDMReaction(parsed: ReactionParser.ParsedDMReaction, contactID: UUID, senderName: String, rawText: String, radioID: UUID) {
        val key = PendingDMReactionKey(contactID, parsed.messageHash)
        val pending = PendingDMReaction(parsed, contactID, senderName, rawText, radioID, Instant.now())
        mutex.withLock {
            val list = pendingDMReactions.getOrPut(key) {
                pendingDMOrder.add(key)
                mutableListOf()
            }
            list.add(pending)
            evictIfNeededLocked(pendingDMReactions, pendingDMOrder)
        }
    }

    /** Evicts the oldest queued reaction (FIFO, one at a time) until under [MAX_PENDING_REACTIONS]. Caller holds [mutex]. */
    private fun <K, V> evictIfNeededLocked(pending: MutableMap<K, MutableList<V>>, order: MutableList<K>) {
        var total = pending.values.sumOf { it.size }
        while (total > MAX_PENDING_REACTIONS && order.isNotEmpty()) {
            val oldestKey = order.first()
            val entries = pending[oldestKey]
            if (entries != null && entries.isNotEmpty()) {
                entries.removeAt(0)
                total--
                if (entries.isEmpty()) {
                    pending.remove(oldestKey)
                    order.removeAt(0)
                }
            } else {
                order.removeAt(0)
            }
        }
    }

    /**
     * Persists [reaction] if [ReactionStore.reactionExists] doesn't already cover it, then
     * updates the target message's cached summary and emits [ReactionEvent.ReactionReceived].
     * Ported from `persistReactionIfNew`: returns `true` whenever the reaction wasn't a
     * pre-existing duplicate — *even if the save/summary step itself then fails* (matching
     * Swift's `guard exists != true else { return false }; ...; return true` shape exactly, not
     * "fixed" to be consistent).
     */
    private suspend fun persistReactionIfNew(reaction: ReactionDto): Boolean {
        val exists = try {
            reactionStore.reactionExists(reaction.messageID, reaction.senderName, reaction.emoji)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null // Swift's `try?` swallows to nil; `exists != true` then proceeds.
        }
        if (exists == true) return false

        val summary = persistAndUpdateSummary(reaction)
        if (summary != null) {
            eventsFlow.emit(ReactionEvent.ReactionReceived(reaction.messageID, summary))
        }
        return true
    }

    /** Ported from `persistReactionAndUpdateSummary`; `null` on any failure (fail-open, no logging — matches this port's business-service convention). */
    private suspend fun persistAndUpdateSummary(reaction: ReactionDto): String? {
        try {
            reactionStore.saveReaction(reaction)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return null
        }

        val allReactions = try {
            reactionStore.fetchReactions(reaction.messageID)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return null
        }

        val summary = buildSummary(allReactions)
        try {
            messageStore.updateMessageReactionSummary(reaction.messageID, summary)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return null
        }

        return summary
    }

    /**
     * Groups [reactions] by emoji, sorted by count descending with ties broken by earliest
     * [ReactionDto.receivedAt] ascending, then formats via [ReactionParser.buildSummary]. Ported
     * from `ReactionParser.buildSummary(from: [ReactionDTO])`; the DTO-grouping stays here
     * (business logic depending on [ReactionDto]) rather than in the dependency-free
     * [ReactionParser], which only formats already-ordered pairs.
     */
    private fun buildSummary(reactions: List<ReactionDto>): String {
        val ordered = reactions.groupBy { it.emoji }
            .map { (emoji, items) -> Triple(emoji, items.size, items.minOf { it.receivedAt }) }
            .sortedWith(compareByDescending<Triple<String, Int, Instant>> { it.second }.thenBy { it.third })
            .map { it.first to it.second }
        return ReactionParser.buildSummary(ordered)
    }

    private fun reactionTimestampWindow(anchor: UInt): UIntRange {
        val start = if (anchor > REACTION_TIMESTAMP_WINDOW_SECONDS) anchor - REACTION_TIMESTAMP_WINDOW_SECONDS else 0u
        return start..(anchor + REACTION_TIMESTAMP_WINDOW_SECONDS)
    }

    companion object {
        /** Session-lifetime pending-reaction queue cap (shared across all channel/DM keys), matching Swift's `maxPendingReactions`. */
        private const val MAX_PENDING_REACTIONS = 100

        /** Symmetric window (seconds) around a reaction's receive time for the DB-fallback candidate scan, matching `SyncCoordinator.reactionTimestampWindowSeconds`. */
        private val REACTION_TIMESTAMP_WINDOW_SECONDS = 300u
    }
}
