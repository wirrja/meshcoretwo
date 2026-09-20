// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import com.meshcoretwo.protocol.PayloadType
import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.services.messages.DeduplicationKey
import com.meshcoretwo.services.persistence.DecryptStatus
import com.meshcoretwo.services.persistence.RxLogDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Ported from `ChannelRXCorrelationTests.swift`. */
class ChannelRXCorrelationTest {
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    private fun entry(
        text: String? = "Alice: hello",
        at: Instant = t0,
        channelIndex: UByte? = 1u,
        senderTimestamp: UInt? = 500u,
        status: DecryptStatus = DecryptStatus.SUCCESS,
        payloadType: PayloadType = PayloadType.GROUP_TEXT,
    ) = RxLogDto(
        id = UUID.randomUUID(), radioID = UUID.randomUUID(), receivedAt = at, snr = null, rssi = null,
        routeType = RouteType.FLOOD, payloadType = payloadType, payloadVersion = 0u, pathLength = 0u,
        pathNodes = byteArrayOf(), packetPayload = byteArrayOf(), rawPayload = byteArrayOf(), packetHash = "h",
        channelIndex = channelIndex, channelName = null, decryptStatus = status, senderTimestamp = senderTimestamp,
        decodedText = text,
    )

    private val key = DeduplicationKey.contentBased(null, 1u, "Alice", 500u, "hello")

    @Test
    fun `null key matches nothing`() {
        assertTrue(ChannelRXCorrelation.matching(listOf(entry()), null).isEmpty())
    }

    @Test
    fun `matches on sender and body and orders oldest first`() {
        val late = entry(at = t0.plusSeconds(5))
        val early = entry(at = t0)

        assertEquals(listOf(early, late), ChannelRXCorrelation.matching(listOf(late, early), key))
    }

    @Test
    fun `rejects other bodies, senders, and undecrypted or non-group rows`() {
        val rows = listOf(
            entry(text = "Alice: bye"),
            entry(text = "Bob: hello"),
            entry(status = DecryptStatus.HMAC_FAILED),
            entry(payloadType = PayloadType.TEXT_MESSAGE),
            entry(text = null),
            entry(channelIndex = null),
            entry(senderTimestamp = null),
            entry(text = "no colon here"),
        )

        assertTrue(ChannelRXCorrelation.matching(rows, key).isEmpty())
    }

    @Test
    fun `sender whitespace around the name does not break the join`() {
        assertEquals(1, ChannelRXCorrelation.matching(listOf(entry(text = "Alice : hello")), key).size)
    }
}
