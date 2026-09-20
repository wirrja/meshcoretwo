// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/** Port of DiscoverResponseParsingTests.swift and PathDiscoveryParsingTests.swift. */
class BinaryParsersTest {
    // MARK: - ControlData / DiscoverResponse

    @Test
    fun `controlData parses discover response`() {
        // Control data: [snr:1][rssi:1][pathLen:1][payloadType:1][payload...]
        // DISCOVER_RESP payload: [snr_in:1][tag:4][pubkey:8 or 32]
        var payload = byteArrayOf(0x28, 0xAB.toByte(), 0x02, 0x95.toByte()) // 0x90 | 0x05
        payload += 0x14 // snr_in: 5.0
        payload += 12345u.toLittleEndianBytes()
        payload += byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())

        val event = ControlDataParser.parse(payload)
        val response = (event as? MeshEvent.DiscoverResponseEvent)?.response ?: run {
            fail("Expected DiscoverResponseEvent, got $event")
            return
        }

        assertEquals(5u.toUByte(), response.nodeType)
        assertTrue(abs(response.snrIn - 5.0) <= 0.001)
        assertTrue(abs(response.snr - 10.0) <= 0.001)
        assertEquals(-85, response.rssi)
        assertEquals(2u.toUByte(), response.pathLength)
        assertArrayEquals(byteArrayOf(0x39, 0x30, 0x00, 0x00), response.tag)
        assertEquals("1122334455667788", response.publicKey.hexString)
    }

    @Test
    fun `controlData parses full pubkey`() {
        var payload = byteArrayOf(0x28, 0xAB.toByte(), 0x01, 0x91.toByte())
        payload += 0x28
        payload += 999u.toLittleEndianBytes()
        payload += ByteArray(32) { 0xAA.toByte() }

        val event = ControlDataParser.parse(payload)
        val response = (event as? MeshEvent.DiscoverResponseEvent)?.response ?: run {
            fail("Expected DiscoverResponseEvent")
            return
        }

        assertEquals(32, response.publicKey.size)
        assertArrayEquals(ByteArray(32) { 0xAA.toByte() }, response.publicKey)
    }

    @Test
    fun `controlData non-discover returns raw`() {
        var payload = byteArrayOf(0x28, 0xAB.toByte(), 0x01, 0x80.toByte())
        payload += byteArrayOf(0x01, 0x02, 0x03)

        val event = ControlDataParser.parse(payload)
        val info = (event as? MeshEvent.ControlData)?.info ?: run {
            fail("Expected ControlData, got $event")
            return
        }

        assertEquals(0x80u.toUByte(), info.payloadType)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), info.payload)
    }

    @Test
    fun `controlData discover resp too short falls back to controlData`() {
        var payload = byteArrayOf(0x28, 0xAB.toByte(), 0x01, 0x91.toByte())
        payload += byteArrayOf(0x01, 0x02, 0x03, 0x04) // only 4 bytes, need 5

        val event = ControlDataParser.parse(payload)
        val info = (event as? MeshEvent.ControlData)?.info ?: run {
            fail("Expected ControlData for short DISCOVER_RESP payload, got $event")
            return
        }

        assertEquals(0x91u.toUByte(), info.payloadType)
    }

    // MARK: - PathDiscoveryResponse

    @Test
    fun `pathDiscoveryResponse skips reserved byte`() {
        var payload = byteArrayOf(0x00)
        payload += byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        payload += 0x02
        payload += byteArrayOf(0x11, 0x22)
        payload += 0x03
        payload += byteArrayOf(0x33, 0x44, 0x55)

        val event = PathDiscoveryResponseParser.parse(payload)
        val info = (event as? MeshEvent.PathResponse)?.info ?: run {
            fail("Expected PathResponse, got $event")
            return
        }

        assertEquals("aabbccddeeff", info.publicKeyPrefix.hexString)
        assertArrayEquals(byteArrayOf(0x11, 0x22), info.outPath)
        assertArrayEquals(byteArrayOf(0x33, 0x44, 0x55), info.inPath)
    }

    @Test
    fun `pathDiscoveryResponse handles empty paths`() {
        var payload = byteArrayOf(0x00)
        payload += byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        payload += byteArrayOf(0x00, 0x00)

        val event = PathDiscoveryResponseParser.parse(payload)
        val info = (event as? MeshEvent.PathResponse)?.info ?: run {
            fail("Expected PathResponse")
            return
        }

        assertEquals(0, info.outPath.size)
        assertEquals(0, info.inPath.size)
    }

    @Test
    fun `pathDiscoveryResponse rejects short payload`() {
        val shortPayload = byteArrayOf(0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte())
        assertTrue(PathDiscoveryResponseParser.parse(shortPayload) is MeshEvent.ParseFailure)
    }

    @Test
    fun `pathDiscoveryResponse preserves mode-1 out_path_len byte`() {
        var payload = byteArrayOf(0x00)
        payload += byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        payload += 0x42
        payload += byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte())
        payload += byteArrayOf(0x00, 0x00)

        val event = PathDiscoveryResponseParser.parse(payload)
        val info = (event as? MeshEvent.PathResponse)?.info ?: run {
            fail("Expected PathResponse, got $event")
            return
        }

        assertEquals(0x42u.toUByte(), info.outPathLength)
        assertEquals(2, info.outHopCount)
        assertArrayEquals(byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte()), info.outPath)
    }

    @Test
    fun `pathDiscoveryResponse preserves mode-2 out_path_len byte`() {
        var payload = byteArrayOf(0x00)
        payload += byteArrayOf(0x77, 0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        payload += 0x83.toByte()
        payload += byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09)
        payload += 0x00

        val event = PathDiscoveryResponseParser.parse(payload)
        val info = (event as? MeshEvent.PathResponse)?.info ?: run {
            fail("Expected PathResponse")
            return
        }

        assertEquals(0x83u.toUByte(), info.outPathLength)
        assertEquals(3, info.outHopCount)
        assertEquals(9, info.outPath.size)
    }

    @Test
    fun `pathDiscoveryResponse preserves both out and in length bytes`() {
        var payload = byteArrayOf(0x00)
        payload += byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x00, 0x01)
        payload += 0x02
        payload += byteArrayOf(0xA0.toByte(), 0xA1.toByte())
        payload += 0x81.toByte()
        payload += byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte())

        val event = PathDiscoveryResponseParser.parse(payload)
        val info = (event as? MeshEvent.PathResponse)?.info ?: run {
            fail("Expected PathResponse")
            return
        }

        assertEquals(0x02u.toUByte(), info.outPathLength)
        assertEquals(2, info.outHopCount)
        assertEquals(0x81u.toUByte(), info.inPathLength)
        assertEquals(1, info.inHopCount)
    }

    @Test
    fun `pathDiscoveryResponse outHopCount is null for reserved mode`() {
        var payload = byteArrayOf(0x00)
        payload += byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        payload += 0xC0.toByte()
        payload += 0x00

        val event = PathDiscoveryResponseParser.parse(payload)
        val info = (event as? MeshEvent.PathResponse)?.info ?: run {
            fail("Expected PathResponse")
            return
        }

        assertEquals(0xC0u.toUByte(), info.outPathLength)
        assertNull(info.outHopCount)
    }
}
