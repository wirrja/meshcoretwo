// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.protocol.TextType
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun message(pathLength: UByte, pathNodes: ByteArray?, routeType: RouteType? = null, channelIndex: UByte? = null) = MessageDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    contactID = if (channelIndex == null) UUID.randomUUID() else null,
    channelIndex = channelIndex,
    text = "hi",
    timestamp = 1_000u,
    createdAt = Instant.ofEpochSecond(1_000),
    sortDate = Instant.ofEpochSecond(1_000),
    direction = MessageDirection.INCOMING,
    status = MessageStatus.SENT,
    textType = TextType.PLAIN_TEXT,
    ackCode = null,
    pathLength = pathLength,
    snr = null,
    pathNodes = pathNodes,
    senderKeyPrefix = null,
    senderNodeName = null,
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
    heardRepeats = 0,
)

class MessageDtoPathTest {
    @Test
    fun `pathHops chunks single-byte hashes`() {
        // pathLength 0x02 = mode 0 (hashSize 1) with hopCount 2.
        val hops = message(pathLength = 2u, pathNodes = byteArrayOf(0xA3.toByte(), 0x7F)).pathHops

        assertEquals(listOf("A3", "7F"), hops.map { it.hex })
    }

    @Test
    fun `pathHops chunks multi-byte hashes`() {
        // pathLength 0x42 = mode 1 (hashSize 2) with hopCount 2.
        val hops = message(pathLength = 0x42u, pathNodes = byteArrayOf(0xA3.toByte(), 0x7F, 0x11, 0x22)).pathHops

        assertEquals(listOf("A37F", "1122"), hops.map { it.hex })
    }

    @Test
    fun `pathHops is empty when pathNodes is null`() {
        assertTrue(message(pathLength = 1u, pathNodes = null).pathHops.isEmpty())
    }

    @Test
    fun `pathHops truncates to hashSize times hopCount, ignoring trailing bytes`() {
        // pathLength 0x01 = mode 0 (hashSize 1) with hopCount 1 — only the first byte is a hop.
        val hops = message(pathLength = 1u, pathNodes = byteArrayOf(0xA3.toByte(), 0x7F, 0x11)).pathHops

        assertEquals(listOf("A3"), hops.map { it.hex })
    }

    @Test
    fun `isDirectRouted is the inverse of isFloodRouted`() {
        // The sentinel pathLength means no path was ever recorded, inferred as direct-routed —
        // same pathLength-inference branch MessageActionAvailabilityTest documents for isFloodRouted.
        val direct = message(pathLength = PacketBuilder.FLOOD_PATH_SENTINEL, pathNodes = null)
        val flooded = message(pathLength = 2u, pathNodes = byteArrayOf(1, 2))

        assertEquals(true, direct.isDirectRouted)
        assertEquals(false, flooded.isDirectRouted)
    }

    @Test
    fun `pathHashSizeIfKnown decodes the hash mode and is null for the no-path marker`() {
        assertEquals(1, message(pathLength = 2u, pathNodes = null).pathHashSizeIfKnown)
        // 0x42 = mode 1 (2-byte hashes), 2 hops.
        assertEquals(2, message(pathLength = 0x42u, pathNodes = null).pathHashSizeIfKnown)
        assertEquals(null, message(pathLength = PacketBuilder.FLOOD_PATH_SENTINEL, pathNodes = null).pathHashSizeIfKnown)
    }

    @Test
    fun `timestampCorrected is derived from a differing senderTimestamp on incoming messages`() {
        val plain = message(pathLength = 1u, pathNodes = null)
        assertEquals(false, plain.timestampCorrected)
        assertEquals(Instant.ofEpochSecond(1_000), plain.wireSentInstant)

        val corrected = plain.copy(senderTimestamp = 400u)
        assertTrue(corrected.timestampCorrected)
        assertEquals(Instant.ofEpochSecond(400), corrected.wireSentInstant)

        assertEquals(false, plain.copy(senderTimestamp = 1_000u).timestampCorrected)
        assertEquals(false, corrected.copy(direction = MessageDirection.OUTGOING).timestampCorrected)
    }
}
