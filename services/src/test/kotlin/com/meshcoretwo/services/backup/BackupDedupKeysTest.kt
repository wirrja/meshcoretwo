// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackupDedupKeysTest {
    private val radioID = UUID.randomUUID()

    @Test
    fun `contactKey and channelKey are stable and distinct per input`() {
        val keyA = BackupDedupKeys.contactKey(radioID, byteArrayOf(1, 2, 3))
        val keyB = BackupDedupKeys.contactKey(radioID, byteArrayOf(1, 2, 3))
        val keyC = BackupDedupKeys.contactKey(radioID, byteArrayOf(4, 5, 6))

        assertEquals(keyA, keyB)
        assertNotEquals(keyA, keyC)
        assertEquals("$radioID-2", BackupDedupKeys.channelKey(radioID, 2u))
    }

    @Test
    fun `messageBackupKey keys an outgoing message on its own id, not content`() {
        val message = messageDto(id = UUID.randomUUID(), direction = MessageDirection.OUTGOING, text = "hi", deduplicationKey = null)
        val other = messageDto(id = UUID.randomUUID(), direction = MessageDirection.OUTGOING, text = "hi", deduplicationKey = null)

        assertNotEquals(BackupDedupKeys.messageBackupKey(message), BackupDedupKeys.messageBackupKey(other))
        assertEquals("out-${message.id}", BackupDedupKeys.messageBackupKey(message))
    }

    @Test
    fun `messageBackupKey prefers an incoming message's stored dedup key, scoped by radioID`() {
        val message = messageDto(direction = MessageDirection.INCOMING, deduplicationKey = "dm-abc-1-DEADBEEF")

        assertEquals("$radioID-dm-abc-1-DEADBEEF", BackupDedupKeys.messageBackupKey(message))
    }

    @Test
    fun `messageBackupKey derives a content-based key for an incoming message with no stored key`() {
        val message = messageDto(direction = MessageDirection.INCOMING, deduplicationKey = null, channelIndex = null, contactID = UUID.randomUUID())

        val key = BackupDedupKeys.messageBackupKey(message)
        assertEquals(key, BackupDedupKeys.messageBackupKey(message))
        assertEquals(true, key.startsWith("$radioID-dm-"))
    }

    @Test
    fun `rewriteDMDeduplicationKey rewrites only the matching contact-uuid prefix`() {
        val backupID = UUID.randomUUID()
        val localID = UUID.randomUUID()
        val key = "dm-$backupID-1234-ABCDEF01"

        assertEquals("dm-$localID-1234-ABCDEF01", BackupDedupKeys.rewriteDMDeduplicationKey(key, backupID, localID))
        assertEquals("out-someid", BackupDedupKeys.rewriteDMDeduplicationKey("out-someid", backupID, localID))
    }

    @Test
    fun `rewriteChannelDeduplicationKey rewrites only the matching channel-index prefix`() {
        val key = "ch-3-1234-Alice-ABCDEF01"

        assertEquals("ch-7-1234-Alice-ABCDEF01", BackupDedupKeys.rewriteChannelDeduplicationKey(key, 3u, 7u))
        assertEquals("ch-3-1234-Alice-ABCDEF01", BackupDedupKeys.rewriteChannelDeduplicationKey(key, 9u, 7u))
    }

    @Test
    fun `nodeStatusSnapshotKey coalesces two timestamps in the same millisecond`() {
        val publicKey = byteArrayOf(9, 9, 9)
        val instant = Instant.ofEpochMilli(1_700_000_000_123)

        assertEquals(
            BackupDedupKeys.nodeStatusSnapshotKey(publicKey, instant),
            BackupDedupKeys.nodeStatusSnapshotKey(publicKey, instant),
        )
        assertNotEquals(
            BackupDedupKeys.nodeStatusSnapshotKey(publicKey, instant),
            BackupDedupKeys.nodeStatusSnapshotKey(publicKey, instant.plusMillis(1)),
        )
    }

    private fun messageDto(
        id: UUID = UUID.randomUUID(),
        direction: MessageDirection,
        text: String = "hi",
        deduplicationKey: String?,
        channelIndex: UByte? = null,
        contactID: UUID? = UUID.randomUUID(),
    ) = MessageDto(
        id = id,
        radioID = radioID,
        contactID = contactID,
        channelIndex = channelIndex,
        text = text,
        timestamp = 1234u,
        createdAt = Instant.now(),
        sortDate = Instant.now(),
        direction = direction,
        status = MessageStatus.DELIVERED,
        textType = TextType.PLAIN_TEXT,
        ackCode = null,
        pathLength = 0u,
        snr = null,
        pathNodes = null,
        senderKeyPrefix = null,
        senderNodeName = null,
        isRead = true,
        replyToID = null,
        roundTripTime = null,
        sendCount = 1,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        deduplicationKey = deduplicationKey,
        reactionSummary = null,
        senderTimestamp = null,
        routeType = null,
        heardRepeats = 0,
    )
}
