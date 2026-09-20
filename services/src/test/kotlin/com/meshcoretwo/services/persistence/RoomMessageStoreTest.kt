// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import com.meshcoretwo.services.backup.BackupDedupKeys
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

/**
 * Exercises [RoomMessageStore] (and, through it, [RoomMessageEntity]/[RoomMessageDto]) against a
 * real in-memory Room database via Robolectric — see [ContactStoreTest]'s doc for why Room needs
 * no `AndroidKeyStore` workaround.
 */
@RunWith(RobolectricTestRunner::class)
class RoomMessageStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: RoomMessageStore
    private val sessionID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomMessageStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun message(
        id: UUID = UUID.randomUUID(),
        text: String = "hello",
        timestamp: UInt = 1000u,
        authorKeyPrefix: ByteArray = ByteArray(4) { 0x01 },
        status: MessageStatus = MessageStatus.DELIVERED,
    ) = RoomMessageDto(
        id = id,
        sessionID = sessionID,
        authorKeyPrefix = authorKeyPrefix,
        text = text,
        timestamp = timestamp,
        // Fixed instead of the Instant.now() default — createdAt round-trips through the DB at
        // epochSecond precision (see Converters.kt), so Instant.now()'s sub-second component
        // would break equality against the fetched row. Same convention as ContactStoreTest.
        createdAt = Instant.ofEpochSecond(2000),
        status = status,
    )

    @Test
    fun `saveMessage then fetchMessage round-trips`() = runTest {
        val dto = message()
        store.saveMessage(dto)

        assertEquals(dto, store.fetchMessage(dto.id))
    }

    @Test
    fun `saveMessage silently ignores a duplicate by deduplication key`() = runTest {
        val first = message(text = "same text", timestamp = 500u)
        val second = message(text = "same text", timestamp = 500u)
        store.saveMessage(first)

        store.saveMessage(second)

        assertEquals(1, store.fetchMessages(sessionID).size)
        assertEquals(first.id, store.fetchMessages(sessionID).first().id)
    }

    @Test
    fun `saveMessage does not dedupe across different sessions`() = runTest {
        val other = UUID.randomUUID()
        val a = RoomMessageDto(sessionID = sessionID, authorKeyPrefix = ByteArray(4), text = "hi", timestamp = 1u)
        val b = RoomMessageDto(sessionID = other, authorKeyPrefix = ByteArray(4), text = "hi", timestamp = 1u)

        store.saveMessage(a)
        store.saveMessage(b)

        assertEquals(1, store.fetchMessages(sessionID).size)
        assertEquals(1, store.fetchMessages(other).size)
    }

    @Test
    fun `isDuplicateMessage reflects the deduplication key`() = runTest {
        val dto = message()
        assertFalse(store.isDuplicateMessage(sessionID, dto.deduplicationKey))

        store.saveMessage(dto)

        assertTrue(store.isDuplicateMessage(sessionID, dto.deduplicationKey))
    }

    @Test
    fun `fetchMessages orders oldest first`() = runTest {
        val older = message(timestamp = 100u, text = "older")
        val newer = message(timestamp = 200u, text = "newer")
        store.saveMessage(newer)
        store.saveMessage(older)

        val result = store.fetchMessages(sessionID)

        assertEquals(listOf("older", "newer"), result.map { it.text })
    }

    @Test
    fun `updateMessageStatus updates status and merges ackCode and roundTripTime`() = runTest {
        val dto = message(status = MessageStatus.PENDING)
        store.saveMessage(dto)

        store.updateMessageStatus(dto.id, MessageStatus.DELIVERED, ackCode = 42u, roundTripTime = 700u)

        val updated = store.fetchMessage(dto.id)!!
        assertEquals(MessageStatus.DELIVERED, updated.status)
        assertEquals(42u, updated.ackCode)
        assertEquals(700u, updated.roundTripTime)
    }

    @Test
    fun `updateMessageStatus without ackCode or roundTripTime leaves existing values`() = runTest {
        val dto = message(status = MessageStatus.PENDING).copy(ackCode = 9u, roundTripTime = 111u)
        store.saveMessage(dto)

        store.updateMessageStatus(dto.id, MessageStatus.SENT)

        val updated = store.fetchMessage(dto.id)!!
        assertEquals(MessageStatus.SENT, updated.status)
        assertEquals(9u, updated.ackCode)
        assertEquals(111u, updated.roundTripTime)
    }

    @Test
    fun `updateMessageStatus clears failureSeen only on a fresh transition into failed`() = runTest {
        val dto = message(status = MessageStatus.PENDING).copy(failureSeen = true)
        store.saveMessage(dto)

        store.updateMessageStatus(dto.id, MessageStatus.FAILED)
        assertFalse(store.fetchMessage(dto.id)!!.failureSeen)

        // Mark seen again, then re-apply .failed — must NOT reset a second time (not a fresh transition).
        database.roomMessageDao().fetchById(dto.id)!!.let { database.roomMessageDao().update(it.copy(failureSeen = true)) }
        store.updateMessageStatus(dto.id, MessageStatus.FAILED)
        assertTrue(store.fetchMessage(dto.id)!!.failureSeen)
    }

    @Test
    fun `updateMessageStatus is a no-op for an unknown id`() = runTest {
        store.updateMessageStatus(UUID.randomUUID(), MessageStatus.DELIVERED)
        // No exception; nothing to assert beyond "didn't crash".
    }

    @Test
    fun `updateMessageRetryStatus updates status retryAttempt and maxRetryAttempts`() = runTest {
        val dto = message(status = MessageStatus.FAILED)
        store.saveMessage(dto)

        store.updateMessageRetryStatus(dto.id, MessageStatus.PENDING, retryAttempt = 1, maxRetryAttempts = 5)

        val updated = store.fetchMessage(dto.id)!!
        assertEquals(MessageStatus.PENDING, updated.status)
        assertEquals(1, updated.retryAttempt)
        assertEquals(5, updated.maxRetryAttempts)
    }

    @Test
    fun `deleteMessages removes every row for the session only`() = runTest {
        val other = UUID.randomUUID()
        store.saveMessage(RoomMessageDto(sessionID = sessionID, authorKeyPrefix = ByteArray(4), text = "a", timestamp = 1u))
        store.saveMessage(RoomMessageDto(sessionID = other, authorKeyPrefix = ByteArray(4), text = "b", timestamp = 1u))

        store.deleteMessages(sessionID)

        assertTrue(store.fetchMessages(sessionID).isEmpty())
        assertEquals(1, store.fetchMessages(other).size)
    }

    @Test
    fun `fetchMessage returns null for an unknown id`() = runTest {
        assertNull(store.fetchMessage(UUID.randomUUID()))
    }

    @Test
    fun `existingRoomMessageKeys keys by (sessionID, deduplicationKey)`() = runTest {
        val dto = message()
        store.saveMessage(dto)

        val keys = store.existingRoomMessageKeys(setOf(sessionID))

        assertEquals(setOf(BackupDedupKeys.roomMessageKey(sessionID, dto.deduplicationKey)), keys)
        assertTrue(store.existingRoomMessageKeys(emptySet()).isEmpty())
    }

    @Test
    fun `batchInsertRoomMessages drops an orphaned message and skips a duplicate key`() = runTest {
        val orphan = message().copy(sessionID = UUID.randomUUID())
        val known = message(text = "known")
        val fresh = message(text = "fresh")
        val existingKey = BackupDedupKeys.roomMessageKey(sessionID, known.deduplicationKey)

        val result = store.batchInsertRoomMessages(listOf(orphan, known, fresh), existingKeys = setOf(existingKey), existingSessionIds = setOf(sessionID))

        assertEquals(1, result.counts.inserted)
        assertEquals(2, result.counts.skipped)
        assertEquals(setOf(sessionID), result.affectedParentIds)
        assertEquals(1, store.fetchMessages(sessionID).size)
    }
}
