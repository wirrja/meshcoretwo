// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class MessageActionAvailabilityTest {
    private fun message(
        direction: MessageDirection = MessageDirection.INCOMING,
        channelIndex: UByte? = null,
        senderNodeName: String? = null,
        heardRepeats: Int = 0,
        pathLength: UByte = PacketBuilder.FLOOD_PATH_SENTINEL,
        pathNodes: ByteArray? = null,
        routeType: RouteType? = null,
    ) = MessageDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        contactID = if (channelIndex == null) UUID.randomUUID() else null,
        channelIndex = channelIndex,
        text = "hi",
        timestamp = 1_000u,
        createdAt = Instant.ofEpochSecond(1_000),
        sortDate = Instant.ofEpochSecond(1_000),
        direction = direction,
        status = MessageStatus.SENT,
        textType = TextType.PLAIN_TEXT,
        ackCode = null,
        pathLength = pathLength,
        snr = null,
        pathNodes = pathNodes,
        senderKeyPrefix = null,
        senderNodeName = senderNodeName,
        isRead = true,
        replyToID = null,
        roundTripTime = null,
        sendCount = 1,
        retryAttempt = 0,
        maxRetryAttempts = 3,
        deduplicationKey = null,
        reactionSummary = null,
        senderTimestamp = null,
        routeType = routeType,
        heardRepeats = heardRepeats,
    )

    @Test
    fun `an incoming DM allows reply, copy, and delete only`() {
        val availability = MessageActionAvailability(message(direction = MessageDirection.INCOMING))

        assertEquals(
            MessageActionAvailability(
                canReply = true,
                canCopy = true,
                canSendAgain = false,
                canBlockSender = false,
                canSendDM = false,
                canShowRepeatDetails = false,
                canViewPath = false,
                canDelete = true,
            ),
            availability,
        )
    }

    @Test
    fun `an outgoing DM allows send-again instead of reply`() {
        val availability = MessageActionAvailability(message(direction = MessageDirection.OUTGOING))

        assertEquals(false, availability.canReply)
        assertEquals(true, availability.canSendAgain)
    }

    @Test
    fun `an incoming channel message with a known sender allows block-sender and send-DM`() {
        val availability = MessageActionAvailability(message(channelIndex = 0u, senderNodeName = "Alice"))

        assertEquals(true, availability.canBlockSender)
        assertEquals(true, availability.canSendDM)
    }

    @Test
    fun `an incoming channel message without a resolved sender name disallows block-sender and send-DM`() {
        val availability = MessageActionAvailability(message(channelIndex = 0u, senderNodeName = null))

        assertEquals(false, availability.canBlockSender)
        assertEquals(false, availability.canSendDM)
    }

    @Test
    fun `an outgoing channel message never allows block-sender or send-DM, even with a sender name`() {
        val availability = MessageActionAvailability(message(direction = MessageDirection.OUTGOING, channelIndex = 0u, senderNodeName = "Me"))

        assertEquals(false, availability.canBlockSender)
        assertEquals(false, availability.canSendDM)
    }

    @Test
    fun `repeat details require an outgoing message with at least one heard repeat`() {
        assertEquals(false, MessageActionAvailability(message(direction = MessageDirection.OUTGOING, heardRepeats = 0)).canShowRepeatDetails)
        assertEquals(true, MessageActionAvailability(message(direction = MessageDirection.OUTGOING, heardRepeats = 1)).canShowRepeatDetails)
        assertEquals(false, MessageActionAvailability(message(direction = MessageDirection.INCOMING, heardRepeats = 1)).canShowRepeatDetails)
    }

    @Test
    fun `view path requires incoming, flood-routed, and a non-empty recorded path`() {
        // pathLength at the flood sentinel means no path was ever recorded (direct-routed, or
        // simply never accumulated one) — not flood-routed, per MessageDto.isFloodRouted's
        // pathLength-inference branch, even with pathNodes somehow present.
        assertEquals(
            false,
            MessageActionAvailability(message(pathLength = PacketBuilder.FLOOD_PATH_SENTINEL, pathNodes = byteArrayOf(1))).canViewPath,
        )
        // A decoded (non-sentinel) pathLength means the message picked up a real hop-by-hop path
        // while being flood-repeated — flood-routed — but with no path bytes recorded yet.
        assertEquals(
            false,
            MessageActionAvailability(message(pathLength = 1u, pathNodes = null)).canViewPath,
        )
        // Flood-routed with a recorded path.
        assertEquals(
            true,
            MessageActionAvailability(message(pathLength = 1u, pathNodes = byteArrayOf(1))).canViewPath,
        )
        // Outgoing is never eligible, even otherwise-qualifying.
        assertEquals(
            false,
            MessageActionAvailability(message(direction = MessageDirection.OUTGOING, pathLength = 1u, pathNodes = byteArrayOf(1))).canViewPath,
        )
    }

    @Test
    fun `a channel message is always flood-routed regardless of pathLength`() {
        assertEquals(
            true,
            MessageActionAvailability(message(channelIndex = 0u, pathLength = PacketBuilder.FLOOD_PATH_SENTINEL, pathNodes = byteArrayOf(1))).canViewPath,
        )
    }

    @Test
    fun `an incoming message with extra flood paths shows path detail but not repeat details`() {
        val availability = MessageActionAvailability(message(channelIndex = 0u, heardRepeats = 2, pathNodes = byteArrayOf(1)))

        assertEquals(true, availability.showsPathDetail)
        assertEquals(false, availability.canShowRepeatDetails)
    }

    @Test
    fun `an incoming message without a known path but with extras still shows path detail`() {
        assertEquals(true, MessageActionAvailability(message(channelIndex = 0u, heardRepeats = 1, pathNodes = null)).showsPathDetail)
    }

    @Test
    fun `an incoming message with a single arrival and no path shows no path detail`() {
        assertEquals(false, MessageActionAvailability(message(channelIndex = 0u, heardRepeats = 0, pathNodes = null)).showsPathDetail)
    }

    @Test
    fun `an outgoing message with heard repeats shows path detail`() {
        assertEquals(true, MessageActionAvailability(message(direction = MessageDirection.OUTGOING, heardRepeats = 1)).showsPathDetail)
    }

    @Test
    fun `arrival count adds the message's own arrival only for incoming`() {
        assertEquals(3, arrivalCount(message(heardRepeats = 2)))
        assertEquals(2, arrivalCount(message(direction = MessageDirection.OUTGOING, heardRepeats = 2)))
    }
}
