// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of WiFiFrameCodecTests.swift. */
class WiFiFrameCodecTest {
    @Test
    fun `encodes frame with correct delimiter and length`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val encoded = WiFiFrameCodec.encode(payload)

        // Expected: '<' (0x3C) + length (3, 0) little-endian + payload
        assertEquals(6, encoded.size)
        assertEquals(0x3C, encoded[0].toInt())
        assertEquals(0x03, encoded[1].toInt())
        assertEquals(0x00, encoded[2].toInt())
        assertEquals(0x01, encoded[3].toInt())
        assertEquals(0x02, encoded[4].toInt())
        assertEquals(0x03, encoded[5].toInt())
    }

    @Test
    fun `encodes empty frame`() {
        val encoded = WiFiFrameCodec.encode(ByteArray(0))

        assertEquals(3, encoded.size)
        assertEquals(0x3C, encoded[0].toInt())
        assertEquals(0x00, encoded[1].toInt())
        assertEquals(0x00, encoded[2].toInt())
    }

    @Test
    fun `decodes single complete frame`() {
        // '>' + length (3, 0) + payload
        val data = byteArrayOf(0x3E, 0x03, 0x00, 0x01, 0x02, 0x03)
        val decoder = WiFiFrameDecoder()

        val frames = decoder.decode(data)

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), frames[0])
    }

    @Test
    fun `decodes multiple frames in one chunk`() {
        val data = byteArrayOf(
            0x3E, 0x02, 0x00, 0xAA.toByte(), 0xBB.toByte(), // frame 1
            0x3E, 0x01, 0x00, 0xCC.toByte(), // frame 2
        )
        val decoder = WiFiFrameDecoder()

        val frames = decoder.decode(data)

        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), frames[0])
        assertArrayEquals(byteArrayOf(0xCC.toByte()), frames[1])
    }

    @Test
    fun `buffers incomplete frame`() {
        val decoder = WiFiFrameDecoder()

        // Send header only
        assertTrue(decoder.decode(byteArrayOf(0x3E, 0x05, 0x00)).isEmpty())

        // Send partial payload
        assertTrue(decoder.decode(byteArrayOf(0x01, 0x02)).isEmpty())

        // Complete the frame
        val frames = decoder.decode(byteArrayOf(0x03, 0x04, 0x05))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05), frames[0])
    }

    @Test
    fun `handles frame split across chunks`() {
        val decoder = WiFiFrameDecoder()

        // First chunk: delimiter only
        assertTrue(decoder.decode(byteArrayOf(0x3E)).isEmpty())

        // Second chunk: length + partial payload
        assertTrue(decoder.decode(byteArrayOf(0x03, 0x00, 0xAA.toByte())).isEmpty())

        // Third chunk: rest of payload
        val frames = decoder.decode(byteArrayOf(0xBB.toByte(), 0xCC.toByte()))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), frames[0])
    }
}
