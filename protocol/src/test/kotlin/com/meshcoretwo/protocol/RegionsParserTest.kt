// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Port of the RegionsParser suite from RegionTests.swift. */
class RegionsParserTest {
    /** Builds a mock region response: [4-byte timestamp][UTF-8 string]. */
    private fun makeResponse(regionString: String, timestamp: UInt = 0x1234_5678u): ByteArray {
        var data = timestamp.toLittleEndianBytes()
        data += regionString.toByteArray(Charsets.UTF_8)
        return data
    }

    @Test
    fun `parses comma-separated regions`() {
        val result = RegionsParser.parse(makeResponse("Europe,UK,France"))
        assertEquals(listOf("Europe", "UK", "France"), result)
    }

    @Test
    fun `parses single region`() {
        val result = RegionsParser.parse(makeResponse("Europe"))
        assertEquals(listOf("Europe"), result)
    }

    @Test
    fun `parses empty string to empty array`() {
        val result = RegionsParser.parse(makeResponse(""))
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `strips null terminators`() {
        val nullChar = 0.toChar()
        val result = RegionsParser.parse(makeResponse("Europe,UK$nullChar$nullChar"))
        assertEquals(listOf("Europe", "UK"), result)
    }

    @Test
    fun `throws on response shorter than 4 bytes`() {
        assertThrows(RegionsParseException::class.java) {
            RegionsParser.parse(byteArrayOf(0x01, 0x02))
        }
    }

    @Test
    fun `throws on invalid UTF-8`() {
        var data = ByteArray(4) // timestamp
        data += byteArrayOf(0xFF.toByte(), 0xFE.toByte()) // invalid UTF-8
        assertThrows(RegionsParseException::class.java) {
            RegionsParser.parse(data)
        }
    }

    @Test
    fun `filters out wildcard region`() {
        val result = RegionsParser.parse(makeResponse("*,Europe,UK"))
        assertEquals(listOf("Europe", "UK"), result)
    }

    @Test
    fun `filters out wildcard-only response to empty array`() {
        val result = RegionsParser.parse(makeResponse("*"))
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `filters out whitespace-only entries`() {
        val result = RegionsParser.parse(makeResponse("Europe, ,UK"))
        assertEquals(listOf("Europe", "UK"), result)
    }

    @Test
    fun `trims whitespace around region names`() {
        val result = RegionsParser.parse(makeResponse(" Europe , UK "))
        assertEquals(listOf("Europe", "UK"), result)
    }
}
