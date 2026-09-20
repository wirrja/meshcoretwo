// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

class PacketBuilderTest {
    // MARK: - setRadio

    @Test
    fun `setRadio with repeat appends byte`() {
        val packet = PacketBuilder.setRadio(
            frequency = 915.0,
            bandwidth = 250.0,
            spreadingFactor = 11u,
            codingRate = 8u,
            clientRepeat = true,
        )
        // cmd(1) + freq(4) + bw(4) + sf(1) + cr(1) + repeat(1) = 12
        assertEquals(12, packet.size)
        assertEquals(1, packet.last().toInt())
    }

    @Test
    fun `setRadio without repeat no extra byte`() {
        val packet = PacketBuilder.setRadio(
            frequency = 915.0,
            bandwidth = 250.0,
            spreadingFactor = 11u,
            codingRate = 8u,
        )
        // cmd(1) + freq(4) + bw(4) + sf(1) + cr(1) = 11
        assertEquals(11, packet.size)
    }

    @Test
    fun `setRadio rounds frequency to the nearest kHz`() {
        val packet = PacketBuilder.setRadio(
            frequency = 512.002,
            bandwidth = 250.0,
            spreadingFactor = 11u,
            codingRate = 8u,
        )
        val freqKHz = packet.readUInt32LE(1)
        // Truncation would yield 512001; rounding restores the representable 512002.
        assertEquals(512_002u, freqKHz)
    }

    @Test
    fun `setRadio clamps out-of-range frequency and bandwidth instead of throwing`() {
        val high = PacketBuilder.setRadio(
            frequency = 9_999_999.0,
            bandwidth = 9_999_999.0,
            spreadingFactor = 11u,
            codingRate = 8u,
        )
        assertEquals(PacketBuilder.FREQUENCY_RANGE_KHZ.last, high.readUInt32LE(1))
        assertEquals(PacketBuilder.BANDWIDTH_RANGE_HZ.last, high.readUInt32LE(5))

        val low = PacketBuilder.setRadio(
            frequency = -1.0,
            bandwidth = Double.NaN,
            spreadingFactor = 11u,
            codingRate = 8u,
        )
        assertEquals(PacketBuilder.FREQUENCY_RANGE_KHZ.first, low.readUInt32LE(1))
        assertEquals(PacketBuilder.BANDWIDTH_RANGE_HZ.first, low.readUInt32LE(5))
    }

    // MARK: - setTime

    @Test
    fun `setTime saturates out-of-range dates instead of throwing`() {
        val preEpoch = PacketBuilder.setTime(Instant.ofEpochSecond(-1000))
        assertEquals(0u, preEpoch.readUInt32LE(1))

        val postRange = PacketBuilder.setTime(Instant.ofEpochSecond(UInt.MAX_VALUE.toLong() + 1000))
        assertEquals(UInt.MAX_VALUE, postRange.readUInt32LE(1))
    }

    // MARK: - setTxPower

    @Test
    fun `setTxPower positive power packet format`() {
        val packet = PacketBuilder.setTxPower(20)
        assertArrayEquals(byteArrayOf(0x0C, 0x14), packet)
    }

    @Test
    fun `setTxPower negative power packet format`() {
        val packet = PacketBuilder.setTxPower(-5)
        assertArrayEquals(byteArrayOf(0x0C, 0xFB.toByte()), packet)
    }

    @Test
    fun `getRepeatFreq packet format`() {
        assertArrayEquals(byteArrayOf(0x3C), PacketBuilder.getRepeatFreq())
    }

    // MARK: - factoryReset

    @Test
    fun `factoryReset includes guard string`() {
        val packet = PacketBuilder.factoryReset()
        assertEquals(6, packet.size)
        assertEquals(0x33, packet[0].toInt())
        assertEquals("reset", String(packet.copyOfRange(1, packet.size), Charsets.UTF_8))
    }

    @Test
    fun `factoryReset exact bytes`() {
        val expected = byteArrayOf(0x33, 0x72, 0x65, 0x73, 0x65, 0x74) // 0x33 + "reset"
        assertArrayEquals(expected, PacketBuilder.factoryReset())
    }

    // MARK: - sendRawData

    @Test
    fun `sendRawData format`() {
        val path = byteArrayOf(0x11, 0x22)
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())

        val packet = PacketBuilder.sendRawData(path, payload)

        assertEquals(0x19, packet[0].toInt())
        assertEquals(0x02, packet[1].toInt())
        assertArrayEquals(path, packet.copyOfRange(2, 4))
        assertArrayEquals(payload, packet.copyOfRange(4, packet.size))
    }

    @Test
    fun `sendRawData empty path`() {
        val packet = PacketBuilder.sendRawData(ByteArray(0), byteArrayOf(0xAA.toByte()))
        assertEquals(0x00, packet[1].toInt())
        assertEquals(3, packet.size) // command + pathLen + payload
    }

    @Test
    fun `sendRawData clamps to firmware limits`() {
        val path = ByteArray(80) { 0x11 }
        val payload = ByteArray(220) { 0x22 }

        val packet = PacketBuilder.sendRawData(path, payload)

        assertEquals(64, packet[1].toInt() and 0xFF)
        assertArrayEquals(ByteArray(64) { 0x11 }, packet.copyOfRange(2, 66))
        assertArrayEquals(ByteArray(184) { 0x22 }, packet.copyOfRange(66, packet.size))
    }

    // MARK: - hasConnection / getContactByKey / getAdvertPath

    @Test
    fun `hasConnection format`() {
        val pubkey = ByteArray(32) { 0xAA.toByte() }
        val packet = PacketBuilder.hasConnection(pubkey)
        assertEquals(33, packet.size)
        assertEquals(0x1C, packet[0].toInt())
        assertArrayEquals(pubkey, packet.copyOfRange(1, packet.size))
    }

    @Test
    fun `hasConnection pads short public key to protocol width`() {
        val pubkey = ByteArray(31) { 0xAA.toByte() }
        val packet = PacketBuilder.hasConnection(pubkey)
        assertEquals(33, packet.size)
        assertArrayEquals(pubkey, packet.copyOfRange(1, 32))
        assertEquals(0x00, packet[32].toInt())
    }

    @Test
    fun `getContactByKey format`() {
        val pubkey = ByteArray(32) { 0xBB.toByte() }
        val packet = PacketBuilder.getContactByKey(pubkey)
        assertEquals(33, packet.size)
        assertEquals(0x1E, packet[0].toInt())
        assertArrayEquals(pubkey, packet.copyOfRange(1, packet.size))
    }

    @Test
    fun `getContactByKey pads short public key to protocol width`() {
        val pubkey = ByteArray(31) { 0xBB.toByte() }
        val packet = PacketBuilder.getContactByKey(pubkey)
        assertEquals(33, packet.size)
        assertArrayEquals(pubkey, packet.copyOfRange(1, 32))
        assertEquals(0x00, packet[32].toInt())
    }

    @Test
    fun `getAdvertPath format`() {
        val pubkey = ByteArray(32) { 0xCC.toByte() }
        val packet = PacketBuilder.getAdvertPath(pubkey)
        assertEquals(34, packet.size)
        assertEquals(0x2A, packet[0].toInt())
        assertEquals(0x00, packet[1].toInt())
        assertArrayEquals(pubkey, packet.copyOfRange(2, packet.size))
    }

    @Test
    fun `getAdvertPath pads short public key to protocol width`() {
        val pubkey = ByteArray(31) { 0xCC.toByte() }
        val packet = PacketBuilder.getAdvertPath(pubkey)
        assertEquals(34, packet.size)
        assertArrayEquals(pubkey, packet.copyOfRange(2, 33))
        assertEquals(0x00, packet[33].toInt())
    }

    @Test
    fun `getTuningParams format`() {
        val packet = PacketBuilder.getTuningParams()
        assertEquals(1, packet.size)
        assertEquals(0x2B, packet[0].toInt())
    }

    // MARK: - setPathHashMode

    @Test
    fun `setPathHashMode encodes mode 0, 1, 2 verbatim`() {
        for (mode in listOf<UByte>(0u, 1u, 2u)) {
            val packet = PacketBuilder.setPathHashMode(mode)
            assertEquals(3, packet.size)
            assertEquals(0x3D, packet[0].toInt())
            assertEquals(0x00, packet[1].toInt())
            assertEquals(mode.toInt(), packet[2].toInt())
        }
    }

    @Test
    fun `setPathHashMode clamps reserved values to max supported mode`() {
        val packet = PacketBuilder.setPathHashMode(3u)
        assertEquals(3, packet.size)
        assertEquals(2, packet[2].toInt())
    }

    // MARK: - updateContact

    private fun testContact(
        type: ContactType = ContactType.CHAT,
        flags: ContactFlags = ContactFlags.NONE,
        outPathLength: UByte = 0u,
        outPath: ByteArray = ByteArray(0),
        advertisedName: String = "",
        lastAdvertisement: Instant = Instant.EPOCH,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
    ) = MeshContact(
        id = "test",
        publicKey = ByteArray(32) { 0xAA.toByte() },
        type = type,
        flags = flags,
        outPathLength = outPathLength,
        outPath = outPath,
        advertisedName = advertisedName,
        lastAdvertisement = lastAdvertisement,
        latitude = latitude,
        longitude = longitude,
        lastModified = Instant.now(),
    )

    @Test
    fun `updateContact produces 147 bytes`() {
        val contact = testContact(
            flags = ContactFlags(0x02u),
            outPathLength = 3u,
            outPath = byteArrayOf(0x11, 0x22, 0x33),
            advertisedName = "TestNode",
            lastAdvertisement = Instant.ofEpochSecond(1_704_067_200),
            latitude = 37.7749,
            longitude = -122.4194,
        )
        val packet = PacketBuilder.updateContact(contact)
        assertEquals(147, packet.size)
    }

    @Test
    fun `updateContact correct layout`() {
        val pubkey = ByteArray(32) { 0xAA.toByte() }
        val outPath = byteArrayOf(0x11, 0x22, 0x33)
        val contact = MeshContact(
            id = "test",
            publicKey = pubkey,
            type = ContactType.ROOM,
            flags = ContactFlags(0x03u),
            outPathLength = 3u,
            outPath = outPath,
            advertisedName = "Node",
            lastAdvertisement = Instant.ofEpochSecond(1000),
            latitude = 10.0,
            longitude = -20.0,
            lastModified = Instant.now(),
        )

        val packet = PacketBuilder.updateContact(contact)

        assertEquals(0x09, packet[0].toInt())
        assertArrayEquals(pubkey, packet.copyOfRange(1, 33))
        assertEquals(ContactType.ROOM.value.toInt(), packet[33].toInt() and 0xFF)
        assertEquals(0x03, packet[34].toInt())
        assertEquals(0x03, packet[35].toInt())

        // Bytes 36-99: outPath (64 bytes, padded)
        assertArrayEquals(outPath, packet.copyOfRange(36, 39))
        assertEquals(0x00, packet[39].toInt())

        // Bytes 100-131: name (32 bytes, padded)
        val name = String(packet.copyOfRange(100, 104), Charsets.UTF_8)
        assertEquals("Node", name)
        assertEquals(0x00, packet[104].toInt())

        // Bytes 132-135: lastAdvertTimestamp (UInt32 LE)
        assertEquals(1000u, packet.readUInt32LE(132))

        // Bytes 136-139 / 140-143: latitude/longitude (Int32 LE, scaled by 1M)
        assertEquals(10_000_000, packet.readInt32LE(136))
        assertEquals(-20_000_000, packet.readInt32LE(140))
    }

    @Test
    fun `updateContact signed path length 0xFF is flood routing`() {
        val contact = testContact(outPathLength = 0xFFu)
        val packet = PacketBuilder.updateContact(contact)
        assertEquals(0xFF, packet[35].toInt() and 0xFF)
    }

    // MARK: - FloodScope / setFloodScope

    @Test
    fun `disabled scope key differs from any region scope key`() {
        val disabled = FloodScope.Disabled.scopeKey()
        val region = FloodScope.Region("Europe").scopeKey()
        assertNotEquals(true, disabled.contentEquals(region))
    }

    @Test
    fun `different region names produce different scope keys`() {
        val europe = FloodScope.Region("Europe").scopeKey()
        val uk = FloodScope.Region("UK").scopeKey()
        assertNotEquals(true, europe.contentEquals(uk))
    }

    @Test
    fun `setFloodScopeUnscoped emits sub-command 1 with no scope key`() {
        val data = PacketBuilder.setFloodScopeUnscoped()
        assertArrayEquals(byteArrayOf(0x36, 0x01), data)
    }

    @Test
    fun `setFloodScope (sub-command 0) differs from the unscoped override`() {
        val zeroKey = PacketBuilder.setFloodScope(FloodScope.Disabled.scopeKey())
        val unscoped = PacketBuilder.setFloodScopeUnscoped()
        assertNotEquals(true, zeroKey.contentEquals(unscoped))
        assertEquals(0x00, zeroKey[1].toInt())
        assertEquals(0x01, unscoped[1].toInt())
    }

    // MARK: - sendChannelData

    @Test
    fun `sendChannelData flood (default pathLength) format`() {
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val packet = PacketBuilder.sendChannelData(channelIndex = 2u, dataType = 0xFFFFu, payload = payload)

        assertEquals(0x3E, packet[0].toInt())
        assertEquals(0x02, packet[1].toInt())
        assertEquals(0xFF, packet[2].toInt() and 0xFF)
        assertEquals(0xFF, packet[3].toInt() and 0xFF)
        assertEquals(0xFF, packet[4].toInt() and 0xFF)
        assertArrayEquals(payload, packet.copyOfRange(5, packet.size))
        assertEquals(5 + payload.size, packet.size)
    }

    @Test
    fun `sendChannelData flood ignores pathBytes when pathLength is 0xFF`() {
        val packet = PacketBuilder.sendChannelData(
            channelIndex = 0u,
            dataType = 0xFFFFu,
            payload = byteArrayOf(0xAA.toByte()),
            pathLength = 0xFFu,
            pathBytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
        )
        assertEquals(6, packet.size) // 5-byte header (flood) + 1-byte payload; pathBytes dropped
        assertEquals(0xFF, packet[2].toInt() and 0xFF)
    }

    @Test
    fun `sendChannelData direct-path format (1-byte hashes)`() {
        val pathBytes = byteArrayOf(0x11, 0x22, 0x33)
        val payload = byteArrayOf(0x01, 0x02)
        val packet = PacketBuilder.sendChannelData(
            channelIndex = 0u,
            dataType = 0x1234u,
            payload = payload,
            pathLength = 0x03u,
            pathBytes = pathBytes,
        )

        assertEquals(0x3E, packet[0].toInt())
        assertEquals(0x00, packet[1].toInt())
        assertEquals(0x03, packet[2].toInt())
        assertArrayEquals(pathBytes, packet.copyOfRange(3, 6))
        assertEquals(0x34, packet[6].toInt())
        assertEquals(0x12, packet[7].toInt())
        assertArrayEquals(payload, packet.copyOfRange(8, packet.size))
        assertEquals(8 + payload.size, packet.size)
    }

    @Test
    fun `sendChannelData clamps payload to 163 bytes`() {
        val payload = ByteArray(200) { 0x55 }
        val packet = PacketBuilder.sendChannelData(channelIndex = 1u, dataType = 0x00FFu, payload = payload)

        assertEquals(5 + PacketBuilder.CHANNEL_DATA_MAX_PAYLOAD_BYTES, packet.size)
        assertArrayEquals(
            ByteArray(PacketBuilder.CHANNEL_DATA_MAX_PAYLOAD_BYTES) { 0x55 },
            packet.copyOfRange(5, packet.size),
        )
    }

    // MARK: - setDefaultFloodScope

    @Test
    fun `setDefaultFloodScope set format`() {
        val key = ByteArray(16) { 0xAB.toByte() }
        val packet = PacketBuilder.setDefaultFloodScope(name = "test", scopeKey = key)

        assertEquals(1 + 31 + 16, packet.size)
        assertEquals(0x3F, packet[0].toInt())
        assertArrayEquals("test".toByteArray(Charsets.UTF_8), packet.copyOfRange(1, 5))
        assertEquals(0, packet[5].toInt())
        assertArrayEquals(key, packet.copyOfRange(32, 48))
    }

    @Test
    fun `setDefaultFloodScope clear format`() {
        val packet = PacketBuilder.setDefaultFloodScope(name = "", scopeKey = ByteArray(0))
        assertEquals(1, packet.size)
        assertEquals(0x3F, packet[0].toInt())
    }

    @Test
    fun `setDefaultFloodScope truncates long name to 30 bytes`() {
        val longName = "A".repeat(50)
        val key = ByteArray(16) { 0xCD.toByte() }
        val packet = PacketBuilder.setDefaultFloodScope(name = longName, scopeKey = key)

        assertEquals(1 + 31 + 16, packet.size)
        assertArrayEquals(ByteArray(30) { 'A'.code.toByte() }, packet.copyOfRange(1, 31))
        assertEquals(0, packet[31].toInt())
    }

    @Test
    fun `setDefaultFloodScope pads short key to 16 bytes`() {
        val shortKey = byteArrayOf(0x01, 0x02, 0x03)
        val packet = PacketBuilder.setDefaultFloodScope(name = "x", scopeKey = shortKey)

        assertEquals(1 + 31 + 16, packet.size)
        assertArrayEquals(shortKey, packet.copyOfRange(32, 35))
        assertEquals(0, packet[35].toInt())
    }

    @Test
    fun `setDefaultFloodScope treats empty name as clear regardless of key`() {
        val packet = PacketBuilder.setDefaultFloodScope(name = "", scopeKey = ByteArray(16) { 0xAA.toByte() })
        assertArrayEquals(byteArrayOf(0x3F), packet)
    }

    @Test
    fun `setDefaultFloodScope preserves codepoint boundary when truncating`() {
        // rocket emoji is 4 UTF-8 bytes. 27 'A's + rocket = 31 bytes encoded; budget is 30
        // bytes (reserving byte 30 as the null terminator). Builder must drop the rocket
        // entirely rather than split it into invalid UTF-8.
        val name = "A".repeat(27) + "🚀"
        val key = ByteArray(16) { 0x01 }
        val packet = PacketBuilder.setDefaultFloodScope(name = name, scopeKey = key)

        val nameField = packet.copyOfRange(1, 32)
        val expected = ByteArray(27) { 'A'.code.toByte() } + ByteArray(4)
        assertArrayEquals(expected, nameField)
    }

    @Test
    fun `setDefaultFloodScope with Disabled scope clears`() {
        val packet = PacketBuilder.setDefaultFloodScope(name = "ignored", scope = FloodScope.Disabled)
        assertArrayEquals(byteArrayOf(0x3F), packet)
    }

    @Test
    fun `getDefaultFloodScope format`() {
        val packet = PacketBuilder.getDefaultFloodScope()
        assertEquals(1, packet.size)
        assertEquals(0x40, packet[0].toInt())
    }

    // MARK: - setOtherParams (no isolated Swift unit test; direct bit-packing sanity check)

    @Test
    fun `setOtherParams packs telemetry modes into a single byte`() {
        val packet = PacketBuilder.setOtherParams(
            manualAddContacts = true,
            telemetryModeEnvironment = 0b10u,
            telemetryModeLocation = 0b01u,
            telemetryModeBase = 0b11u,
            advertisementLocationPolicy = 5u,
            multiAcks = 1u,
        )
        assertEquals(5, packet.size)
        assertEquals(0x26, packet[0].toInt())
        assertEquals(1, packet[1].toInt()) // manualAddContacts
        // env(2)<<4 | loc(2)<<2 | base(2) = 0b10_01_11 = 0x27
        assertEquals(0b10_01_11, packet[2].toInt())
        assertEquals(5, packet[3].toInt())
        assertEquals(1, packet[4].toInt())
    }

    @Test
    fun `setOtherParams omits multiAcks byte when null`() {
        val packet = PacketBuilder.setOtherParams(
            manualAddContacts = false,
            telemetryModeEnvironment = 0u,
            telemetryModeLocation = 0u,
            telemetryModeBase = 0u,
            advertisementLocationPolicy = 0u,
        )
        assertEquals(4, packet.size)
    }
}
