// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class TraceDataParsingTest {
    private fun parseTrace(payload: ByteArray): TraceInfo {
        val event = TraceDataParser.parse(payload)
        return (event as? MeshEvent.TraceData)?.info ?: run {
            fail("Expected TraceData event, got $event")
            error("unreachable")
        }
    }

    @Test
    fun `traceData pathSz=0 single byte hashes`() {
        var payload = byteArrayOf(0x00, 0x02, 0x00) // reserved, pathLength=2, flags(path_sz=0)
        payload += 12345u.toLittleEndianBytes() // tag
        payload += 67890u.toLittleEndianBytes() // authCode
        payload += byteArrayOf(0xAA.toByte(), 0xBB.toByte()) // 2 hash bytes
        payload += byteArrayOf(0x28, 0x14) // SNRs: 10.0, 5.0
        payload += 0x0C // final SNR: 3.0

        val trace = parseTrace(payload)

        assertEquals(12345u, trace.tag)
        assertEquals(67890u, trace.authCode)
        assertEquals(3, trace.path.size)

        assertArrayEquals(byteArrayOf(0xAA.toByte()), trace.path[0].hashBytes)
        assertArrayEquals(byteArrayOf(0xBB.toByte()), trace.path[1].hashBytes)
        assertNull(trace.path[2].hashBytes)

        assertTrue(abs(trace.path[0].snr - 10.0) <= 0.001)
        assertTrue(abs(trace.path[1].snr - 5.0) <= 0.001)
        assertTrue(abs(trace.path[2].snr - 3.0) <= 0.001)
    }

    @Test
    fun `traceData pathSz=2 four byte hashes`() {
        var payload = byteArrayOf(0x00, 0x08, 0x02) // pathLength=8, flags(path_sz=2)
        payload += 111u.toLittleEndianBytes()
        payload += 222u.toLittleEndianBytes()
        payload += byteArrayOf(0x11, 0x22, 0x33, 0x44) // hop 0
        payload += byteArrayOf(0x55, 0x66, 0x77.toByte(), 0x88.toByte()) // hop 1
        payload += byteArrayOf(0x28, 0x14)
        payload += 0x0C

        val trace = parseTrace(payload)

        assertEquals(3, trace.path.size)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), trace.path[0].hashBytes)
        assertArrayEquals(byteArrayOf(0x55, 0x66, 0x77.toByte(), 0x88.toByte()), trace.path[1].hashBytes)
        assertNull(trace.path[2].hashBytes)

        assertEquals(0x11u.toUByte(), trace.path[0].hash)
    }

    @Test
    fun `traceData pathSz=1 two byte hashes`() {
        var payload = byteArrayOf(0x00, 0x04, 0x01) // pathLength=4, flags(path_sz=1)
        payload += 100u.toLittleEndianBytes()
        payload += 200u.toLittleEndianBytes()
        payload += byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        payload += byteArrayOf(0xCC.toByte(), 0xDD.toByte())
        payload += byteArrayOf(0x28, 0x14)
        payload += 0x0C

        val trace = parseTrace(payload)

        assertEquals(3, trace.path.size)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), trace.path[0].hashBytes)
        assertArrayEquals(byteArrayOf(0xCC.toByte(), 0xDD.toByte()), trace.path[1].hashBytes)
        assertNull(trace.path[2].hashBytes)
    }

    @Test
    fun `traceData destination marker`() {
        var payload = byteArrayOf(0x00, 0x01, 0x00)
        payload += 1u.toLittleEndianBytes()
        payload += 2u.toLittleEndianBytes()
        payload += 0xFF.toByte() // destination marker
        payload += byteArrayOf(0x28, 0x14)

        val trace = parseTrace(payload)
        assertNull(trace.path[0].hashBytes)
    }

    @Test
    fun `traceData empty path`() {
        var payload = byteArrayOf(0x00, 0x00, 0x00) // pathLength = 0
        payload += 999u.toLittleEndianBytes()
        payload += 888u.toLittleEndianBytes()
        payload += 0x28 // final SNR only

        val trace = parseTrace(payload)

        assertEquals(1, trace.path.size)
        assertNull(trace.path[0].hashBytes)
        assertTrue(abs(trace.path[0].snr - 10.0) <= 0.001)
    }

    @Test
    fun `traceData legacy hash accessor`() {
        var payload = byteArrayOf(0x00, 0x01, 0x00)
        payload += 1u.toLittleEndianBytes()
        payload += 2u.toLittleEndianBytes()
        payload += 0x42
        payload += byteArrayOf(0x28, 0x14)

        val trace = parseTrace(payload)
        assertEquals(0x42u.toUByte(), trace.path[0].hash)
        assertNull(trace.path[1].hash)
    }

    @Test
    fun `traceData too short payload`() {
        val shortPayload = byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        val event = TraceDataParser.parse(shortPayload)
        assertTrue(event is MeshEvent.ParseFailure)
    }
}
