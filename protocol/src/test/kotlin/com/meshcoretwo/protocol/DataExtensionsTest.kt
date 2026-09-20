// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataExtensionsTest {
    @Test
    fun `paddedOrTruncated pads short data`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val result = data.paddedOrTruncated(6)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x00, 0x00, 0x00), result)
    }

    @Test
    fun `paddedOrTruncated truncates long data`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val result = data.paddedOrTruncated(3)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), result)
    }

    @Test
    fun `paddedOrTruncated returns exact size unchanged`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val result = data.paddedOrTruncated(3)
        assertArrayEquals(data, result)
    }

    @Test
    fun `paddedOrTruncated returns empty for negative length`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val result = data.paddedOrTruncated(-1)
        assertArrayEquals(ByteArray(0), result)
    }

    @Test
    fun `utf8PaddedOrTruncated pads short string`() {
        val result = "Hi".utf8PaddedOrTruncated(6)
        assertArrayEquals(byteArrayOf(0x48, 0x69, 0x00, 0x00, 0x00, 0x00), result)
    }

    @Test
    fun `utf8PaddedOrTruncated truncates long string`() {
        val result = "Hello World".utf8PaddedOrTruncated(5)
        assertArrayEquals(byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F), result) // "Hello"
    }

    @Test
    fun `toLittleEndianBytes UInt32`() {
        val result = 0x1234_5678u.toLittleEndianBytes()
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), result)
    }

    @Test
    fun `toLittleEndianBytes Int32`() {
        val result = (-1).toLittleEndianBytes()
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), result)
    }

    // MARK: - utf8Prefix(maxBytes:)

    @Test
    fun `utf8Prefix ASCII unchanged when under limit`() {
        assertEquals("Hello", "Hello".utf8Prefix(10))
    }

    @Test
    fun `utf8Prefix ASCII truncated at exact limit`() {
        assertEquals("Hel", "Hello".utf8Prefix(3))
    }

    @Test
    fun `utf8Prefix CJK never splits three-byte characters`() {
        // Each CJK character is 3 UTF-8 bytes
        val cjk = "你好世界" // 12 bytes total
        val result = cjk.utf8Prefix(7) // room for 2 chars (6 bytes), not 3 (9 bytes)
        assertEquals("你好", result)
        assertEquals(6, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `utf8Prefix emoji never splits four-byte characters`() {
        // Each emoji is 4 UTF-8 bytes
        val emoji = "😀🎉🔥"
        val result = emoji.utf8Prefix(5) // room for 1 emoji (4 bytes), not 2 (8 bytes)
        assertEquals("😀", result)
        assertEquals(4, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `utf8Prefix exact boundary includes character`() {
        val cjk = "你好" // 6 bytes total
        assertEquals("你好", cjk.utf8Prefix(6))
    }

    @Test
    fun `utf8Prefix empty string returns empty`() {
        assertEquals("", "".utf8Prefix(10))
    }

    @Test
    fun `utf8Prefix zero bytes returns empty`() {
        assertEquals("", "Hello".utf8Prefix(0))
    }

    @Test
    fun `utf8Prefix negative bytes returns empty`() {
        assertEquals("", "Hello".utf8Prefix(-1))
    }

    @Test
    fun `utf8Prefix mixed ASCII and multibyte`() {
        val mixed = "Hi你" // 2 + 3 = 5 bytes
        val result = mixed.utf8Prefix(4) // room for "Hi" (2) but not "Hi你" (5)
        assertEquals("Hi", result)
    }

    // MARK: - utf8PaddedOrTruncated with multi-byte characters

    @Test
    fun `utf8PaddedOrTruncated does not split CJK characters`() {
        val cjk = "你好世界" // 12 bytes
        val result = cjk.utf8PaddedOrTruncated(8)
        // Should include "你好" (6 bytes) + 2 zero-padding bytes
        assertEquals(8, result.size)
        assertEquals(0x00, result[6].toInt())
        assertEquals(0x00, result[7].toInt())
        val textPortion = String(result.copyOfRange(0, 6), Charsets.UTF_8)
        assertEquals("你好", textPortion)
    }

    @Test
    fun `utf8PaddedOrTruncated does not split emoji`() {
        val emoji = "😀🎉" // 8 bytes
        val result = emoji.utf8PaddedOrTruncated(6)
        // Should include "😀" (4 bytes) + 2 zero-padding bytes
        assertEquals(6, result.size)
        val textPortion = String(result.copyOfRange(0, 4), Charsets.UTF_8)
        assertEquals("😀", textPortion)
    }

    // MARK: - hexString / decodeHex (not in the Swift suite above, but exercised elsewhere)

    @Test
    fun `hexString round-trips through decodeHex`() {
        val data = byteArrayOf(0x00, 0x0F, 0xFF.toByte(), 0xA5.toByte())
        assertEquals("000fffa5", data.hexString)
        assertArrayEquals(data, data.hexString.decodeHex())
    }

    @Test
    fun `decodeHex drops a trailing odd character like Data(hexString-)`() {
        val result = "abc".decodeHex() // 3 chars -> 1 pair read, trailing "c" dropped
        assertArrayEquals(byteArrayOf(0xAB.toByte()), result)
    }

    @Test
    fun `decodeHex returns null for invalid hex digit`() {
        assertNull("zz".decodeHex())
    }
}
