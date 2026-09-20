// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/** Port of the Stats parser round-trip cases from MeshCoreTests/Validation/RoundTripTests.swift. */
class StatsParsersTest {
    @Test
    fun `CoreStats round trip`() {
        val batteryMV: UShort = 3750u
        val uptime: UInt = 86400u
        val errors: UShort = 3u
        val queueLen: UByte = 5u

        var data = batteryMV.toLittleEndianBytes()
        data += uptime.toLittleEndianBytes()
        data += errors.toLittleEndianBytes()
        data += queueLen.toByte()

        val event = CoreStatsParser.parse(data)
        val stats = (event as? MeshEvent.StatsCore)?.stats ?: run {
            fail("Expected StatsCore event, got $event")
            return
        }

        assertEquals(batteryMV, stats.batteryMV)
        assertEquals(uptime, stats.uptimeSeconds)
        assertEquals(errors, stats.errors)
        assertEquals(queueLen, stats.queueLength)
    }

    @Test
    fun `RadioStats round trip`() {
        val noiseFloor: Short = -115
        val lastRSSI: Byte = -90
        val lastSNRRaw: Byte = 28 // 7.0 * 4
        val txAir: UInt = 1000u
        val rxAir: UInt = 2000u

        var data = noiseFloor.toLittleEndianBytes()
        data += lastRSSI
        data += lastSNRRaw
        data += txAir.toLittleEndianBytes()
        data += rxAir.toLittleEndianBytes()

        val event = RadioStatsParser.parse(data)
        val stats = (event as? MeshEvent.StatsRadio)?.stats ?: run {
            fail("Expected StatsRadio event, got $event")
            return
        }

        assertEquals(noiseFloor, stats.noiseFloor)
        assertEquals(lastRSSI, stats.lastRSSI)
        assertTrue(abs(stats.lastSNR - 7.0) <= 0.01)
        assertEquals(txAir, stats.txAirtimeSeconds)
        assertEquals(rxAir, stats.rxAirtimeSeconds)
    }

    @Test
    fun `PacketStats round trip (legacy 24-byte format)`() {
        val received = 1000u
        val sent = 500u
        val floodTx = 100u
        val directTx = 400u
        val floodRx = 200u
        val directRx = 800u

        var data = received.toLittleEndianBytes()
        data += sent.toLittleEndianBytes()
        data += floodTx.toLittleEndianBytes()
        data += directTx.toLittleEndianBytes()
        data += floodRx.toLittleEndianBytes()
        data += directRx.toLittleEndianBytes()

        val event = PacketStatsParser.parse(data)
        val stats = (event as? MeshEvent.StatsPackets)?.stats ?: run {
            fail("Expected StatsPackets event, got $event")
            return
        }

        assertEquals(received, stats.received)
        assertEquals(sent, stats.sent)
        assertEquals(floodTx, stats.floodTx)
        assertEquals(directTx, stats.directTx)
        assertEquals(floodRx, stats.floodRx)
        assertEquals(directRx, stats.directRx)
        assertEquals(0u, stats.receiveErrors)
    }

    @Test
    fun `PacketStats round trip (28-byte format with receiveErrors)`() {
        val received = 1000u
        val sent = 500u
        val floodTx = 100u
        val directTx = 400u
        val floodRx = 200u
        val directRx = 800u
        val receiveErrors = 42u

        var data = received.toLittleEndianBytes()
        data += sent.toLittleEndianBytes()
        data += floodTx.toLittleEndianBytes()
        data += directTx.toLittleEndianBytes()
        data += floodRx.toLittleEndianBytes()
        data += directRx.toLittleEndianBytes()
        data += receiveErrors.toLittleEndianBytes()

        val event = PacketStatsParser.parse(data)
        val stats = (event as? MeshEvent.StatsPackets)?.stats ?: run {
            fail("Expected StatsPackets event, got $event")
            return
        }

        assertEquals(received, stats.received)
        assertEquals(sent, stats.sent)
        assertEquals(floodTx, stats.floodTx)
        assertEquals(directTx, stats.directTx)
        assertEquals(floodRx, stats.floodRx)
        assertEquals(directRx, stats.directRx)
        assertEquals(receiveErrors, stats.receiveErrors)
    }
}
