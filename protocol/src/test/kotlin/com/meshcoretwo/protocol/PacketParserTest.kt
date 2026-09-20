// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Port of AckParsingTests.swift, the "routes via PacketParser" sibling cases deferred from
 * ChannelParsersTest/MessagingParsersTest (V115ParsingTests.swift), and the PacketParser
 * integration cases from V112ProtocolTests.swift, plus a few router-level sanity checks.
 */
class PacketParserTest {
    // MARK: - Router basics

    @Test
    fun `empty packet is a parse failure`() {
        assertTrue(PacketParser.parse(ByteArray(0)) is MeshEvent.ParseFailure)
    }

    @Test
    fun `unknown response code is a parse failure`() {
        assertTrue(PacketParser.parse(byteArrayOf(0xFF.toByte())) is MeshEvent.ParseFailure)
    }

    @Test
    fun `OK with no payload has null value`() {
        val event = PacketParser.parse(byteArrayOf(ResponseCode.OK.value.toByte()))
        assertEquals(MeshEvent.Ok(null), event)
    }

    @Test
    fun `OK with 4-byte payload parses the value`() {
        var frame = byteArrayOf(ResponseCode.OK.value.toByte())
        frame += 42u.toLittleEndianBytes()
        assertEquals(MeshEvent.Ok(42u), PacketParser.parse(frame))
    }

    @Test
    fun `Error with a code byte parses it`() {
        val frame = byteArrayOf(ResponseCode.ERROR.value.toByte(), ErrorCode.NOT_FOUND.value.toByte())
        assertEquals(MeshEvent.Error(ErrorCode.NOT_FOUND.value), PacketParser.parse(frame))
    }

    // MARK: - AckParsingTests.swift

    @Test
    fun `4-byte ACK payload produces tripTime null`() {
        val ackCode = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        var frame = byteArrayOf(ResponseCode.ACK.value.toByte())
        frame += ackCode

        val event = PacketParser.parse(frame)
        val ack = event as? MeshEvent.Acknowledgement ?: run {
            fail("Expected Acknowledgement, got $event")
            return
        }
        assertArrayEquals(ackCode, ack.code)
        assertNull(ack.tripTime)
    }

    @Test
    fun `8-byte ACK payload parses trip time as UInt32 LE`() {
        val ackCode = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        // trip_time = 500ms = 0x000001F4 LE = [0xF4, 0x01, 0x00, 0x00]
        val tripTimeBytes = byteArrayOf(0xF4.toByte(), 0x01, 0x00, 0x00)

        var frame = byteArrayOf(ResponseCode.ACK.value.toByte())
        frame += ackCode
        frame += tripTimeBytes

        val event = PacketParser.parse(frame)
        val ack = event as? MeshEvent.Acknowledgement ?: run {
            fail("Expected Acknowledgement, got $event")
            return
        }
        assertArrayEquals(ackCode, ack.code)
        assertEquals(500u, ack.tripTime)
    }

    @Test
    fun `3-byte ACK payload produces parseFailure`() {
        var frame = byteArrayOf(ResponseCode.ACK.value.toByte())
        frame += byteArrayOf(0x01, 0x02, 0x03) // only 3 bytes, need 4
        assertTrue(PacketParser.parse(frame) is MeshEvent.ParseFailure)
    }

    @Test
    fun `5-7 byte ACK payload produces tripTime null (no partial read)`() {
        for (extraBytes in 1..3) {
            val ackCode = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
            var frame = byteArrayOf(ResponseCode.ACK.value.toByte())
            frame += ackCode
            frame += ByteArray(extraBytes) { 0xFF.toByte() }

            val event = PacketParser.parse(frame)
            val ack = event as? MeshEvent.Acknowledgement ?: run {
                fail("Expected Acknowledgement for ${4 + extraBytes}-byte payload, got $event")
                return
            }
            assertArrayEquals(ackCode, ack.code)
            assertNull("Payload of ${4 + extraBytes} bytes should not attempt partial trip time read", ack.tripTime)
        }
    }

    // MARK: - V112ProtocolTests.swift (ContactDeleted / ContactsFull / AutoAddConfig)

    @Test
    fun `contactDeleted response code exists and categorizes as push`() {
        assertEquals(ResponseCode.CONTACT_DELETED, ResponseCode.fromValue(0x8Fu))
        assertEquals(ResponseCategory.PUSH, ResponseCode.CONTACT_DELETED.category)
    }

    @Test
    fun `contactsFull response code exists and categorizes as push`() {
        assertEquals(ResponseCode.CONTACTS_FULL, ResponseCode.fromValue(0x90u))
        assertEquals(ResponseCategory.PUSH, ResponseCode.CONTACTS_FULL.category)
    }

    @Test
    fun `packetParser routes contactDeleted`() {
        var packet = byteArrayOf(0x8F.toByte())
        packet += ByteArray(32) { 0xEF.toByte() }

        val event = PacketParser.parse(packet)
        val publicKey = (event as? MeshEvent.ContactDeleted)?.publicKey ?: run {
            fail("Expected ContactDeleted event, got $event")
            return
        }
        assertArrayEquals(ByteArray(32) { 0xEF.toByte() }, publicKey)
    }

    @Test
    fun `packetParser routes contactsFull`() {
        val packet = byteArrayOf(0x90.toByte())
        assertEquals(MeshEvent.ContactsFull, PacketParser.parse(packet))
    }

    @Test
    fun `packetParser contactDeleted parse failure for short payload`() {
        var packet = byteArrayOf(0x8F.toByte())
        packet += ByteArray(20) { 0xAB.toByte() }

        val event = PacketParser.parse(packet)
        val failure = event as? MeshEvent.ParseFailure ?: run {
            fail("Expected ParseFailure event, got $event")
            return
        }
        assertTrue(failure.reason.contains("ContactDeleted too short"))
    }

    @Test
    fun `autoAddConfig response code exists and categorizes as device`() {
        assertEquals(ResponseCode.AUTO_ADD_CONFIG, ResponseCode.fromValue(0x19u))
        assertEquals(ResponseCategory.DEVICE, ResponseCode.AUTO_ADD_CONFIG.category)
    }

    @Test
    fun `autoAddConfig parses single-byte payload with default maxHops`() {
        val packet = byteArrayOf(0x19, 0x0F)
        val config = (PacketParser.parse(packet) as? MeshEvent.AutoAddConfigEvent)?.config ?: run {
            fail("Expected AutoAddConfigEvent")
            return
        }
        assertEquals(0x0Fu.toUByte(), config.bitmask)
        assertEquals(0u.toUByte(), config.maxHops)
    }

    @Test
    fun `autoAddConfig parses two-byte payload with maxHops`() {
        val packet = byteArrayOf(0x19, 0x0F, 0x05)
        val config = (PacketParser.parse(packet) as? MeshEvent.AutoAddConfigEvent)?.config ?: run {
            fail("Expected AutoAddConfigEvent")
            return
        }
        assertEquals(0x0Fu.toUByte(), config.bitmask)
        assertEquals(5u.toUByte(), config.maxHops)
    }

    @Test
    fun `autoAddConfig parse failure for empty payload`() {
        val packet = byteArrayOf(0x19)
        val failure = PacketParser.parse(packet) as? MeshEvent.ParseFailure ?: run {
            fail("Expected ParseFailure event")
            return
        }
        assertTrue(failure.reason.contains("AutoAddConfig response too short"))
    }

    @Test
    fun `autoAddConfig ignores extra bytes beyond maxHops`() {
        val packet = byteArrayOf(0x19, 0x0E, 0x03, 0xFF.toByte(), 0xFF.toByte())
        val config = (PacketParser.parse(packet) as? MeshEvent.AutoAddConfigEvent)?.config ?: run {
            fail("Expected AutoAddConfigEvent")
            return
        }
        assertEquals(0x0Eu.toUByte(), config.bitmask)
        assertEquals(3u.toUByte(), config.maxHops)
    }

    // MARK: - V115ParsingTests.swift ("routes via PacketParser" siblings)

    @Test
    fun `channelDatagram routes via PacketParser`() {
        val data = byteArrayOf(
            0x1B, // response code
            0x00, 0x00, 0x00, // snr + reserved
            0x00, // channel 0
            0x03, // path_len: flood-accumulated, 3 hops x 1-byte hashes
            0x12, 0x34, // data_type LE 0x3412
            0x02, // data_len
            0xAA.toByte(), 0xBB.toByte(),
        )

        val event = PacketParser.parse(data)
        val dg = (event as? MeshEvent.ChannelDataReceived)?.datagram ?: run {
            fail("Expected routed ChannelDataReceived, got $event")
            return
        }
        assertEquals(0x03u.toUByte(), dg.pathLength)
        assertEquals(0x3412u.toUShort(), dg.dataType)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), dg.data)
    }

    @Test
    fun `defaultFloodScope routes via PacketParser`() {
        var wire = byteArrayOf(0x1C)
        var nameBytes = "ch".toByteArray(Charsets.UTF_8)
        nameBytes = nameBytes.paddedOrTruncated(31)
        wire += nameBytes
        wire += ByteArray(16) { 0x01 }

        val event = PacketParser.parse(wire)
        val scope = (event as? MeshEvent.DefaultFloodScopeEvent)?.scope ?: run {
            fail("Expected routed DefaultFloodScopeEvent, got $event")
            return
        }
        assertEquals("ch", scope.name)
    }
}
