// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/** Port of the StatusResponse round-trip case from RoundTripTests.swift and all of TelemetryParsingTests.swift. */
class StatusParsersTest {
    @Test
    fun `StatusResponse round trip`() {
        val pubkeyPrefix = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte())
        val battery: UShort = 3800u
        val txQueue: UShort = 5u
        val noiseFloor: Short = -110
        val lastRSSI: Short = -85
        val packetsRecv = 1000u
        val packetsSent = 500u
        val airtime = 3600u
        val uptime = 86400u
        val sentFlood = 100u
        val sentDirect = 400u
        val recvFlood = 200u
        val recvDirect = 800u
        val fullEvents: UShort = 10u
        val lastSNRRaw: Short = 24 // 6.0 * 4
        val directDups: UShort = 5u
        val floodDups: UShort = 15u
        val rxAirtime = 1800u

        var data = byteArrayOf(0x00) // Reserved byte
        data += pubkeyPrefix
        data += battery.toLittleEndianBytes()
        data += txQueue.toLittleEndianBytes()
        data += noiseFloor.toLittleEndianBytes()
        data += lastRSSI.toLittleEndianBytes()
        data += packetsRecv.toLittleEndianBytes()
        data += packetsSent.toLittleEndianBytes()
        data += airtime.toLittleEndianBytes()
        data += uptime.toLittleEndianBytes()
        data += sentFlood.toLittleEndianBytes()
        data += sentDirect.toLittleEndianBytes()
        data += recvFlood.toLittleEndianBytes()
        data += recvDirect.toLittleEndianBytes()
        data += fullEvents.toLittleEndianBytes()
        data += lastSNRRaw.toLittleEndianBytes()
        data += directDups.toLittleEndianBytes()
        data += floodDups.toLittleEndianBytes()
        data += rxAirtime.toLittleEndianBytes()

        val event = StatusResponseParser.parse(data)
        val status = (event as? MeshEvent.StatusResponseEvent)?.response ?: run {
            fail("Expected StatusResponseEvent, got $event")
            return
        }

        assertArrayEquals(pubkeyPrefix, status.publicKeyPrefix)
        assertEquals(battery.toInt(), status.battery)
        assertEquals(txQueue.toInt(), status.txQueueLength)
        assertEquals(noiseFloor.toInt(), status.noiseFloor)
        assertEquals(lastRSSI.toInt(), status.lastRSSI)
        assertEquals(packetsRecv, status.packetsReceived)
        assertEquals(packetsSent, status.packetsSent)
        assertEquals(airtime, status.airtime)
        assertEquals(uptime, status.uptime)
        assertEquals(sentFlood, status.sentFlood)
        assertEquals(sentDirect, status.sentDirect)
        assertEquals(recvFlood, status.receivedFlood)
        assertEquals(recvDirect, status.receivedDirect)
        assertEquals(fullEvents.toInt(), status.fullEvents)
        assertTrue(abs(status.lastSNR - 6.0) <= 0.01)
        assertEquals(directDups.toInt(), status.directDuplicates)
        assertEquals(floodDups.toInt(), status.floodDuplicates)
        assertEquals(rxAirtime, status.rxAirtime)
        assertEquals(0u, status.receiveErrors)
    }

    @Test
    fun `telemetryResponse skips reserved byte`() {
        // Firmware format: [reserved:1][pubkey_prefix:6][lpp_data...]
        var payload = byteArrayOf(0x00)
        payload += byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        payload += byteArrayOf(0x01, 0x67, 0x00, 0xFA.toByte()) // LPP: channel 1, temp, 25.0C

        val event = TelemetryResponseParser.parse(payload)
        val response = (event as? MeshEvent.TelemetryResponseEvent)?.response ?: run {
            fail("Expected TelemetryResponseEvent, got $event")
            return
        }

        assertEquals("aabbccddeeff", response.publicKeyPrefix.hexString)
        assertNull(response.tag)
        assertArrayEquals(byteArrayOf(0x01, 0x67, 0x00, 0xFA.toByte()), response.rawData)
    }

    @Test
    fun `telemetryResponse rejects short payload`() {
        val shortPayload = byteArrayOf(0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte())
        assertTrue(TelemetryResponseParser.parse(shortPayload) is MeshEvent.ParseFailure)
    }

    @Test
    fun `telemetryResponse handles empty LPP data`() {
        var payload = byteArrayOf(0x00)
        payload += byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)

        val event = TelemetryResponseParser.parse(payload)
        val response = (event as? MeshEvent.TelemetryResponseEvent)?.response ?: run {
            fail("Expected TelemetryResponseEvent")
            return
        }

        assertEquals(0, response.rawData.size)
    }
}
