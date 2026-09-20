// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Port of the Channels parser cases from RoundTripTests.swift and the DefaultFloodScope-parser
 * cases from V115ParsingTests.swift. The "routes via PacketParser" sibling cases are covered
 * once PacketParser.kt lands (PacketParserTest.kt).
 */
class ChannelParsersTest {
    @Test
    fun `ChannelInfo round trip`() {
        val index: UByte = 1u
        val nameBytes = "TestChannel".toByteArray(Charsets.UTF_8)
        val namePadded = nameBytes.paddedOrTruncated(32)
        val secret = ByteArray(16) { it.toByte() }

        var data = byteArrayOf(index.toByte())
        data += namePadded
        data += secret

        val event = ChannelInfoParser.parse(data)
        val info = (event as? MeshEvent.ChannelInfoEvent)?.info ?: run {
            fail("Expected ChannelInfoEvent, got $event")
            return
        }

        assertEquals(index, info.index)
        assertEquals("TestChannel", info.name)
        assertArrayEquals(secret, info.secret)
    }

    @Test
    fun `ChannelInfo handles garbage bytes after null`() {
        // Firmware uses strcpy which leaves uninitialized garbage after the null terminator.
        val index: UByte = 2u
        var namePadded = "Primary".toByteArray(Charsets.UTF_8)
        namePadded += 0 // Null terminator
        // Garbage bytes (invalid UTF-8 sequences) simulating uninitialized memory
        namePadded += byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0x81.toByte(), 0xC0.toByte(), 0xC1.toByte())
        namePadded += ByteArray(32 - namePadded.size) { 0xAB.toByte() }
        val secret = ByteArray(16) { 0xCC.toByte() }

        var data = byteArrayOf(index.toByte())
        data += namePadded
        data += secret

        val event = ChannelInfoParser.parse(data)
        val info = (event as? MeshEvent.ChannelInfoEvent)?.info ?: run {
            fail("Expected ChannelInfoEvent, got $event")
            return
        }

        assertEquals(index, info.index)
        assertEquals("Primary", info.name)
        assertArrayEquals(secret, info.secret)
    }

    @Test
    fun `ChannelInfo lossy decodes invalid UTF-8 before null`() {
        val index: UByte = 3u
        val rawName = byteArrayOf(0x50, 0x72, 0x69, 0xFF.toByte(), 0x6D, 0x61, 0x72, 0x79)
        var namePadded = rawName + byteArrayOf(0)
        namePadded += ByteArray(32 - namePadded.size)
        val secret = ByteArray(16) { 0x55 }

        var data = byteArrayOf(index.toByte())
        data += namePadded
        data += secret

        val event = ChannelInfoParser.parse(data)
        val info = (event as? MeshEvent.ChannelInfoEvent)?.info ?: run {
            fail("Expected ChannelInfoEvent, got $event")
            return
        }

        val expectedName = String(rawName, Charsets.UTF_8)
        assertEquals(index, info.index)
        assertEquals(expectedName, info.name)
        assertFalse(info.name.isEmpty())
        assertArrayEquals(secret, info.secret)
    }

    @Test
    fun `defaultFloodScope parses empty payload as null`() {
        val event = DefaultFloodScopeParser.parse(ByteArray(0))
        val scope = (event as? MeshEvent.DefaultFloodScopeEvent)?.scope
        assertTrue(event is MeshEvent.DefaultFloodScopeEvent)
        assertNull(scope)
    }

    @Test
    fun `defaultFloodScope parses populated payload`() {
        var nameBytes = "Europe".toByteArray(Charsets.UTF_8)
        nameBytes = nameBytes.paddedOrTruncated(31)
        val payload = nameBytes + ByteArray(16) { 0x7E }

        val event = DefaultFloodScopeParser.parse(payload)
        val scope = (event as? MeshEvent.DefaultFloodScopeEvent)?.scope ?: run {
            fail("Expected populated DefaultFloodScopeEvent, got $event")
            return
        }

        assertEquals("Europe", scope.name)
        assertArrayEquals(ByteArray(16) { 0x7E }, scope.scopeKey)
    }

    @Test
    fun `defaultFloodScope rejects partial payload`() {
        val event = DefaultFloodScopeParser.parse(ByteArray(20))
        assertTrue("Expected ParseFailure for short payload, got $event", event is MeshEvent.ParseFailure)
    }
}
