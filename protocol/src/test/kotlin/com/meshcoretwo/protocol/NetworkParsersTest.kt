// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * Port of NewResponseParsingTests.swift (AdvertPathResponse/TuningParamsResponse) and the
 * AllowedRepeatFreq round-trip case from RoundTripTests.swift.
 */
class NetworkParsersTest {
    @Test
    fun `advertPathResponse parse`() {
        var payload = 1_704_067_200u.toLittleEndianBytes() // timestamp
        payload += 0x03 // path length
        payload += byteArrayOf(0x11, 0x22, 0x33) // path

        val event = AdvertPathResponseParser.parse(payload)
        val response = (event as? MeshEvent.AdvertPathResponseEvent)?.response ?: run {
            fail("Expected AdvertPathResponseEvent, got $event")
            return
        }

        assertEquals(1_704_067_200u, response.recvTimestamp)
        assertEquals(3u.toUByte(), response.pathLength)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), response.path)
    }

    @Test
    fun `advertPathResponse empty path`() {
        var payload = 1000u.toLittleEndianBytes()
        payload += 0x00 // path length = 0

        val event = AdvertPathResponseParser.parse(payload)
        val response = (event as? MeshEvent.AdvertPathResponseEvent)?.response ?: run {
            fail("Expected AdvertPathResponseEvent")
            return
        }

        assertEquals(0u.toUByte(), response.pathLength)
        assertEquals(0, response.path.size)
    }

    @Test
    fun `advertPathResponse too short`() {
        val shortPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val event = AdvertPathResponseParser.parse(shortPayload)
        assertTrue("Expected ParseFailure for short payload", event is MeshEvent.ParseFailure)
    }

    @Test
    fun `advertPathResponse rejects reserved path length encoding`() {
        var payload = 1_704_067_200u.toLittleEndianBytes()
        payload += 0xC1.toByte() // mode 3 (reserved), hop count 1
        payload += 0x11

        val event = AdvertPathResponseParser.parse(payload)
        val failure = event as? MeshEvent.ParseFailure ?: run {
            fail("Expected ParseFailure for reserved path length, got $event")
            return
        }
        assertTrue(failure.reason.contains("reserved path length encoding"))
    }

    @Test
    fun `tuningParamsResponse parse`() {
        // rx_delay_base * 1000 = 1500 (1.5ms); airtime_factor * 1000 = 2500 (2.5)
        var payload = 1500u.toLittleEndianBytes()
        payload += 2500u.toLittleEndianBytes()

        val event = TuningParamsResponseParser.parse(payload)
        val response = (event as? MeshEvent.TuningParamsResponseEvent)?.response ?: run {
            fail("Expected TuningParamsResponseEvent, got $event")
            return
        }

        assertTrue(abs(response.rxDelayBase - 1.5) <= 0.001)
        assertTrue(abs(response.airtimeFactor - 2.5) <= 0.001)
    }

    @Test
    fun `tuningParamsResponse too short`() {
        val shortPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        val event = TuningParamsResponseParser.parse(shortPayload)
        assertTrue("Expected ParseFailure for short payload", event is MeshEvent.ParseFailure)
    }

    @Test
    fun `tuningParamsResponse zero values`() {
        var payload = 0u.toLittleEndianBytes()
        payload += 0u.toLittleEndianBytes()

        val event = TuningParamsResponseParser.parse(payload)
        val response = (event as? MeshEvent.TuningParamsResponseEvent)?.response ?: run {
            fail("Expected TuningParamsResponseEvent")
            return
        }

        assertTrue(abs(response.rxDelayBase - 0.0) <= 0.001)
        assertTrue(abs(response.airtimeFactor - 0.0) <= 0.001)
    }

    @Test
    fun `AllowedRepeatFreq round trip`() {
        val ranges = listOf(433_000u to 433_000u, 869_000u to 869_000u, 918_000u to 918_000u)
        var data = ByteArray(0)
        for ((lower, upper) in ranges) {
            data += lower.toLittleEndianBytes()
            data += upper.toLittleEndianBytes()
        }

        val event = AllowedRepeatFreqParser.parse(data)
        val parsed = (event as? MeshEvent.AllowedRepeatFreq)?.ranges ?: run {
            fail("Expected AllowedRepeatFreq event, got $event")
            return
        }

        assertEquals(3, parsed.size)
        assertEquals(433_000u, parsed[0].lowerKHz)
        assertEquals(433_000u, parsed[0].upperKHz)
        assertEquals(869_000u, parsed[1].lowerKHz)
        assertEquals(918_000u, parsed[2].lowerKHz)
    }
}
