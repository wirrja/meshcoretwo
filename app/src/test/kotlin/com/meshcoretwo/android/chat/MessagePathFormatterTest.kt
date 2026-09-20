// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class MessagePathFormatterTest {
    private fun channelMessage(hops: Int, pathNodes: ByteArray?) = MessageDto(
        id = UUID.randomUUID(), radioID = UUID.randomUUID(), contactID = null, channelIndex = 0u,
        text = "hi", timestamp = 1_000u, createdAt = Instant.ofEpochSecond(1_000), sortDate = Instant.ofEpochSecond(1_000),
        direction = MessageDirection.INCOMING, status = MessageStatus.SENT, textType = TextType.PLAIN_TEXT,
        ackCode = null, pathLength = hops.toUByte(), snr = null, pathNodes = pathNodes, senderKeyPrefix = null,
        senderNodeName = null, isRead = true, replyToID = null, roundTripTime = null, sendCount = 1, retryAttempt = 0,
        maxRetryAttempts = 3, deduplicationKey = null, reactionSummary = null, senderTimestamp = null, routeType = null,
        heardRepeats = 0,
    )

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `short path lists every hop`() {
        assertEquals("A3,7F,42", MessagePathFormatter.format(channelMessage(3, bytes(0xA3, 0x7F, 0x42))))
    }

    @Test
    fun `path at the cap is not collapsed`() {
        assertEquals("01,02,03,04", MessagePathFormatter.format(channelMessage(4, bytes(1, 2, 3, 4))))
    }

    @Test
    fun `long path collapses its middle`() {
        assertEquals("01,02…05,06", MessagePathFormatter.format(channelMessage(6, bytes(1, 2, 3, 4, 5, 6))))
    }

    @Test
    fun `flood message without recorded hops reads Flood`() {
        assertEquals("Flood", MessagePathFormatter.format(channelMessage(0, null)))
    }

    @Test
    fun `a DM with the no-path sentinel reads Direct`() {
        val dm = channelMessage(0, null).copy(channelIndex = null, contactID = UUID.randomUUID(), pathLength = PacketBuilder.FLOOD_PATH_SENTINEL)
        assertEquals("Direct", MessagePathFormatter.format(dm))
    }

    @Test
    fun `a lone 0xFF destination marker reads Direct`() {
        assertEquals("Direct", MessagePathFormatter.format(channelMessage(1, bytes(0xFF))))
    }
}
