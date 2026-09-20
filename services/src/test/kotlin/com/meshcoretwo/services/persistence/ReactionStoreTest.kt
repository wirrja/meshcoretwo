// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import com.meshcoretwo.services.backup.BackupDedupKeys
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ReactionStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: ReactionStore
    private val radioID = UUID.randomUUID()
    private val messageID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = ReactionStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun reaction(
        emoji: String = "👍",
        senderName: String = "Alice",
        receivedAt: Instant = Instant.now(),
        channelIndex: UByte? = 0u,
        contactID: UUID? = null,
    ) = ReactionDto(
        id = UUID.randomUUID(),
        messageID = messageID,
        emoji = emoji,
        senderName = senderName,
        messageHash = "abcdefgh",
        rawText = "$emoji@[$senderName]\nabcdefgh",
        receivedAt = receivedAt,
        channelIndex = channelIndex,
        contactID = contactID,
        radioID = radioID,
    )

    @Test
    fun `saveReaction then fetchReactions round-trips`() = runTest {
        store.saveReaction(reaction())

        val fetched = store.fetchReactions(messageID)

        assertEquals(1, fetched.size)
        assertEquals("👍", fetched[0].emoji)
        assertEquals("Alice", fetched[0].senderName)
    }

    @Test
    fun `fetchReactions orders most recent first`() = runTest {
        val older = reaction(emoji = "👍", receivedAt = Instant.ofEpochSecond(1000))
        val newer = reaction(emoji = "❤️", senderName = "Bob", receivedAt = Instant.ofEpochSecond(2000))
        store.saveReaction(older)
        store.saveReaction(newer)

        val fetched = store.fetchReactions(messageID)

        assertEquals(listOf("❤️", "👍"), fetched.map { it.emoji })
    }

    @Test
    fun `reactionExists is true only for a matching (messageID, senderName, emoji) triple`() = runTest {
        store.saveReaction(reaction(emoji = "👍", senderName = "Alice"))

        assertTrue(store.reactionExists(messageID, "Alice", "👍"))
        assertFalse(store.reactionExists(messageID, "Alice", "❤️"))
        assertFalse(store.reactionExists(messageID, "Bob", "👍"))
        assertFalse(store.reactionExists(UUID.randomUUID(), "Alice", "👍"))
    }

    @Test
    fun `deleteReactionsForMessage removes every reaction for that message only`() = runTest {
        val otherMessageID = UUID.randomUUID()
        store.saveReaction(reaction())
        val untouched = ReactionDto(
            id = UUID.randomUUID(), messageID = otherMessageID, emoji = "😂", senderName = "Carol",
            messageHash = "ijkmnpqr", rawText = "x", receivedAt = Instant.now(), channelIndex = 0u, contactID = null, radioID = radioID,
        )
        store.saveReaction(untouched)

        store.deleteReactionsForMessage(messageID)

        assertTrue(store.fetchReactions(messageID).isEmpty())
        assertEquals(1, store.fetchReactions(otherMessageID).size)
    }

    @Test
    fun `existingReactionKeys keys by (messageID, senderName, emoji)`() = runTest {
        val dto = reaction()
        store.saveReaction(dto)

        val keys = store.existingReactionKeys(setOf(messageID))

        assertEquals(setOf(BackupDedupKeys.reactionKey(messageID, dto.senderName, dto.emoji)), keys)
        assertTrue(store.existingReactionKeys(emptySet()).isEmpty())
    }

    @Test
    fun `batchInsertReactions drops an orphaned reaction and skips a duplicate key`() = runTest {
        val orphan = ReactionDto(
            id = UUID.randomUUID(), messageID = UUID.randomUUID(), emoji = "👍", senderName = "Orphan",
            messageHash = "abcdefgh", rawText = "x", receivedAt = Instant.now(), channelIndex = 0u, contactID = null, radioID = radioID,
        )
        val known = reaction(senderName = "Bob")
        val fresh = reaction(senderName = "Carol")
        val existingKey = BackupDedupKeys.reactionKey(messageID, known.senderName, known.emoji)

        val result = store.batchInsertReactions(listOf(orphan, known, fresh), existingKeys = setOf(existingKey), existingMessageIds = setOf(messageID))

        assertEquals(1, result.counts.inserted)
        assertEquals(2, result.counts.skipped)
        assertEquals(setOf(messageID), result.affectedParentIds)
        assertEquals(1, store.fetchReactions(messageID).size)
    }
}
