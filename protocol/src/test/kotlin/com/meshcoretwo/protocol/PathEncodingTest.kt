// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PathEncodingTest {
    // MARK: - Mode 0 (1-byte hashes)

    @Test
    fun `mode 0 with 5 hops`() {
        // 0b00_000101 = mode 0, 5 hops
        val result = decodePathLen(0b0000_0101u)
        assertEquals(1, result?.hashSize)
        assertEquals(5, result?.hopCount)
        assertEquals(5, result?.byteLength)
    }

    @Test
    fun `mode 0 with 0 hops`() {
        val result = decodePathLen(0x00u)
        assertEquals(1, result?.hashSize)
        assertEquals(0, result?.hopCount)
        assertEquals(0, result?.byteLength)
    }

    @Test
    fun `mode 0 with max hops (63)`() {
        // 0b00_111111 = mode 0, 63 hops
        val result = decodePathLen(0b0011_1111u)
        assertEquals(1, result?.hashSize)
        assertEquals(63, result?.hopCount)
        assertEquals(63, result?.byteLength)
    }

    // MARK: - Mode 1 (2-byte hashes)

    @Test
    fun `mode 1 with 3 hops`() {
        // 0b01_000011 = mode 1, 3 hops
        val result = decodePathLen(0b0100_0011u)
        assertEquals(2, result?.hashSize)
        assertEquals(3, result?.hopCount)
        assertEquals(6, result?.byteLength)
    }

    @Test
    fun `mode 1 with max hops (63)`() {
        val result = decodePathLen(0x7Fu)
        assertEquals(2, result?.hashSize)
        assertEquals(63, result?.hopCount)
        assertEquals(126, result?.byteLength)
    }

    // MARK: - Mode 2 (3-byte hashes)

    @Test
    fun `mode 2 with 4 hops`() {
        // 0b10_000100 = mode 2, 4 hops
        val result = decodePathLen(0b1000_0100u)
        assertEquals(3, result?.hashSize)
        assertEquals(4, result?.hopCount)
        assertEquals(12, result?.byteLength)
    }

    @Test
    fun `mode 2 with 0 hops`() {
        val result = decodePathLen(0x80u)
        assertEquals(3, result?.hashSize)
        assertEquals(0, result?.hopCount)
        assertEquals(0, result?.byteLength)
    }

    @Test
    fun `mode 2 with max hops (63)`() {
        val result = decodePathLen(0xBFu)
        assertEquals(3, result?.hashSize)
        assertEquals(63, result?.hopCount)
        assertEquals(189, result?.byteLength)
    }

    // MARK: - Mode 3 (reserved)

    @Test
    fun `mode 3 returns null`() {
        // 0b11_000001 = mode 3, 1 hop -> reserved
        assertNull(decodePathLen(0b1100_0001u))
    }

    @Test
    fun `mode 3 with zero hops returns null`() {
        assertNull(decodePathLen(0xC0u))
    }

    // MARK: - Flood Sentinel

    @Test
    fun `0xFF flood sentinel returns null (mode 3)`() {
        assertNull(decodePathLen(0xFFu))
    }

    // MARK: - Encode

    @Test
    fun `encode mode 0`() {
        assertEquals(0x05u.toUByte(), encodePathLen(hashSize = 1, hopCount = 5))
    }

    @Test
    fun `encode mode 1`() {
        assertEquals(0x4Au.toUByte(), encodePathLen(hashSize = 2, hopCount = 10))
    }

    @Test
    fun `encode mode 2 max hops`() {
        assertEquals(0xBFu.toUByte(), encodePathLen(hashSize = 3, hopCount = 63))
    }

    @Test
    fun `encode clamps hop count to 63`() {
        assertEquals(63u.toUByte(), encodePathLen(hashSize = 1, hopCount = 100))
    }

    @Test
    fun `encode-decode round-trip`() {
        for (hashSize in 1..3) {
            for (hopCount in listOf(0, 1, 31, 63)) {
                val encoded = encodePathLen(hashSize = hashSize, hopCount = hopCount)
                val decoded = decodePathLen(encoded)
                assertEquals(hashSize, decoded?.hashSize)
                assertEquals(hopCount, decoded?.hopCount)
            }
        }
    }

    // MARK: - Input Validation

    @Test
    fun `encode accepts all valid hash sizes`() {
        for (hashSize in listOf(1, 2, 3)) {
            val decoded = decodePathLen(encodePathLen(hashSize = hashSize, hopCount = 1))
            assertEquals(hashSize, decoded?.hashSize)
        }
    }

    @Test
    fun `encode rejects hash size out of range`() {
        assertThrows(IllegalArgumentException::class.java) { encodePathLen(hashSize = 0, hopCount = 1) }
        assertThrows(IllegalArgumentException::class.java) { encodePathLen(hashSize = 4, hopCount = 1) }
    }
}
