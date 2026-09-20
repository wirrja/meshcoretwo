// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import com.meshcoretwo.protocol.ChannelInfo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ChannelStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: ChannelStore
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = ChannelStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun channelInfo(index: UByte = 0u, name: String = "General", secret: ByteArray = ByteArray(16) { 0x01 }) =
        ChannelInfo(index, name, secret)

    @Test
    fun `fetchChannels is empty for an unknown device`() = runTest {
        assertTrue(store.fetchChannels(radioID).isEmpty())
    }

    @Test
    fun `saveChannel inserts a new channel`() = runTest {
        val id = store.saveChannel(radioID, channelInfo(index = 2u, name = "Ops"))

        val fetched = store.fetchChannelById(id)
        assertNotNull(fetched)
        assertEquals("Ops", fetched!!.name)
        assertEquals(2u.toUByte(), fetched.index)
    }

    @Test
    fun `saveChannel updates an existing channel matched by radioID and index`() = runTest {
        val firstId = store.saveChannel(radioID, channelInfo(index = 1u, name = "Original"))
        val secondId = store.saveChannel(radioID, channelInfo(index = 1u, name = "Renamed"))

        assertEquals("Second save at the same (radioID, index) should update, not insert", firstId, secondId)
        assertEquals(1, store.fetchChannels(radioID).size)
        assertEquals("Renamed", store.fetchChannelById(firstId)?.name)
    }

    @Test
    fun `saveChannel preserves app-only metadata across a wire update`() = runTest {
        val id = store.saveChannel(radioID, channelInfo(index = 0u, name = "General"))
        val existingEntity = database.channelDao().fetchChannel(id)!!
        database.channelDao().update(existingEntity.copy(isFavorite = true, unreadCount = 5))

        store.saveChannel(radioID, channelInfo(index = 0u, name = "General Renamed"))

        val after = store.fetchChannelById(id)!!
        assertEquals("General Renamed", after.name)
        assertTrue(after.isFavorite)
        assertEquals(5, after.unreadCount)
    }

    @Test
    fun `batchSaveChannels upserts configured channels`() = runTest {
        val configured = listOf(channelInfo(index = 0u, name = "A"), channelInfo(index = 1u, name = "B"))

        val result = store.batchSaveChannels(radioID, configured, unconfiguredIndices = emptyList(), pruneBeyond = null)

        assertEquals(2, result.size)
        assertEquals(2, store.fetchChannels(radioID).size)
    }

    @Test
    fun `batchSaveChannels deletes rows at unconfigured indices`() = runTest {
        store.saveChannel(radioID, channelInfo(index = 0u, name = "A"))
        store.saveChannel(radioID, channelInfo(index = 1u, name = "B"))

        store.batchSaveChannels(radioID, configured = emptyList(), unconfiguredIndices = listOf(1u), pruneBeyond = null)

        val remaining = store.fetchChannels(radioID)
        assertEquals(1, remaining.size)
        assertEquals("A", remaining.first().name)
    }

    @Test
    fun `batchSaveChannels prunes rows at or beyond pruneBeyond`() = runTest {
        store.saveChannel(radioID, channelInfo(index = 0u, name = "A"))
        store.saveChannel(radioID, channelInfo(index = 5u, name = "Stale"))

        store.batchSaveChannels(radioID, configured = emptyList(), unconfiguredIndices = emptyList(), pruneBeyond = 4u)

        val remaining = store.fetchChannels(radioID)
        assertEquals(1, remaining.size)
        assertEquals("A", remaining.first().name)
    }

    @Test
    fun `batchSaveChannels leaves an index untouched when neither configured nor unconfigured`() = runTest {
        store.saveChannel(radioID, channelInfo(index = 2u, name = "Survivor"))

        // Index 2 appears in neither list — e.g. skipped by a circuit breaker mid-sync.
        store.batchSaveChannels(radioID, configured = emptyList(), unconfiguredIndices = emptyList(), pruneBeyond = null)

        assertEquals(1, store.fetchChannels(radioID).size)
    }

    @Test
    fun `deleteChannel removes the row`() = runTest {
        val id = store.saveChannel(radioID, channelInfo())

        store.deleteChannel(id)

        assertNull(store.fetchChannelById(id))
    }

    @Test
    fun `fetchChannel by radioID and index finds the matching row`() = runTest {
        store.saveChannel(radioID, channelInfo(index = 3u, name = "Found"))

        assertEquals("Found", store.fetchChannel(radioID, 3u)?.name)
        assertNull(store.fetchChannel(radioID, 4u))
    }

    @Test
    fun `incrementChannelUnreadCount and clearChannelUnreadCount`() = runTest {
        val id = store.saveChannel(radioID, channelInfo())

        store.incrementChannelUnreadCount(id)
        store.incrementChannelUnreadCount(id)
        assertEquals(2, store.fetchChannelById(id)!!.unreadCount)

        store.clearChannelUnreadCount(id)
        assertEquals(0, store.fetchChannelById(id)!!.unreadCount)
    }

    @Test
    fun `channel unread mention count increments, decrements clamped at zero, and clears`() = runTest {
        val id = store.saveChannel(radioID, channelInfo())

        store.decrementChannelUnreadMentionCount(id)
        assertEquals("decrement on a zero counter must clamp, not underflow", 0, store.fetchChannelById(id)!!.unreadMentionCount)

        store.incrementChannelUnreadMentionCount(id)
        store.incrementChannelUnreadMentionCount(id)
        assertEquals(2, store.fetchChannelById(id)!!.unreadMentionCount)

        store.decrementChannelUnreadMentionCount(id)
        assertEquals(1, store.fetchChannelById(id)!!.unreadMentionCount)

        store.clearChannelUnreadMentionCount(id)
        assertEquals(0, store.fetchChannelById(id)!!.unreadMentionCount)
    }

    private fun channelDto(
        radioID: UUID = this.radioID,
        index: UByte = 0u,
        secret: ByteArray = ByteArray(16) { 0x01 },
        unreadCount: Int = 0,
    ) = ChannelDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        index = index,
        name = "Channel",
        secret = secret,
        isEnabled = true,
        lastMessageDate = null,
        unreadCount = unreadCount,
        unreadMentionCount = 0,
        notificationLevel = NotificationLevel.ALL,
        isFavorite = false,
        floodScopeModeRawValue = "inherit",
        regionScope = null,
    )

    @Test
    fun `batchInsertChannels inserts a new channel at its own free slot`() = runTest {
        val dto = channelDto(index = 3u, secret = byteArrayOf(9, 9, 9))

        val result = store.batchInsertChannels(listOf(dto), setOf(radioID))

        assertEquals(1, result.counts.inserted)
        assertEquals(3u.toUByte(), store.fetchChannels(radioID).single().index)
    }

    @Test
    fun `batchInsertChannels merges by stable secret and remaps the backup index to the existing slot`() = runTest {
        val secret = byteArrayOf(5, 5, 5)
        val localId = store.saveChannel(radioID, channelInfo(index = 2u, secret = secret))
        val backup = channelDto(index = 5u, secret = secret, unreadCount = 3)

        val result = store.batchInsertChannels(listOf(backup), setOf(radioID))

        assertEquals(0, result.counts.inserted)
        assertEquals(1, result.counts.merged)
        assertEquals(mapOf(radioID to mapOf(5u.toUByte() to 2u.toUByte())), result.channelIndexRemap)
        assertEquals(3, store.fetchChannelById(localId)?.unreadCount)
    }

    @Test
    fun `batchInsertChannels merges an empty-secret channel by slot index`() = runTest {
        val localId = store.saveChannel(radioID, channelInfo(index = 0u, secret = ByteArray(16)))
        val backup = channelDto(index = 0u, secret = ByteArray(16), unreadCount = 4)

        val result = store.batchInsertChannels(listOf(backup), setOf(radioID))

        assertEquals(1, result.counts.merged)
        assertEquals(4, store.fetchChannelById(localId)?.unreadCount)
    }

    @Test
    fun `batchInsertChannels relocates to the lowest free slot when its own slot is taken by a different channel`() = runTest {
        store.saveChannel(radioID, channelInfo(index = 1u, secret = byteArrayOf(1, 1, 1)))
        val backup = channelDto(index = 1u, secret = byteArrayOf(2, 2, 2))

        val result = store.batchInsertChannels(listOf(backup), setOf(radioID), mapOf(radioID to 4u))

        assertEquals(1, result.counts.inserted)
        assertEquals(mapOf(radioID to mapOf(1u.toUByte() to 2u.toUByte())), result.channelIndexRemap)
        assertEquals(mapOf(radioID to setOf(2u.toUByte())), result.insertedLocalIndices)
        assertEquals(2u.toUByte(), store.fetchChannels(radioID).first { it.secret.contentEquals(backup.secret) }.index)
    }

    @Test
    fun `batchInsertChannels drops a channel with no free local slot`() = runTest {
        store.saveChannel(radioID, channelInfo(index = 1u, secret = byteArrayOf(1, 1, 1)))
        val backup = channelDto(index = 1u, secret = byteArrayOf(2, 2, 2))

        val result = store.batchInsertChannels(listOf(backup), setOf(radioID), mapOf(radioID to 2u))

        assertEquals(0, result.counts.inserted)
        assertEquals(1, result.counts.dropped)
        assertEquals(mapOf(radioID to setOf(1u.toUByte())), result.droppedChannelIndices)
        assertEquals(1, store.fetchChannels(radioID).size)
    }

    @Test
    fun `applyLastMessageDatesToChannels advances lastMessageDate keyed by (radioID, index) only when newer`() = runTest {
        val id = store.saveChannel(radioID, channelInfo(index = 3u))
        val older = Instant.ofEpochSecond(1000)
        val newer = Instant.ofEpochSecond(2000)
        database.channelDao().fetchChannel(id)?.let { database.channelDao().update(it.copy(lastMessageDate = newer)) }

        store.applyLastMessageDatesToChannels(mapOf(radioID to mapOf(3u.toUByte() to older)))
        assertEquals("an already-newer local date must not regress", newer, store.fetchChannelById(id)?.lastMessageDate)

        store.applyLastMessageDatesToChannels(mapOf(radioID to mapOf(3u.toUByte() to Instant.ofEpochSecond(3000))))
        assertEquals(Instant.ofEpochSecond(3000), store.fetchChannelById(id)?.lastMessageDate)
    }

    // MARK: - Slot occupant change wipes history (upstream 70b4bcfd)

    private fun message(channelIndex: UByte, text: String = "hi") = MessageDto(
        id = UUID.randomUUID(), radioID = radioID, contactID = null, channelIndex = channelIndex, text = text,
        timestamp = 1000u, createdAt = Instant.now(), sortDate = Instant.now(),
        direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
        textType = com.meshcoretwo.protocol.TextType.PLAIN_TEXT, ackCode = null, pathLength = 0u, snr = null,
        pathNodes = null, senderKeyPrefix = null, senderNodeName = null, isRead = false, replyToID = null,
        roundTripTime = null, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0, deduplicationKey = null,
        reactionSummary = null, senderTimestamp = null, routeType = null, heardRepeats = 0,
    )

    private suspend fun messageCount(index: UByte) = MessageStore(database).fetchMessages(radioID, index, limit = 100).size

    @Test
    fun `saveChannel with a changed secret wipes the slot's history and counters`() = runTest {
        val id = store.saveChannel(radioID, channelInfo(index = 3u, secret = ByteArray(16) { 0x01 }))
        MessageStore(database).saveMessage(message(3u))
        store.incrementChannelUnreadCount(id)
        store.updateChannelLastMessage(id, Instant.now())

        store.saveChannel(radioID, channelInfo(index = 3u, name = "Other", secret = ByteArray(16) { 0x02 }))

        assertEquals(0, messageCount(3u))
        val row = store.fetchChannelById(id)!!
        assertEquals(0, row.unreadCount)
        assertNull(row.lastMessageDate)
    }

    @Test
    fun `saveChannel with the same secret keeps history even when the name changes`() = runTest {
        store.saveChannel(radioID, channelInfo(index = 3u, name = "A"))
        MessageStore(database).saveMessage(message(3u))

        store.saveChannel(radioID, channelInfo(index = 3u, name = "B"))

        assertEquals(1, messageCount(3u))
    }

    @Test
    fun `batchSaveChannels wipes a rewritten slot, an unconfigured slot, and pruned slots but not a slot below the prune threshold`() = runTest {
        store.saveChannel(radioID, channelInfo(index = 1u, secret = ByteArray(16) { 0x01 }))
        store.saveChannel(radioID, channelInfo(index = 2u, secret = ByteArray(16) { 0x02 }))
        store.saveChannel(radioID, channelInfo(index = 5u, secret = ByteArray(16) { 0x05 }))
        store.saveChannel(radioID, channelInfo(index = 6u, secret = ByteArray(16) { 0x06 }))
        val messages = MessageStore(database)
        listOf<UByte>(1u, 2u, 4u, 5u, 6u).forEach { messages.saveMessage(message(it)) }

        store.batchSaveChannels(
            radioID,
            configured = listOf(channelInfo(index = 1u, secret = ByteArray(16) { 0x09 }), channelInfo(index = 6u, secret = ByteArray(16) { 0x06 })),
            unconfiguredIndices = listOf<UByte>(2u, 4u), // 4 has leftover messages but no row
            pruneBeyond = 6u.toUByte(),
        )

        assertEquals(0, messageCount(1u)) // secret changed
        assertEquals(0, messageCount(2u)) // reported empty
        assertEquals(0, messageCount(4u)) // reported empty, row already gone
        assertEquals(1, messageCount(5u)) // index 5 < pruneBeyond 6 and not touched
        assertEquals(0, messageCount(6u)) // index >= pruneBeyond
    }

    @Test
    fun `deleteChannel wipes the slot's messages with the row`() = runTest {
        val id = store.saveChannel(radioID, channelInfo(index = 7u))
        MessageStore(database).saveMessage(message(7u))

        store.deleteChannel(id)

        assertNull(store.fetchChannelById(id))
        assertEquals(0, messageCount(7u))
    }
}
