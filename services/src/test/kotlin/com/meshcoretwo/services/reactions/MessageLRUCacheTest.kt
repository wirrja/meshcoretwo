// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.reactions

import com.meshcoretwo.services.utilities.ReactionParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MessageLRUCacheTest {
    @Test
    fun `index then lookup finds the candidate by channel key`() = runTest {
        val cache = MessageLRUCache()
        val id = UUID.randomUUID()

        cache.index(id, channelIndex = 0u, senderName = "Alice", text = "hi", timestamp = 100u)
        val hash = ReactionParser.generateMessageHash("hi", 100u)

        val found = cache.lookup(0u, "Alice", hash)
        assertEquals(listOf(id), found.map { it.messageID })
    }

    @Test
    fun `lookup with no matching key returns empty`() = runTest {
        val cache = MessageLRUCache()
        assertTrue(cache.lookup(0u, "Nobody", "abcdefgh").isEmpty())
    }

    @Test
    fun `re-indexing the same message id replaces rather than duplicates it`() = runTest {
        val cache = MessageLRUCache()
        val id = UUID.randomUUID()

        cache.index(id, 0u, "Alice", "hi", 100u)
        cache.index(id, 0u, "Alice", "hi", 100u)

        val hash = ReactionParser.generateMessageHash("hi", 100u)
        assertEquals(1, cache.lookup(0u, "Alice", hash).size)
    }

    @Test
    fun `prunes to maxCandidatesPerKey, keeping the most recent`() = runTest {
        val cache = MessageLRUCache(maxCandidatesPerKey = 2)
        // Same key (channel/sender/hash) via distinct message ids indexed under the same content+timestamp
        // isn't representative (hash depends on content), so instead exercise via repeated distinct texts
        // that happen to collide on hash is impractical — verify the cap using direct-key collisions instead.
        val idA = UUID.randomUUID()
        val idB = UUID.randomUUID()
        val idC = UUID.randomUUID()
        cache.index(idA, 0u, "Alice", "same", 1u)
        cache.index(idB, 0u, "Alice", "same", 1u)
        cache.index(idC, 0u, "Alice", "same", 1u)

        val hash = ReactionParser.generateMessageHash("same", 1u)
        val found = cache.lookup(0u, "Alice", hash)
        assertEquals(2, found.size)
        assertEquals(listOf(idB, idC), found.map { it.messageID })
    }

    @Test
    fun `DM cache is independent of the channel cache`() = runTest {
        val cache = MessageLRUCache()
        val contactID = UUID.randomUUID()
        val id = UUID.randomUUID()

        cache.indexDM(id, contactID, "hi", 100u)

        val hash = ReactionParser.generateMessageHash("hi", 100u)
        assertEquals(listOf(id), cache.lookupDM(contactID, hash).map { it.messageID })
        assertTrue(cache.lookup(0u, "Alice", hash).isEmpty())
    }

    @Test
    fun `clear empties both caches`() = runTest {
        val cache = MessageLRUCache()
        val contactID = UUID.randomUUID()
        cache.index(UUID.randomUUID(), 0u, "Alice", "hi", 100u)
        cache.indexDM(UUID.randomUUID(), contactID, "hi", 100u)

        cache.clear()

        val hash = ReactionParser.generateMessageHash("hi", 100u)
        assertTrue(cache.lookup(0u, "Alice", hash).isEmpty())
        assertTrue(cache.lookupDM(contactID, hash).isEmpty())
    }
}
