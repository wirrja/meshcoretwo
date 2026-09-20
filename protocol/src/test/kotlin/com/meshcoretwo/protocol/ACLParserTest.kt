// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** No direct Swift unit test file for ACLParser (only exercised via Session integration tests). */
class ACLParserTest {
    @Test
    fun `parses entries and skips all-zero (null) entries`() {
        var data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06) + 0x07 // real entry
        data += ByteArray(7) // null entry: all zeros, should be skipped
        data += byteArrayOf(0x11, 0x12, 0x13, 0x14, 0x15, 0x16) + 0x02 // another real entry

        val entries = ACLParser.parse(data)

        assertEquals(2, entries.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06), entries[0].keyPrefix)
        assertEquals(0x07u.toUByte(), entries[0].permissions)
        assertArrayEquals(byteArrayOf(0x11, 0x12, 0x13, 0x14, 0x15, 0x16), entries[1].keyPrefix)
        assertEquals(0x02u.toUByte(), entries[1].permissions)
    }

    @Test
    fun `stops at a trailing partial entry`() {
        var data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06) + 0x07
        data += byteArrayOf(0x01, 0x02, 0x03) // only 3 bytes, need 7

        val entries = ACLParser.parse(data)
        assertEquals(1, entries.size)
    }

    @Test
    fun `empty input yields no entries`() {
        assertEquals(0, ACLParser.parse(ByteArray(0)).size)
    }
}
