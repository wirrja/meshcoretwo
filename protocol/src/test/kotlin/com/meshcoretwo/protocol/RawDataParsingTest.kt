// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class RawDataParsingTest {
    @Test
    fun `rawData skips reserved byte`() {
        // Firmware format: [snr:1][rssi:1][reserved:1][payload...]
        var payload = byteArrayOf(0x28, 0xAB.toByte(), 0xFF.toByte()) // snr=10.0, rssi=-85, reserved
        payload += byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val event = RawDataParser.parse(payload)
        val info = (event as? MeshEvent.RawData)?.info ?: run {
            fail("Expected RawData event, got $event")
            return
        }

        assertTrue(abs(info.snr - 10.0) <= 0.001)
        assertEquals(-85, info.rssi)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), info.payload)
    }

    @Test
    fun `rawData rejects short payload`() {
        val shortPayload = byteArrayOf(0x28, 0xAB.toByte())
        assertTrue(RawDataParser.parse(shortPayload) is MeshEvent.ParseFailure)
    }

    @Test
    fun `rawData handles empty payload`() {
        val payload = byteArrayOf(0x28, 0xAB.toByte(), 0xFF.toByte())
        val event = RawDataParser.parse(payload)
        val info = (event as? MeshEvent.RawData)?.info ?: run {
            fail("Expected RawData event")
            return
        }
        assertEquals(0, info.payload.size)
    }
}
