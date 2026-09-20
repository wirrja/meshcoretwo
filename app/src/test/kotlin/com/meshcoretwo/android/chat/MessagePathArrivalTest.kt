// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.services.persistence.MessageRepeatDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class MessagePathArrivalTest {
    private fun repeat(pathLength: UByte, vararg nodes: Int) = MessageRepeatDto(
        id = UUID.randomUUID(), messageID = UUID.randomUUID(), receivedAt = Instant.EPOCH,
        pathNodes = ByteArray(nodes.size) { nodes[it].toByte() }, pathLength = pathLength, snr = null, rssi = null, rxLogEntryID = null,
    )

    @Test
    fun `offsets format compactly and never go negative`() {
        assertEquals("+0s", formatArrivalOffset(Duration.ofMillis(900)))
        assertEquals("+3s", formatArrivalOffset(Duration.ofSeconds(3)))
        assertEquals("+1m 05s", formatArrivalOffset(Duration.ofSeconds(65)))
        assertEquals("+2h 03m", formatArrivalOffset(Duration.ofMinutes(123)))
        assertEquals("+0s", formatArrivalOffset(Duration.ofSeconds(-4)))
    }

    @Test
    fun `hops split by the hash size in the length byte`() {
        assertEquals(listOf("11", "22"), repeat(2u, 0x11, 0x22).pathHops.map { it.hex })
        // 0x42 = hash mode 1 (2-byte hashes) with 2 hops.
        assertEquals(listOf("1122", "33AB"), repeat(0x42u, 0x11, 0x22, 0x33, 0xAB).pathHops.map { it.hex })
    }

    @Test
    fun `a zero-hop arrival has no hops and a zero count`() {
        val direct = repeat(0u)

        assertTrue(direct.pathHops.isEmpty())
        assertEquals(0, direct.arrivalHopCount)
    }

    @Test
    fun `hop count comes from the length byte's lower six bits`() {
        assertEquals(2, repeat(0x42u, 0x11, 0x22, 0x33, 0xAB).arrivalHopCount)
    }
}
