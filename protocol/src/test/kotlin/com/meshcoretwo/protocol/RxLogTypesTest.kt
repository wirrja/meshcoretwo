// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RxLogTypesTest {
    @Test
    fun `RouteType raw values match protocol spec`() {
        assertEquals(0u.toUByte(), RouteType.TC_FLOOD.value)
        assertEquals(1u.toUByte(), RouteType.FLOOD.value)
        assertEquals(2u.toUByte(), RouteType.DIRECT.value)
        assertEquals(3u.toUByte(), RouteType.TC_DIRECT.value)
    }

    @Test
    fun `RouteType hasTransportCode`() {
        assertTrue(RouteType.TC_FLOOD.hasTransportCode)
        assertTrue(!RouteType.FLOOD.hasTransportCode)
        assertTrue(!RouteType.DIRECT.hasTransportCode)
        assertTrue(RouteType.TC_DIRECT.hasTransportCode)
    }

    @Test
    fun `PayloadType raw values match protocol spec`() {
        assertEquals(0u.toUByte(), PayloadType.REQUEST.value)
        assertEquals(5u.toUByte(), PayloadType.GROUP_TEXT.value)
        assertEquals(11u.toUByte(), PayloadType.CONTROL.value)
        assertEquals(255u.toUByte(), PayloadType.UNKNOWN.value)
    }

    @Test
    fun `Reserved PayloadType values 12-14 map to unknown via fromBits`() {
        assertEquals(PayloadType.UNKNOWN, PayloadType.fromBits(12u))
        assertEquals(PayloadType.UNKNOWN, PayloadType.fromBits(13u))
        assertEquals(PayloadType.UNKNOWN, PayloadType.fromBits(14u))
    }

    @Test
    fun `PayloadType value 15 maps to rawCustom via fromBits`() {
        assertEquals(PayloadType.RAW_CUSTOM, PayloadType.fromBits(15u))
    }

    @Test
    fun `PayloadType fromValue returns null for undefined values`() {
        assertNull(PayloadType.fromValue(12u))
        assertEquals(PayloadType.UNKNOWN, PayloadType.fromValue(255u))
    }

    @Test
    fun `PayloadType fromBits with valid values`() {
        assertEquals(PayloadType.REQUEST, PayloadType.fromBits(0u))
        assertEquals(PayloadType.GROUP_TEXT, PayloadType.fromBits(5u))
        assertEquals(PayloadType.CONTROL, PayloadType.fromBits(11u))
    }

    @Test
    fun `RouteType displayName`() {
        assertEquals("TC_FLOOD", RouteType.TC_FLOOD.displayName)
        assertEquals("FLOOD", RouteType.FLOOD.displayName)
        assertEquals("DIRECT", RouteType.DIRECT.displayName)
        assertEquals("TC_DIRECT", RouteType.TC_DIRECT.displayName)
    }

    @Test
    fun `PayloadType displayName`() {
        assertEquals("REQUEST", PayloadType.REQUEST.displayName)
        assertEquals("GROUP_TEXT", PayloadType.GROUP_TEXT.displayName)
        assertEquals("UNKNOWN", PayloadType.UNKNOWN.displayName)
    }

    @Test
    fun `ParsedRxLogData initializes with all fields`() {
        val data = ParsedRxLogData(
            snr = 8.5,
            rssi = -85,
            rawPayload = byteArrayOf(0x01, 0x02, 0x03),
            routeType = RouteType.FLOOD,
            payloadType = PayloadType.GROUP_TEXT,
            payloadVersion = 1u,
            payloadTypeBits = 5u,
            transportCode = null,
            pathLength = 2u,
            pathNodes = listOf(0x3Au.toUByte(), 0x7Fu.toUByte()),
            packetPayload = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
        )

        assertEquals(8.5, data.snr)
        assertEquals(-85, data.rssi)
        assertEquals(RouteType.FLOOD, data.routeType)
        assertEquals(PayloadType.GROUP_TEXT, data.payloadType)
        assertEquals(1u.toUByte(), data.payloadVersion)
        assertNull(data.transportCode)
        assertEquals(2u.toUByte(), data.pathLength)
        assertEquals(listOf(0x3Au.toUByte(), 0x7Fu.toUByte()), data.pathNodes)
        assertEquals(16, data.packetHash.length) // 8 bytes as hex
    }

    @Test
    fun `ParsedRxLogData packetHash is stable`() {
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val data1 = ParsedRxLogData(
            snr = null, rssi = null, rawPayload = ByteArray(0),
            routeType = RouteType.FLOOD, payloadType = PayloadType.GROUP_TEXT, payloadVersion = 0u,
            payloadTypeBits = 5u,
            transportCode = null, pathLength = 0u, pathNodes = emptyList(),
            packetPayload = payload,
        )
        val data2 = ParsedRxLogData(
            snr = 5.0, rssi = -90, rawPayload = byteArrayOf(0xFF.toByte()),
            routeType = RouteType.DIRECT, payloadType = PayloadType.ACK, payloadVersion = 2u,
            payloadTypeBits = 3u,
            transportCode = byteArrayOf(0x01, 0x02, 0x03, 0x04), pathLength = 3u,
            pathNodes = listOf(0x11u, 0x22u, 0x33u),
            packetPayload = payload, // Same payload
        )

        // Same packetPayload should produce same hash regardless of other fields
        assertEquals(data1.packetHash, data2.packetHash)
    }
}
