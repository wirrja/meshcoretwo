// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.reactions

import com.meshcoretwo.services.utilities.ReactionParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/** A recently-indexed message, candidate to match an incoming reaction. Ported from `MessageCandidate`. */
data class MessageCandidate(val messageID: UUID, val text: String, val timestamp: UInt, val indexedAt: Instant = Instant.now())

private data class MessageCacheKey(val channelIndex: UByte, val senderName: String, val messageHash: String)
private data class DirectMessageCacheKey(val contactID: UUID, val messageHash: String)

/**
 * LRU index of recently-seen messages, keyed by content hash, so an incoming reaction can find
 * its target without a DB scan. Ported from `MessageLRUCache.swift` (a Swift `actor`) — a
 * `Mutex` guards the four maps here instead, matching this port's established actor→coroutine
 * convention (see e.g. [com.meshcoretwo.services.messages.MessageService]'s `pendingAcks`).
 */
class MessageLRUCache(private val capacity: Int = 500, private val maxCandidatesPerKey: Int = 5) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<MessageCacheKey, MutableList<MessageCandidate>>()
    private val order = mutableListOf<MessageCacheKey>()
    private val dmCache = mutableMapOf<DirectMessageCacheKey, MutableList<MessageCandidate>>()
    private val dmOrder = mutableListOf<DirectMessageCacheKey>()

    /** Indexes a channel message for later lookup. */
    suspend fun index(messageID: UUID, channelIndex: UByte, senderName: String, text: String, timestamp: UInt) {
        val key = MessageCacheKey(channelIndex, senderName, ReactionParser.generateMessageHash(text, timestamp))
        mutex.withLock { indexLocked(cache, order, key, MessageCandidate(messageID, text, timestamp)) }
    }

    /** Looks up channel-message candidates by cache key. */
    suspend fun lookup(channelIndex: UByte, senderName: String, messageHash: String): List<MessageCandidate> =
        mutex.withLock { cache[MessageCacheKey(channelIndex, senderName, messageHash)]?.toList() ?: emptyList() }

    /** Indexes a DM message for later lookup. */
    suspend fun indexDM(messageID: UUID, contactID: UUID, text: String, timestamp: UInt) {
        val key = DirectMessageCacheKey(contactID, ReactionParser.generateMessageHash(text, timestamp))
        mutex.withLock { indexLocked(dmCache, dmOrder, key, MessageCandidate(messageID, text, timestamp)) }
    }

    /** Looks up DM candidates by cache key. */
    suspend fun lookupDM(contactID: UUID, messageHash: String): List<MessageCandidate> =
        mutex.withLock { dmCache[DirectMessageCacheKey(contactID, messageHash)]?.toList() ?: emptyList() }

    /** Clears the cache. */
    suspend fun clear() {
        mutex.withLock {
            cache.clear()
            order.clear()
            dmCache.clear()
            dmOrder.clear()
        }
    }

    /** Shared insert-with-eviction logic for both the channel and DM caches. Caller holds [mutex]. */
    private fun <K> indexLocked(cacheMap: MutableMap<K, MutableList<MessageCandidate>>, keyOrder: MutableList<K>, key: K, candidate: MessageCandidate) {
        keyOrder.remove(key)
        keyOrder.add(key)
        if (keyOrder.size > capacity) {
            val oldest = keyOrder.removeAt(0)
            cacheMap.remove(oldest)
        }

        val candidates = cacheMap.getOrPut(key) { mutableListOf() }
        candidates.removeAll { it.messageID == candidate.messageID }
        candidates.add(candidate)
        while (candidates.size > maxCandidatesPerKey) candidates.removeAt(0)
    }
}
