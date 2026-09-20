// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RxLogParserTest {
    @Test
    fun `Parse empty payload returns null`() {
        assertNull(RxLogParser.parse(snr = 5.0, rssi = -80, payload = ByteArray(0)))
    }

    @Test
    fun `Parse FLOOD GROUP_TEXT packet`() {
        // Header: routeType=1 (FLOOD), payloadType=5 (GROUP_TEXT), version=0 -> 0x15
        val payload = byteArrayOf(0x15, 0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())

        val result = RxLogParser.parse(snr = 8.0, rssi = -85, payload = payload)

        assertNotNull(result)
        assertEquals(RouteType.FLOOD, result!!.routeType)
        assertEquals(PayloadType.GROUP_TEXT, result.payloadType)
        assertEquals(0u.toUByte(), result.payloadVersion)
        assertNull(result.transportCode)
        assertEquals(0u.toUByte(), result.pathLength)
        assertEquals(emptyList<UByte>(), result.pathNodes)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), result.packetPayload)
    }

    @Test
    fun `Parse TC_FLOOD with transport code and path`() {
        // Header: routeType=0 (TC_FLOOD), payloadType=5 (GROUP_TEXT), version=1 -> 0x54
        val payload = byteArrayOf(
            0x54,
            0x01, 0x02, 0x03, 0x04,
            0x02,
            0x3A, 0x7F,
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        )

        val result = RxLogParser.parse(snr = null, rssi = null, payload = payload)

        assertNotNull(result)
        assertEquals(RouteType.TC_FLOOD, result!!.routeType)
        assertEquals(PayloadType.GROUP_TEXT, result.payloadType)
        assertEquals(1u.toUByte(), result.payloadVersion)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), result.transportCode)
        assertEquals(2u.toUByte(), result.pathLength)
        assertEquals(listOf(0x3Au.toUByte(), 0x7Fu.toUByte()), result.pathNodes)
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), result.packetPayload)
    }

    @Test
    fun `Parse DIRECT packet (no transport code)`() {
        // Header: routeType=2 (DIRECT), payloadType=2 (TEXT_MSG), version=0 -> 0x0A
        val payload = byteArrayOf(0x0A, 0x01, 0xFF.toByte(), 0x48, 0x69)

        val result = RxLogParser.parse(snr = 6.5, rssi = -70, payload = payload)

        assertNotNull(result)
        assertEquals(RouteType.DIRECT, result!!.routeType)
        assertEquals(PayloadType.TEXT_MESSAGE, result.payloadType)
        assertNull(result.transportCode)
        assertEquals(1u.toUByte(), result.pathLength)
        assertEquals(listOf(0xFFu.toUByte()), result.pathNodes)
        assertArrayEquals(byteArrayOf(0x48, 0x69), result.packetPayload)
    }

    @Test
    fun `Parse TC_DIRECT with transport code`() {
        // Header: routeType=3 (TC_DIRECT), payloadType=2 (TEXT_MSG), version=0 -> 0x0B
        val payload = byteArrayOf(
            0x0B,
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(),
            0x01,
            0x42,
            0x48, 0x69,
        )

        val result = RxLogParser.parse(snr = 7.0, rssi = -75, payload = payload)

        assertNotNull(result)
        assertEquals(RouteType.TC_DIRECT, result!!.routeType)
        assertEquals(PayloadType.TEXT_MESSAGE, result.payloadType)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()), result.transportCode)
        assertEquals(1u.toUByte(), result.pathLength)
        assertEquals(listOf(0x42u.toUByte()), result.pathNodes)
        assertArrayEquals(byteArrayOf(0x48, 0x69), result.packetPayload)
    }

    @Test
    fun `Parse packet with unknown payload type`() {
        // Header: routeType=1 (FLOOD), payloadType=14 (undefined), version=0 -> 0x39
        val payload = byteArrayOf(0x39, 0x00, 0x01, 0x02)

        val result = RxLogParser.parse(snr = null, rssi = null, payload = payload)

        assertNotNull(result)
        assertEquals(PayloadType.UNKNOWN, result!!.payloadType)
    }

    @Test
    fun `Parse DIRECT TEXT_MSG extracts sender and recipient pubkey hashes`() {
        val destHash: Byte = 0x07
        val srcHash: Byte = 0x0A
        var payload = byteArrayOf(0x0A, 0x00, destHash, srcHash)
        payload += byteArrayOf(0x48, 0x69, 0xAB.toByte(), 0xCD.toByte())

        val result = RxLogParser.parse(snr = 5.0, rssi = -80, payload = payload)

        assertNotNull(result)
        assertArrayEquals(byteArrayOf(srcHash), result!!.senderPubkeyPrefix)
        assertArrayEquals(byteArrayOf(destHash), result.recipientPubkeyPrefix)
    }

    @Test
    fun `Parse TC_DIRECT TEXT_MSG extracts sender and recipient pubkey hashes`() {
        val destHash: Byte = 0x12
        val srcHash: Byte = 0x34
        val payload = byteArrayOf(
            0x0B,
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(),
            0x00,
            destHash, srcHash,
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        )

        val result = RxLogParser.parse(snr = 5.0, rssi = -80, payload = payload)

        assertNotNull(result)
        assertArrayEquals(byteArrayOf(srcHash), result!!.senderPubkeyPrefix)
        assertArrayEquals(byteArrayOf(destHash), result.recipientPubkeyPrefix)
    }

    @Test
    fun `Parse DIRECT TEXT_MSG with hashSize greater than 1 path still extracts 1-byte payload hashes`() {
        val destHash: Byte = 0x07
        val srcHash: Byte = 0x0A
        val pathLenByte = 0x41.toByte() // hashSize=2, hopCount=1
        val payload = byteArrayOf(
            0x0A,
            pathLenByte,
            0xAB.toByte(), 0xCD.toByte(),
            destHash, srcHash,
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        )

        val result = RxLogParser.parse(snr = 5.0, rssi = -80, payload = payload)

        assertNotNull(result)
        assertArrayEquals(byteArrayOf(srcHash), result!!.senderPubkeyPrefix)
        assertArrayEquals(byteArrayOf(destHash), result.recipientPubkeyPrefix)
    }

    @Test
    fun `Parse FLOOD GROUP_TEXT has null sender pubkey prefix`() {
        val payload = byteArrayOf(0x15, 0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val result = RxLogParser.parse(snr = 8.0, rssi = -85, payload = payload)
        assertNotNull(result)
        assertNull(result!!.senderPubkeyPrefix)
    }

    @Test
    fun `MeshEvent RxLogData holds ParsedRxLogData`() {
        val parsed = ParsedRxLogData(
            snr = 5.0, rssi = -80, rawPayload = byteArrayOf(0x15, 0x00, 0xAA.toByte()),
            routeType = RouteType.FLOOD, payloadType = PayloadType.GROUP_TEXT, payloadVersion = 0u,
            payloadTypeBits = 5u,
            transportCode = null, pathLength = 0u, pathNodes = emptyList(),
            packetPayload = byteArrayOf(0xAA.toByte()),
        )

        val event: MeshEvent = MeshEvent.RxLogData(parsed)

        val data = (event as? MeshEvent.RxLogData)?.data
        assertNotNull(data)
        assertEquals(RouteType.FLOOD, data!!.routeType)
        assertEquals(16, data.packetHash.length)
    }

    @Test
    fun `Parse rejects reserved (mode 3) path length encoding`() {
        val reservedPathLen = 0xC1.toByte()
        val payload = byteArrayOf(0x0A, reservedPathLen, 0x07, 0x0A, 0xDE.toByte(), 0xAD.toByte())
        assertNull(RxLogParser.parse(snr = 5.0, rssi = -80, payload = payload))
    }

    @Test
    fun `Parse FLOOD TEXT_MSG extracts sender and recipient pubkey hashes`() {
        val destHash: Byte = 0x07
        val srcHash: Byte = 0x0A
        var payload = byteArrayOf(0x09, 0x00, destHash, srcHash)
        payload += byteArrayOf(0x48, 0x69, 0xAB.toByte(), 0xCD.toByte())

        val result = RxLogParser.parse(snr = 5.0, rssi = -80, payload = payload)

        assertNotNull(result)
        assertEquals(RouteType.FLOOD, result!!.routeType)
        assertEquals(PayloadType.TEXT_MESSAGE, result.payloadType)
        assertArrayEquals(byteArrayOf(srcHash), result.senderPubkeyPrefix)
        assertArrayEquals(byteArrayOf(destHash), result.recipientPubkeyPrefix)
    }
}
