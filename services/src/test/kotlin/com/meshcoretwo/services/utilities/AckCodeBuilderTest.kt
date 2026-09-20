// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import com.meshcoretwo.protocol.toLittleEndianBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Port of AckCodeBuilderTests.swift. */
class AckCodeBuilderTest {
    @Test
    fun `matches firmware formula for known fixture`() {
        val timestamp = 0x6624AABBu
        val attempt: UByte = 2u
        val text = "hello"
        val pubkey = ByteArray(32) { 0xAA.toByte() }

        val code = AckCodeBuilder.expectedAck(timestamp, attempt, text, pubkey)

        val input = timestamp.toLittleEndianBytes() +
            (attempt.toInt() and 0x03).toByte() +
            text.toByteArray(Charsets.UTF_8) +
            pubkey
        val expected = MessageDigest.getInstance("SHA-256").digest(input).copyOf(4)

        assertArrayEquals(expected, code)
    }

    @Test
    fun `different texts produce different codes`() {
        val pubkey = ByteArray(32) { 0x01 }
        val a = AckCodeBuilder.expectedAck(1u, 0u, "hi", pubkey)
        val b = AckCodeBuilder.expectedAck(1u, 0u, "bye", pubkey)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `attempts 0 through 3 produce four distinct codes`() {
        val pubkey = ByteArray(32) { 0x02 }
        val codes = (0..3).map { AckCodeBuilder.expectedAck(100u, it.toUByte(), "hi", pubkey) }
        val distinct = codes.map { it.toList() }.toSet()
        assertEquals(4, distinct.size)
    }

    @Test
    fun `attempt 4 wraps to attempt 0's code`() {
        val pubkey = ByteArray(32) { 0x03 }
        val attempt0 = AckCodeBuilder.expectedAck(100u, 0u, "hi", pubkey)
        val attempt4 = AckCodeBuilder.expectedAck(100u, 4u, "hi", pubkey)
        assertTrue(attempt0.contentEquals(attempt4))
    }
}
