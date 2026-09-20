// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** No direct Swift unit test file for NeighboursParser (only exercised via Session integration tests). */
class NeighboursParserTest {
    @Test
    fun `parses total and results counts with entries`() {
        val pubkeyPrefix = byteArrayOf(0x01, 0x02)
        val tag = byteArrayOf(0xAA.toByte())

        var data = 5.toShort().toLittleEndianBytes() // totalCount
        data += 2.toShort().toLittleEndianBytes() // resultsCount
        // entry 1: prefix(4) + secondsAgo(4) + snr(1)
        data += byteArrayOf(0x11, 0x22, 0x33, 0x44)
        data += 60.toLittleEndianBytes()
        data += 0x28 // snr = 10.0
        // entry 2
        data += byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte())
        data += 120.toLittleEndianBytes()
        data += 0x14 // snr = 5.0

        val result = NeighboursParser.parse(data, pubkeyPrefix, tag)

        assertArrayEquals(pubkeyPrefix, result.publicKeyPrefix)
        assertArrayEquals(tag, result.tag)
        assertEquals(5, result.totalCount)
        assertEquals(2, result.neighbours.size)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), result.neighbours[0].publicKeyPrefix)
        assertEquals(60, result.neighbours[0].secondsAgo)
        assertTrue(abs(result.neighbours[0].snr - 10.0) <= 0.001)
        assertEquals(120, result.neighbours[1].secondsAgo)
        assertTrue(abs(result.neighbours[1].snr - 5.0) <= 0.001)
    }

    @Test
    fun `short payload yields empty response`() {
        val result = NeighboursParser.parse(byteArrayOf(0x01, 0x02), byteArrayOf(), byteArrayOf())
        assertEquals(0, result.totalCount)
        assertTrue(result.neighbours.isEmpty())
    }

    @Test
    fun `stops at a truncated trailing entry`() {
        var data = 3.toShort().toLittleEndianBytes()
        data += 3.toShort().toLittleEndianBytes() // claims 3 results
        data += byteArrayOf(0x11, 0x22, 0x33, 0x44) // only one full entry follows
        data += 60.toLittleEndianBytes()
        data += 0x28

        val result = NeighboursParser.parse(data, byteArrayOf(), byteArrayOf())
        assertEquals(3, result.totalCount)
        assertEquals(1, result.neighbours.size)
    }
}
