// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * Port of the Contact round-trip cases from RoundTripTests.swift and the whole of
 * ContactNameDecodingTests.swift (exercises parseContactData's UTF-8 handling directly).
 */
class ContactsParsersTest {
    @Test
    fun `Contact round trip`() {
        val publicKey = ByteArray(32) { 0xAA.toByte() }
        val type: UByte = 1u
        val flags: UByte = 0x02u
        val pathLen: UByte = 3u
        val pathBytes = byteArrayOf(0x11, 0x22, 0x33) + ByteArray(61)
        val namePadded = "TestContact".toByteArray(Charsets.UTF_8).paddedOrTruncated(32)
        val lastAdvert = 1_704_067_200u
        val lat = 37_774_900 // 37.7749 * 1e6
        val lon = -122_419_400 // -122.4194 * 1e6
        val lastMod = 1_704_067_200u

        var data = publicKey.copyOf()
        data += type.toByte()
        data += flags.toByte()
        data += pathLen.toByte()
        data += pathBytes
        data += namePadded
        data += lastAdvert.toLittleEndianBytes()
        data += lat.toLittleEndianBytes()
        data += lon.toLittleEndianBytes()
        data += lastMod.toLittleEndianBytes()

        assertEquals(147, data.size)

        val event = ContactParser.parse(data)
        val contact = (event as? MeshEvent.Contact)?.contact ?: run {
            fail("Expected Contact event, got $event")
            return
        }

        assertArrayEquals(publicKey, contact.publicKey)
        assertEquals(ContactType.fromValue(type), contact.type)
        assertEquals(ContactFlags(flags), contact.flags)
        assertEquals(pathLen, contact.outPathLength)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), contact.outPath)
        assertEquals("TestContact", contact.advertisedName)
        assertTrue(abs(contact.latitude - 37.7749) <= 0.0001)
        assertTrue(abs(contact.longitude - -122.4194) <= 0.0001)
    }

    @Test
    fun `Contact rejects reserved path length encoding`() {
        val data = ByteArray(147)
        for (i in 0 until 32) data[i] = 0xAA.toByte()
        data[32] = 1
        data[33] = 0
        data[34] = 0xC1.toByte() // mode 3 (reserved), hop count 1

        val event = ContactParser.parse(data)
        val failure = event as? MeshEvent.ParseFailure ?: run {
            fail("Expected ParseFailure event, got $event")
            return
        }
        assertTrue(failure.reason.contains("reserved path length encoding"))
    }

    // MARK: - Contact name decoding (ContactNameDecodingTests.swift)

    /**
     * Builds a 147-byte contact body in the shape [parseContactData] consumes: the 32-byte name
     * slot holds [nameField] (null-padded to 32 bytes), and pathLen is flood (0xFF) so the
     * parser's path-length guard passes.
     */
    private fun contactBody(nameField: ByteArray): ByteArray {
        var data = ByteArray(32) { 0xAA.toByte() } // publicKey
        data += ContactType.CHAT.value.toByte() // type
        data += 0x00 // flags
        data += 0xFF.toByte() // pathLen (flood)
        data += ByteArray(64) // path
        data += nameField.paddedOrTruncated(32)
        data += 0u.toLittleEndianBytes() // lastAdvert
        data += 0.toLittleEndianBytes() // lat
        data += 0.toLittleEndianBytes() // lon
        data += 0u.toLittleEndianBytes() // lastMod
        return data
    }

    @Test
    fun `Flag emoji split at the name boundary decodes to a readable prefix`() {
        // 28 ASCII bytes + the first three bytes of a flag's lead scalar (F0 9F 87): the
        // byte-wise truncation an 8-byte flag leaves at the buffer boundary (invalid UTF-8).
        var nameField = ByteArray(28) { 0x78 }
        nameField += byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x87.toByte())
        nameField += 0x00

        val contact = parseContactData(contactBody(nameField))!!
        assertEquals("x".repeat(28), contact.advertisedName)
    }

    @Test
    fun `Lone regional indicator is kept when only half a flag survives`() {
        // 27 ASCII bytes + a flag's complete first regional indicator scalar (F0 9F 87 BA):
        // valid UTF-8 alone, so the prefix keeps it as a dangling indicator (the pinned contract).
        var nameField = ByteArray(27) { 0x78 }
        nameField += byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x87.toByte(), 0xBA.toByte())
        nameField += 0x00

        val contact = parseContactData(contactBody(nameField))!!
        assertEquals("x".repeat(27) + "🇺", contact.advertisedName)
    }

    @Test
    fun `A flag name that fits the buffer decodes unchanged`() {
        val nameField = "Team 🇺🇸".toByteArray(Charsets.UTF_8) + byteArrayOf(0x00) // "Team flag-US"
        val contact = parseContactData(contactBody(nameField))!!
        assertEquals("Team 🇺🇸", contact.advertisedName)
    }

    @Test
    fun `Plain ASCII name still decodes`() {
        val nameField = "TestNode".toByteArray(Charsets.UTF_8) + byteArrayOf(0x00)
        val contact = parseContactData(contactBody(nameField))!!
        assertEquals("TestNode", contact.advertisedName)
    }
}
