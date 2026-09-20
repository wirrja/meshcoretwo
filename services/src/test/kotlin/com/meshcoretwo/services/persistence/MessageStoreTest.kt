// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import com.meshcoretwo.protocol.TextType
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

@RunWith(RobolectricTestRunner::class)
class MessageStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: MessageStore
    private val radioID = UUID.randomUUID()
    private val contactID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = MessageStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun directMessage(
        id: UUID = UUID.randomUUID(),
        text: String = "hi",
        sortDate: Instant = Instant.now(),
        status: MessageStatus = MessageStatus.PENDING,
        deduplicationKey: String? = null,
        heardRepeats: Int = 0,
    ) = MessageDto(
        id = id,
        radioID = radioID,
        contactID = contactID,
        channelIndex = null,
        text = text,
        timestamp = sortDate.epochSecond.toUInt(),
        createdAt = sortDate,
        sortDate = sortDate,
        direction = MessageDirection.OUTGOING,
        status = status,
        textType = TextType.PLAIN_TEXT,
        ackCode = null,
        pathLength = 0u,
        snr = null,
        pathNodes = null,
        senderKeyPrefix = null,
        senderNodeName = null,
        isRead = false,
        replyToID = null,
        roundTripTime = null,
        sendCount = 1,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        deduplicationKey = deduplicationKey,
        reactionSummary = null,
        senderTimestamp = null,
        routeType = null,
        heardRepeats = heardRepeats,
    )

    @Test
    fun `saveMessage and fetchMessage round-trip`() = runTest {
        val message = directMessage(text = "hello there")
        store.saveMessage(message)

        val fetched = store.fetchMessage(message.id)

        assertEquals("hello there", fetched?.text)
        assertEquals(MessageStatus.PENDING, fetched?.status)
    }

    @Test
    fun `fetchMessage returns null for an unknown id`() = runTest {
        assertNull(store.fetchMessage(UUID.randomUUID()))
    }

    @Test
    fun `isDuplicateMessage is scoped per radio`() = runTest {
        store.saveMessage(directMessage(deduplicationKey = "dedupe-1"))

        assertTrue(store.isDuplicateMessage("dedupe-1", radioID))
        assertFalse(store.isDuplicateMessage("dedupe-1", UUID.randomUUID()))
        assertFalse(store.isDuplicateMessage("dedupe-2", radioID))
    }

    @Test
    fun `fetchMessages for a contact returns oldest first`() = runTest {
        val base = Instant.now()
        val first = directMessage(text = "first", sortDate = base)
        val second = directMessage(text = "second", sortDate = base.plusSeconds(60))
        val third = directMessage(text = "third", sortDate = base.plusSeconds(120))
        // Insert out of order to prove the store sorts, not the insertion order.
        store.saveMessage(second)
        store.saveMessage(third)
        store.saveMessage(first)

        val messages = store.fetchMessages(contactID)

        assertEquals(listOf("first", "second", "third"), messages.map { it.text })
    }

    @Test
    fun `fetchMessages for a channel is scoped to radioID and channelIndex`() = runTest {
        val channelMessage = directMessage().copy(contactID = null, channelIndex = 3u)
        val otherChannelMessage = directMessage().copy(contactID = null, channelIndex = 4u)
        store.saveMessage(channelMessage)
        store.saveMessage(otherChannelMessage)

        val messages = store.fetchMessages(radioID, 3u)

        assertEquals(1, messages.size)
        assertEquals(channelMessage.id, messages.first().id)
    }

    @Test
    fun `updateMessageStatus overwrites unconditionally`() = runTest {
        val message = directMessage(status = MessageStatus.DELIVERED)
        store.saveMessage(message)

        store.updateMessageStatus(message.id, MessageStatus.FAILED)

        assertEquals(MessageStatus.FAILED, store.fetchMessage(message.id)?.status)
    }

    @Test
    fun `updateMessageStatusUnlessDelivered skips an already-delivered row`() = runTest {
        val message = directMessage(status = MessageStatus.DELIVERED)
        store.saveMessage(message)

        val changed = store.updateMessageStatusUnlessDelivered(message.id, MessageStatus.FAILED)

        assertFalse(changed)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(message.id)?.status)
    }

    @Test
    fun `updateMessageStatusUnlessDelivered updates a non-terminal row`() = runTest {
        val message = directMessage(status = MessageStatus.SENT)
        store.saveMessage(message)

        val changed = store.updateMessageStatusUnlessDelivered(message.id, MessageStatus.FAILED)

        assertTrue(changed)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(message.id)?.status)
    }

    @Test
    fun `updateMessageAck is dropped when the row is already terminal with a different status`() = runTest {
        val message = directMessage(status = MessageStatus.FAILED)
        store.saveMessage(message)

        store.updateMessageAck(message.id, ackCode = 42u, status = MessageStatus.DELIVERED, roundTripTime = 100u)

        val after = store.fetchMessage(message.id)
        assertEquals(MessageStatus.FAILED, after?.status)
        assertNull(after?.ackCode)
    }

    @Test
    fun `updateMessageAck writes through on a non-terminal row`() = runTest {
        val message = directMessage(status = MessageStatus.SENT)
        store.saveMessage(message)

        store.updateMessageAck(message.id, ackCode = 42u, status = MessageStatus.DELIVERED, roundTripTime = 100u)

        val after = store.fetchMessage(message.id)
        assertEquals(MessageStatus.DELIVERED, after?.status)
        assertEquals(42u, after?.ackCode)
        assertEquals(100u, after?.roundTripTime)
    }

    @Test
    fun `markMessageAsRead sets isRead`() = runTest {
        val message = directMessage()
        store.saveMessage(message)

        store.markMessageAsRead(message.id)

        assertTrue(store.fetchMessage(message.id)!!.isRead)
    }

    @Test
    fun `incrementMessageHeardRepeats increments and returns the new count`() = runTest {
        val message = directMessage()
        store.saveMessage(message)

        val first = store.incrementMessageHeardRepeats(message.id)
        val second = store.incrementMessageHeardRepeats(message.id)

        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(2, store.fetchMessage(message.id)?.heardRepeats)
    }

    @Test
    fun `incrementMessageHeardRepeats returns 0 for an unknown id`() = runTest {
        assertEquals(0, store.incrementMessageHeardRepeats(UUID.randomUUID()))
    }

    @Test
    fun `findSentChannelMessage matches by exact channel, timestamp, and text`() = runTest {
        val sortDate = Instant.ofEpochSecond(5000)
        val message = directMessage(text = "north repeater check", sortDate = sortDate).copy(contactID = null, channelIndex = 2u)
        store.saveMessage(message)

        val found = store.findSentChannelMessage(radioID, 2u, sortDate.epochSecond.toUInt(), "north repeater check")

        assertEquals(message.id, found?.id)
    }

    @Test
    fun `findSentChannelMessage disambiguates messages sharing channel and timestamp by text`() = runTest {
        val sortDate = Instant.ofEpochSecond(5000)
        val a = directMessage(text = "message A", sortDate = sortDate).copy(contactID = null, channelIndex = 3u)
        val b = directMessage(text = "message B", sortDate = sortDate).copy(contactID = null, channelIndex = 3u)
        store.saveMessage(a)
        store.saveMessage(b)

        assertEquals(b.id, store.findSentChannelMessage(radioID, 3u, sortDate.epochSecond.toUInt(), "message B")?.id)
        assertEquals(a.id, store.findSentChannelMessage(radioID, 3u, sortDate.epochSecond.toUInt(), "message A")?.id)
    }

    @Test
    fun `findSentChannelMessage ignores incoming messages`() = runTest {
        val sortDate = Instant.ofEpochSecond(5000)
        val incoming = directMessage(text = "hello", sortDate = sortDate).copy(contactID = null, channelIndex = 1u, direction = MessageDirection.INCOMING)
        store.saveMessage(incoming)

        assertNull(store.findSentChannelMessage(radioID, 1u, sortDate.epochSecond.toUInt(), "hello"))
    }

    @Test
    fun `deleteMessagesForChannel removes only that channel's messages`() = runTest {
        val target = directMessage().copy(contactID = null, channelIndex = 2u)
        val other = directMessage().copy(contactID = null, channelIndex = 3u)
        store.saveMessage(target)
        store.saveMessage(other)

        store.deleteMessagesForChannel(radioID, 2u)

        assertTrue(store.fetchMessages(radioID, 2u).isEmpty())
        assertEquals(1, store.fetchMessages(radioID, 3u).size)
    }

    @Test
    fun `deleteMessagesForContact removes only that contact's messages`() = runTest {
        val otherContactID = UUID.randomUUID()
        val target = directMessage()
        val other = directMessage().copy(contactID = otherContactID)
        store.saveMessage(target)
        store.saveMessage(other)

        store.deleteMessagesForContact(contactID)

        assertTrue(store.fetchMessages(contactID).isEmpty())
        assertEquals(1, store.fetchMessages(otherContactID).size)
    }

    @Test
    fun `deleteChannelMessagesFromSender removes only that sender's channel messages, never a DM`() = runTest {
        val fromSender = directMessage().copy(contactID = null, channelIndex = 1u, senderNodeName = "Spammer")
        val otherSender = directMessage().copy(contactID = null, channelIndex = 1u, senderNodeName = "Legit")
        val dmWithSameSenderName = directMessage().copy(senderNodeName = "Spammer")
        store.saveMessage(fromSender)
        store.saveMessage(otherSender)
        store.saveMessage(dmWithSameSenderName)

        store.deleteChannelMessagesFromSender(radioID, "Spammer")

        val remainingChannelMessages = store.fetchMessages(radioID, 1u)
        assertEquals(1, remainingChannelMessages.size)
        assertEquals("Legit", remainingChannelMessages.first().senderNodeName)
        assertEquals("a DM must never be deleted by the channel-sender-name cascade", 1, store.fetchMessages(contactID).size)
    }

    @Test
    fun `markMentionSeen flips mentionSeen on the target row only`() = runTest {
        val mentioned = directMessage().copy(containsSelfMention = true, mentionSeen = false)
        val untouched = directMessage(id = UUID.randomUUID()).copy(containsSelfMention = true, mentionSeen = false)
        store.saveMessage(mentioned)
        store.saveMessage(untouched)

        store.markMentionSeen(mentioned.id)

        assertTrue(store.fetchMessage(mentioned.id)!!.mentionSeen)
        assertFalse(store.fetchMessage(untouched.id)!!.mentionSeen)
    }

    @Test
    fun `markMentionSeen is a no-op for an unknown id`() = runTest {
        store.markMentionSeen(UUID.randomUUID())
        // No exception is the assertion.
    }

    @Test
    fun `fetchUnseenMentionIDs returns only self-mentioned, unseen messages for a contact, oldest first`() = runTest {
        val base = Instant.ofEpochSecond(1000)
        val unseenOld = directMessage(sortDate = base).copy(containsSelfMention = true, mentionSeen = false)
        val unseenNew = directMessage(sortDate = base.plusSeconds(60)).copy(containsSelfMention = true, mentionSeen = false)
        val seen = directMessage(sortDate = base.plusSeconds(30)).copy(containsSelfMention = true, mentionSeen = true)
        val notAMention = directMessage(sortDate = base.plusSeconds(45)).copy(containsSelfMention = false)
        store.saveMessage(unseenNew)
        store.saveMessage(unseenOld)
        store.saveMessage(seen)
        store.saveMessage(notAMention)

        val ids = store.fetchUnseenMentionIDs(contactID)

        assertEquals(listOf(unseenOld.id, unseenNew.id), ids)
    }

    @Test
    fun `fetchUnseenChannelMentionIDs is scoped to radioID and channelIndex`() = runTest {
        val matching = directMessage().copy(contactID = null, channelIndex = 5u, containsSelfMention = true, mentionSeen = false)
        val otherChannel = directMessage().copy(contactID = null, channelIndex = 6u, containsSelfMention = true, mentionSeen = false)
        store.saveMessage(matching)
        store.saveMessage(otherChannel)

        val ids = store.fetchUnseenChannelMentionIDs(radioID, 5u)

        assertEquals(listOf(matching.id), ids)
    }

    @Test
    fun `existingMessageLookups keys an outgoing message on its own id`() = runTest {
        val message = directMessage()
        store.saveMessage(message)

        val (keys, idsByKey) = store.existingMessageLookups(setOf(radioID))

        val key = BackupDedupKeys.messageBackupKey(message)
        assertTrue(key.startsWith("out-"))
        assertEquals(setOf(key), keys)
        assertEquals(listOf(message.id), idsByKey[key])
    }

    @Test
    fun `existingMessageLookups is empty for radioIDs with no messages`() = runTest {
        val (keys, idsByKey) = store.existingMessageLookups(emptySet())
        assertTrue(keys.isEmpty())
        assertTrue(idsByKey.isEmpty())
    }

    @Test
    fun `batchInsertMessages inserts a message with no existing key match`() = runTest {
        val dto = directMessage(text = "new")

        val result = store.batchInsertMessages(listOf(dto), emptySet(), emptyMap())

        assertEquals(1, result.counts.inserted)
        assertEquals("new", store.fetchMessage(dto.id)?.text)
    }

    @Test
    fun `batchInsertMessages skips a duplicate incoming message and rewrites replyToID onto the winning local parent`() = runTest {
        // Outgoing messages key on their own id, so only an incoming (content/dedup-key-based)
        // message can collide under a *different* id, the scenario replyToID-remap exists for.
        val localParent = directMessage(id = UUID.randomUUID()).copy(direction = MessageDirection.INCOMING, deduplicationKey = "dm-shared-key")
        store.saveMessage(localParent)
        val (existingKeys, existingIdsByKey) = store.existingMessageLookups(setOf(radioID))

        val backupDuplicateParent = directMessage(id = UUID.randomUUID()).copy(direction = MessageDirection.INCOMING, deduplicationKey = "dm-shared-key")
        val reply = directMessage(id = UUID.randomUUID()).copy(replyToID = backupDuplicateParent.id)

        val result = store.batchInsertMessages(listOf(backupDuplicateParent, reply), existingKeys, existingIdsByKey)

        assertEquals(1, result.counts.inserted)
        assertEquals(1, result.counts.skipped)
        assertEquals(localParent.id, result.messageIdByBackupId[backupDuplicateParent.id])
        assertEquals(localParent.id, store.fetchMessage(reply.id)?.replyToID)
    }

    @Test
    fun `recomputeMessageCaches refreshes heardRepeats and reactionSummary from the repeat and reaction tables`() = runTest {
        val message = directMessage()
        store.saveMessage(message)
        val repeatStore = MessageRepeatStore(database)
        repeatStore.saveMessageRepeat(
            MessageRepeatDto(id = UUID.randomUUID(), messageID = message.id, receivedAt = Instant.now(), pathNodes = ByteArray(1), pathLength = 1u, snr = null, rssi = null, rxLogEntryID = null),
        )
        val reactionStore = ReactionStore(database)
        reactionStore.saveReaction(
            ReactionDto(id = UUID.randomUUID(), messageID = message.id, emoji = "👍", senderName = "Alice", messageHash = "abc", rawText = "x", receivedAt = Instant.now(), channelIndex = null, contactID = null, radioID = radioID),
        )

        store.recomputeMessageCaches(setOf(message.id))

        val updated = store.fetchMessage(message.id)!!
        assertEquals(1, updated.heardRepeats)
        assertEquals("👍:1", updated.reactionSummary)
    }

    @Test
    fun `recomputeMessageCaches clears reactionSummary and zeroes heardRepeats when no child rows exist`() = runTest {
        val message = directMessage().copy(heardRepeats = 3, reactionSummary = "👍:1")
        store.saveMessage(message)

        store.recomputeMessageCaches(setOf(message.id))

        val updated = store.fetchMessage(message.id)!!
        assertEquals(0, updated.heardRepeats)
        assertNull(updated.reactionSummary)
    }

    // MARK: - Cascading deletes

    private suspend fun addChildren(messageID: UUID, senderName: String = "Bob") {
        database.reactionDao().insert(
            ReactionEntity(
                id = UUID.randomUUID(), messageID = messageID, emoji = "👍", senderName = senderName, messageHash = "ABCDEFGH",
                rawText = "👍", receivedAt = Instant.now(), channelIndex = null, contactID = null, radioID = radioID,
            ),
        )
        database.messageRepeatDao().insert(
            MessageRepeatEntity(
                id = UUID.randomUUID(), messageID = messageID, receivedAt = Instant.now(), pathNodes = byteArrayOf(1),
                pathLength = 1, snr = null, rssi = null, rxLogEntryID = null,
            ),
        )
    }

    private suspend fun childCount(messageID: UUID) =
        database.reactionDao().fetchForMessages(listOf(messageID)).size + database.messageRepeatDao().fetchForMessages(listOf(messageID)).size

    @Test
    fun `deleteMessagesForChannel cascades reactions and repeats but leaves other channels alone`() = runTest {
        val doomed = directMessage().copy(contactID = null, channelIndex = 2u)
        val kept = directMessage().copy(contactID = null, channelIndex = 3u)
        store.saveMessage(doomed)
        store.saveMessage(kept)
        addChildren(doomed.id)
        addChildren(kept.id)

        store.deleteMessagesForChannel(radioID, 2u)

        assertNull(store.fetchMessage(doomed.id))
        assertEquals(0, childCount(doomed.id))
        assertEquals(2, childCount(kept.id))
    }

    @Test
    fun `deleteMessagesForContact cascades reactions and repeats`() = runTest {
        val message = directMessage()
        store.saveMessage(message)
        addChildren(message.id)

        store.deleteMessagesForContact(contactID)

        assertNull(store.fetchMessage(message.id))
        assertEquals(0, childCount(message.id))
    }

    @Test
    fun `deleteChannelMessagesFromSender cascades and only touches that sender's channel messages`() = runTest {
        val bobs = directMessage().copy(contactID = null, channelIndex = 1u, senderNodeName = "Bob")
        val alices = directMessage().copy(contactID = null, channelIndex = 1u, senderNodeName = "Alice")
        store.saveMessage(bobs)
        store.saveMessage(alices)
        addChildren(bobs.id)
        addChildren(alices.id)

        store.deleteChannelMessagesFromSender(radioID, "Bob")

        assertNull(store.fetchMessage(bobs.id))
        assertEquals(0, childCount(bobs.id))
        assertEquals("Alice", store.fetchMessage(alices.id)?.senderNodeName)
        assertEquals(2, childCount(alices.id))
    }

    @Test
    fun `batchInsertMessages skips a backup row whose id is already claimed under a different key`() = runTest {
        val local = directMessage(text = "local")
        store.saveMessage(local)
        val (keys, idsByKey) = store.existingMessageLookups(setOf(radioID))
        val clash = local.copy(text = "different content so a different dedup key", deduplicationKey = null)
        val duplicateOfClash = clash.copy(id = UUID.randomUUID())

        val result = store.batchInsertMessages(listOf(clash, duplicateOfClash, duplicateOfClash), keys, idsByKey)

        assertEquals(1, result.counts.inserted) // only the fresh id
        assertEquals(2, result.counts.skipped)
        assertEquals("local", store.fetchMessage(local.id)?.text)
    }
}
