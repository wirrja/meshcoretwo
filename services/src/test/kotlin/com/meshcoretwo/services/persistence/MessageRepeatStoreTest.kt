// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
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
class MessageRepeatStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: MessageRepeatStore
    private val messageID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = MessageRepeatStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun repeat(
        id: UUID = UUID.randomUUID(),
        messageID: UUID = this.messageID,
        // Instant is stored as epoch-seconds (see Converters), so round-tripping Instant.now()
        // would lose sub-second precision and break the exact-equality round-trip test below.
        receivedAt: Instant = Instant.ofEpochSecond(Instant.now().epochSecond),
        rxLogEntryID: UUID? = UUID.randomUUID(),
    ) = MessageRepeatDto(
        id = id,
        messageID = messageID,
        receivedAt = receivedAt,
        pathNodes = byteArrayOf(0x42),
        pathLength = 1u,
        snr = 8.0,
        rssi = -70,
        rxLogEntryID = rxLogEntryID,
    )

    @Test
    fun `saveMessageRepeat and fetchMessageRepeats round-trip`() = runTest {
        val entry = repeat()
        store.saveMessageRepeat(entry)

        val fetched = store.fetchMessageRepeats(messageID)

        assertEquals(1, fetched.size)
        assertEquals(entry, fetched.first())
    }

    @Test
    fun `fetchMessageRepeats returns oldest first`() = runTest {
        val base = Instant.now()
        val second = repeat(receivedAt = base.plusSeconds(10))
        val first = repeat(receivedAt = base)
        store.saveMessageRepeat(second)
        store.saveMessageRepeat(first)

        val fetched = store.fetchMessageRepeats(messageID)

        assertEquals(listOf(first.id, second.id), fetched.map { it.id })
    }

    @Test
    fun `fetchMessageRepeats is scoped to messageID`() = runTest {
        store.saveMessageRepeat(repeat())
        store.saveMessageRepeat(repeat(messageID = UUID.randomUUID()))

        assertEquals(1, store.fetchMessageRepeats(messageID).size)
    }

    @Test
    fun `messageRepeatExists is true for a recorded rxLogEntryID`() = runTest {
        val rxLogEntryID = UUID.randomUUID()
        store.saveMessageRepeat(repeat(rxLogEntryID = rxLogEntryID))

        assertTrue(store.messageRepeatExists(rxLogEntryID))
    }

    @Test
    fun `messageRepeatExists is false for an unknown rxLogEntryID`() = runTest {
        assertFalse(store.messageRepeatExists(UUID.randomUUID()))
    }

    @Test
    fun `existingRepeatIds returns ids of repeats already recorded for the given messages`() = runTest {
        val existing = repeat()
        store.saveMessageRepeat(existing)

        val ids = store.existingRepeatIds(setOf(messageID))

        assertEquals(setOf(existing.id), ids)
        assertTrue(store.existingRepeatIds(emptySet()).isEmpty())
    }

    @Test
    fun `batchInsertMessageRepeats drops a repeat whose parent message doesn't exist locally`() = runTest {
        val dto = repeat()

        val result = store.batchInsertMessageRepeats(listOf(dto), existingIds = emptySet(), existingMessageIds = emptySet())

        assertEquals(0, result.counts.inserted)
        assertEquals(1, result.counts.skipped)
        assertTrue(result.affectedParentIds.isEmpty())
    }

    @Test
    fun `batchInsertMessageRepeats skips an already-recorded id and inserts a new one, reporting the affected parent`() = runTest {
        val known = repeat()
        val fresh = repeat()

        val result = store.batchInsertMessageRepeats(listOf(known, fresh), existingIds = setOf(known.id), existingMessageIds = setOf(messageID))

        assertEquals(1, result.counts.inserted)
        assertEquals(1, result.counts.skipped)
        assertEquals(setOf(messageID), result.affectedParentIds)
        assertEquals(1, store.fetchMessageRepeats(messageID).size)
    }
}
