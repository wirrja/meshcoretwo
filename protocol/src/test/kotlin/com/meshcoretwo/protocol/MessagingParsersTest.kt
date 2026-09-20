// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * Port of the ContactMessage/ChannelMessage round-trip cases from RoundTripTests.swift and the
 * ChannelDatagram cases from V115ParsingTests.swift (excluding the "routes via PacketParser"
 * siblings, deferred to PacketParserTest.kt).
 */
class MessagingParsersTest {
    @Test
    fun `ContactMessage v3 round trip`() {
        val snrRaw: Byte = 24 // 6.0 dB * 4
        val pubkeyPrefix = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte())
        val pathLen: UByte = 2u
        val txtType: UByte = 0u
        val timestamp = 1_704_067_200u
        val text = "Hello World"

        var data = byteArrayOf(snrRaw)
        data += 0u.toUShort().toLittleEndianBytes()
        data += pubkeyPrefix
        data += pathLen.toByte()
        data += txtType.toByte()
        data += timestamp.toLittleEndianBytes()
        data += text.toByteArray(Charsets.UTF_8)

        val event = ContactMessageParser.parse(data, ContactMessageParser.Version.V3)
        val msg = (event as? MeshEvent.ContactMessageReceived)?.message ?: run {
            fail("Expected ContactMessageReceived event, got $event")
            return
        }

        assertTrue(abs((msg.snr ?: 0.0) - 6.0) <= 0.01)
        assertArrayEquals(pubkeyPrefix, msg.senderPublicKeyPrefix)
        assertEquals(pathLen, msg.pathLength)
        assertEquals(txtType, msg.textType)
        assertEquals("Hello World", msg.text)
    }

    @Test
    fun `ContactMessage rejects truncated signature payload`() {
        var data = byteArrayOf(0)
        data += 0u.toUShort().toLittleEndianBytes()
        data += byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte())
        data += 0x00
        data += 0x02 // signed text
        data += 1_704_067_200u.toLittleEndianBytes()
        data += byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte()) // only 3 sig bytes, no text

        val event = ContactMessageParser.parse(data, ContactMessageParser.Version.V3)
        val failure = event as? MeshEvent.ParseFailure ?: run {
            fail("Expected ParseFailure event, got $event")
            return
        }
        assertTrue(failure.reason.contains("signature truncated"))
    }

    @Test
    fun `ChannelMessage v3 round trip`() {
        val snrRaw: Byte = -20 // -5.0 dB * 4
        val channel: UByte = 2u
        val pathLen: UByte = 0u
        val txtType: UByte = 0u
        val timestamp = 1_704_067_200u
        val text = "Broadcast message"

        var data = byteArrayOf(snrRaw)
        data += 0u.toUShort().toLittleEndianBytes()
        data += channel.toByte()
        data += pathLen.toByte()
        data += txtType.toByte()
        data += timestamp.toLittleEndianBytes()
        data += text.toByteArray(Charsets.UTF_8)

        val event = ChannelMessageParser.parse(data, ChannelMessageParser.Version.V3)
        val msg = (event as? MeshEvent.ChannelMessageReceived)?.message ?: run {
            fail("Expected ChannelMessageReceived event, got $event")
            return
        }

        assertTrue(abs((msg.snr ?: 0.0) - -5.0) <= 0.01)
        assertEquals(channel, msg.channelIndex)
        assertEquals(pathLen, msg.pathLength)
        assertEquals("Broadcast message", msg.text)
    }

    @Test
    fun `ChannelMessage v3 empty-text broadcast parses at firmware-minimum size`() {
        val snrRaw: Byte = -20
        val channel: UByte = 2u
        val pathLen: UByte = 0u
        val txtType: UByte = 0u
        val timestamp = 1_704_067_200u

        var data = byteArrayOf(snrRaw)
        data += 0u.toUShort().toLittleEndianBytes()
        data += channel.toByte()
        data += pathLen.toByte()
        data += txtType.toByte()
        data += timestamp.toLittleEndianBytes()
        // No text bytes: firmware emits this when strlen(text) == 0.

        assertEquals(10, data.size)

        val event = ChannelMessageParser.parse(data, ChannelMessageParser.Version.V3)
        val msg = (event as? MeshEvent.ChannelMessageReceived)?.message ?: run {
            fail("Expected ChannelMessageReceived event for empty-text broadcast, got $event")
            return
        }

        assertEquals(channel, msg.channelIndex)
        assertEquals(pathLen, msg.pathLength)
        assertTrue(msg.text.isEmpty())
    }

    @Test
    fun `ChannelMessage v1 empty-text broadcast parses at firmware-minimum size`() {
        val channel: UByte = 3u
        val pathLen: UByte = 0u
        val txtType: UByte = 0u
        val timestamp = 1_704_067_200u

        var data = byteArrayOf(channel.toByte(), pathLen.toByte(), txtType.toByte())
        data += timestamp.toLittleEndianBytes()

        assertEquals(7, data.size)

        val event = ChannelMessageParser.parse(data, ChannelMessageParser.Version.V1)
        val msg = (event as? MeshEvent.ChannelMessageReceived)?.message ?: run {
            fail("Expected ChannelMessageReceived event for empty-text broadcast, got $event")
            return
        }

        assertEquals(channel, msg.channelIndex)
        assertEquals(pathLen, msg.pathLength)
        assertTrue(msg.text.isEmpty())
    }

    // MARK: - ChannelDatagram (V115ParsingTests.swift)

    @Test
    fun `channelDatagram parses valid payload`() {
        // Frame after PacketParser strips the 0x1B response byte:
        // [snr:1][rsv:1][rsv:1][channel:1][path_len:1][data_type LE:2][data_len:1][data...]
        val snrByte: Byte = 20 // 20/4 = 5.0 dB
        val data = byteArrayOf(
            snrByte, 0x00, 0x00,
            0x03, // channel index
            0xFF.toByte(), // path_len: direct route
            0xFF.toByte(), 0xFF.toByte(), // data_type LE 0xFFFF
            0x04, // data_len
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        )

        val event = ChannelDatagramParser.parse(data)
        val dg = (event as? MeshEvent.ChannelDataReceived)?.datagram ?: run {
            fail("Expected ChannelDataReceived, got $event")
            return
        }

        assertEquals(3u.toUByte(), dg.channelIndex)
        assertEquals(0xFFu.toUByte(), dg.pathLength)
        assertEquals(0xFFFFu.toUShort(), dg.dataType)
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), dg.data)
        assertEquals(5.0, dg.snr, 0.0001)
    }

    @Test
    fun `channelDatagram rejects truncated payload`() {
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0xFF.toByte()) // missing data_type+data_len+data
        assertTrue(ChannelDatagramParser.parse(data) is MeshEvent.ParseFailure)
    }

    @Test
    fun `channelDatagram truncates when declared data_len exceeds remaining bytes`() {
        val data = byteArrayOf(
            0x00, 0x00, 0x00,
            0x01,
            0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0x10, // claims 16 bytes
            0xAA.toByte(), 0xBB.toByte(), // only 2 actually follow
        )
        val event = ChannelDatagramParser.parse(data)
        val dg = (event as? MeshEvent.ChannelDataReceived)?.datagram ?: run {
            fail("Expected datagram, got $event")
            return
        }
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), dg.data)
    }
}
