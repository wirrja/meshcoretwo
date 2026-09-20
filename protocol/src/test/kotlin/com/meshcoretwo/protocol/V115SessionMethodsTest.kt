// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Port of V115SessionMethodsTests.swift.
 *
 * Unlike most of these ported files, this one relies on [startSession]'s cleared sent-data
 * history, so `sentData` indices/counts below are one lower than Swift's (no appStart frame).
 */
class V115SessionMethodsTest {
    @Test
    fun `sendChannelData emits correct frame and awaits OK`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val job = CoroutineScope(Dispatchers.Default).launch {
            session.sendChannelData(channelIndex = 1u, dataType = 0xFFFFu, payload = byteArrayOf(0x01, 0x02, 0x03))
        }
        waitUntil { transport.sentData().size == 1 }

        val sent = transport.sentData()[0]
        assertEquals(0x3E, sent[0].toInt() and 0xFF)
        assertEquals(0x01, sent[1].toInt() and 0xFF)
        assertEquals(0xFF, sent[2].toInt() and 0xFF)
        assertTrue("data_type LE", sent[3].toInt() and 0xFF == 0xFF && sent[4].toInt() and 0xFF == 0xFF)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), sent.copyOfRange(5, sent.size))

        transport.simulateOK()
        job.join()
        session.stop()
    }

    @Test
    fun `sendChannelData throws deviceError on error response`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val job = CoroutineScope(Dispatchers.Default).async {
            session.sendChannelData(channelIndex = 0u, dataType = 0x0001u, payload = byteArrayOf(0x42))
        }
        waitUntil { transport.sentData().size == 1 }
        transport.simulateError(2u)

        try {
            job.await()
            fail("expected deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(2u.toUByte(), error.code)
        }
        session.stop()
    }

    @Test
    fun `sendChannelData forwards pathLength and pathBytes to the builder`() = runBlocking {
        // Exercises the non-flood default arguments (Rev 4). The session layer is a thin
        // passthrough, so this asserts the wire bytes end up in the builder's 1-byte-hash
        // direct-path shape: [0x3E][ch][pathLen][pathBytes...][dataType LE][payload].
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val job = CoroutineScope(Dispatchers.Default).launch {
            session.sendChannelData(
                channelIndex = 0u,
                dataType = 0xFFFFu,
                payload = byteArrayOf(0x42),
                pathLength = 0x03u, // hash_size=1, hash_count=3
                pathBytes = byteArrayOf(0x11, 0x22, 0x33),
            )
        }
        waitUntil { transport.sentData().size == 1 }

        val sent = transport.sentData()[0]
        assertEquals(0x3E, sent[0].toInt() and 0xFF)
        assertEquals("channel 0", 0x00, sent[1].toInt() and 0xFF)
        assertEquals("pathLength verbatim", 0x03, sent[2].toInt() and 0xFF)
        assertArrayEquals("pathBytes follow pathLength", byteArrayOf(0x11, 0x22, 0x33), sent.copyOfRange(3, 6))
        assertTrue("data_type LE 0xFFFF", sent[6].toInt() and 0xFF == 0xFF && sent[7].toInt() and 0xFF == 0xFF)
        assertArrayEquals(byteArrayOf(0x42), sent.copyOfRange(8, sent.size))

        transport.simulateOK()
        job.join()
        session.stop()
    }

    @Test
    fun `setDefaultFloodScope with name and key`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val key = ByteArray(16) { 0x5A }
        val job = CoroutineScope(Dispatchers.Default).launch { session.setDefaultFloodScope("Europe", key) }
        waitUntil { transport.sentData().size == 1 }

        val sent = transport.sentData()[0]
        assertEquals(48, sent.size)
        assertEquals(0x3F, sent[0].toInt() and 0xFF)
        assertArrayEquals("Europe".toByteArray(Charsets.UTF_8), sent.copyOfRange(1, 7))
        assertArrayEquals(key, sent.copyOfRange(32, 48))

        transport.simulateOK()
        job.join()
        session.stop()
    }

    @Test
    fun `setDefaultFloodScope clears with empty args`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val job = CoroutineScope(Dispatchers.Default).launch { session.setDefaultFloodScope("", ByteArray(0)) }
        waitUntil { transport.sentData().size == 1 }
        assertArrayEquals("Clear command is single byte", byteArrayOf(0x3F), transport.sentData()[0])

        transport.simulateOK()
        job.join()
        session.stop()
    }

    @Test
    fun `setDefaultFloodScope FloodScope overload — disabled clears`() = runBlocking {
        // Exercises the (name, scope) overload's Disabled short-circuit, which the raw-key
        // test can't reach. Regardless of the `name` argument, passing Disabled must emit the
        // single-byte clear frame.
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val job = CoroutineScope(Dispatchers.Default).launch { session.setDefaultFloodScope("ignored", FloodScope.Disabled) }
        waitUntil { transport.sentData().size == 1 }
        assertArrayEquals("Disabled scope must short-circuit to clear frame", byteArrayOf(0x3F), transport.sentData()[0])

        transport.simulateOK()
        job.join()
        session.stop()
    }

    @Test
    fun `setDefaultFloodScope FloodScope overload — channelName derives key`() = runBlocking {
        // Verifies the overload derives a 16-byte key from a FloodScope case and emits the
        // 48-byte set frame. The exact derived key is covered by FloodScope.scopeKey() tests;
        // this only asserts the wire format is the 48-byte set form.
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val scope = FloodScope.ChannelName("public")
        val job = CoroutineScope(Dispatchers.Default).launch { session.setDefaultFloodScope("pub", scope) }
        waitUntil { transport.sentData().size == 1 }

        val sent = transport.sentData()[0]
        assertEquals(48, sent.size)
        assertEquals(0x3F, sent[0].toInt() and 0xFF)
        assertArrayEquals("pub".toByteArray(Charsets.UTF_8), sent.copyOfRange(1, 4))
        assertArrayEquals("Overload must derive key via FloodScope.scopeKey()", scope.scopeKey(), sent.copyOfRange(32, 48))

        transport.simulateOK()
        job.join()
        session.stop()
    }

    @Test
    fun `getDefaultFloodScope returns populated scope`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val job = CoroutineScope(Dispatchers.Default).async { session.getDefaultFloodScope() }
        waitUntil { transport.sentData().size == 1 }

        var wire = byteArrayOf(0x1C)
        val nameBytes = "NA".toByteArray(Charsets.UTF_8).paddedOrTruncated(31)
        wire += nameBytes
        wire += ByteArray(16) { 0xC3.toByte() }
        transport.simulateReceive(wire)

        val result = job.await()
        assertEquals("NA", result?.name)
        assertArrayEquals(ByteArray(16) { 0xC3.toByte() }, result!!.scopeKey)

        session.stop()
    }

    @Test
    fun `getDefaultFloodScope returns null when empty`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val job = CoroutineScope(Dispatchers.Default).async { session.getDefaultFloodScope() }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateReceive(byteArrayOf(0x1C))

        assertNull(job.await())
        session.stop()
    }

    @Test
    fun `getDefaultFloodScope propagates device errors`() = runBlocking {
        // Firmware pre-v11 doesn't recognise opcode 0x40 and falls through to the catch-all
        // branch in MyMesh.cpp, which returns ERR_CODE_UNSUPPORTED_CMD = 1. The session method
        // must surface this as a deviceError, not silently return null — otherwise callers
        // can't distinguish "no scope configured" from "firmware too old".
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val job = CoroutineScope(Dispatchers.Default).async { session.getDefaultFloodScope() }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateError(1u) // ERR_CODE_UNSUPPORTED_CMD

        try {
            job.await()
            fail("expected deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(1u.toUByte(), error.code)
        }
        session.stop()
    }

    @Test
    fun `sendTrace throws invalidInput when path is null`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))

        try {
            session.sendTrace()
            fail("expected invalidInput")
        } catch (error: MeshCoreError.InvalidInput) {
            // expected
        }
        assertEquals("Guard must fail before any frame is sent", 0, transport.sentData().size)
    }

    @Test
    fun `sendTrace throws invalidInput when path is empty`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))

        try {
            session.sendTrace(path = ByteArray(0))
            fail("expected invalidInput")
        } catch (error: MeshCoreError.InvalidInput) {
            // expected
        }
        assertEquals("Guard must fail before any frame is sent", 0, transport.sentData().size)
    }
}
