// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of MeshCoreSessionGetChannelsTests.swift — session-layer validation gates for the
 * windowed channel-read pipeline: correctness with gaps, no orphaned continuations,
 * drop-reconcile, the window/refill bound, and the capability fallback to serial reads.
 *
 * Unlike most of these ported files, this one relies on [startSession]'s cleared sent-data
 * history, so send counts below are one lower than Swift's (no appStart frame counted).
 */
class MeshCoreSessionGetChannelsTest {
    private fun pipelineConfig(window: Int = 8, idleTimeout: Double = 1.5): SessionConfiguration = SessionConfiguration(
        defaultTimeout = 10.0,
        clientIdentifier = "MCTst",
        channelPipelineWindow = window,
        channelPipelineIdleTimeout = idleTimeout,
        channelPipelineHardTimeout = 5.0,
        channelPipelinePostDrainGrace = 0.02,
    )

    // MARK: - Gate #1: correctness with gaps

    @Test
    fun `each requested index lands in its own slot and unrequested indexes are ignored`() = runBlocking {
        val transport = MockTransport()
        transport.setSupportsWriteWithoutResponse(true)
        val session = MeshCoreSession(transport, pipelineConfig())
        startSession(session, transport)

        val task = CoroutineScope(Dispatchers.Default).async { session.getChannels(listOf(0u, 2u, 7u)) }

        waitUntil { transport.sentData().size == 3 } // 3 channel reads (appStart already cleared)

        // Respond out of order, plus an unrequested index that must be ignored.
        transport.simulateReceive(makeChannelInfoPacket(7u, "seven", ByteArray(16) { 0x77 }))
        transport.simulateReceive(makeChannelInfoPacket(2u, "two", ByteArray(16) { 0x22 }))
        transport.simulateReceive(makeChannelInfoPacket(5u, "five", ByteArray(16) { 0x55 }))
        transport.simulateReceive(makeChannelInfoPacket(0u, "zero", ByteArray(16) { 0x00 }))

        val result = task.await()
        assertTrue(result.missing.isEmpty())
        assertEquals(listOf(0u, 2u, 7u).map { it.toUByte() }, result.received.map { it.index })
        assertEquals("zero", result.received.first { it.index == 0u.toUByte() }.name)
        assertEquals("two", result.received.first { it.index == 2u.toUByte() }.name)
        assertEquals("seven", result.received.first { it.index == 7u.toUByte() }.name)
        assertFalse(result.received.any { it.index == 5u.toUByte() })
        session.stop()
    }

    // MARK: - Gate #4: drop-reconcile (missing detection)

    @Test
    fun `dropped writes surface as missing indexes after the idle timeout`() = runBlocking {
        val transport = MockTransport()
        transport.setSupportsWriteWithoutResponse(true)
        val session = MeshCoreSession(transport, pipelineConfig(idleTimeout = 0.1))
        startSession(session, transport)

        val task = CoroutineScope(Dispatchers.Default).async { session.getChannels(listOf(0u, 1u, 2u, 3u)) }

        waitUntil { transport.sentData().size == 4 }

        // Index 2 is never answered (simulated dropped write).
        transport.simulateReceive(makeChannelInfoPacket(0u, "zero", ByteArray(16)))
        transport.simulateReceive(makeChannelInfoPacket(1u, "one", ByteArray(16)))
        transport.simulateReceive(makeChannelInfoPacket(3u, "three", ByteArray(16)))

        val result = task.await()
        assertEquals(listOf(0u, 1u, 3u).map { it.toUByte() }, result.received.map { it.index })
        assertEquals(listOf(2u.toUByte()), result.missing)
        session.stop()
    }

    // MARK: - Window / refill bound

    @Test
    fun `no more than the window is outstanding before the first response, then refills`() = runBlocking {
        val transport = MockTransport()
        transport.setSupportsWriteWithoutResponse(true)
        val session = MeshCoreSession(transport, pipelineConfig(window = 2))
        startSession(session, transport)

        val task = CoroutineScope(Dispatchers.Default).async { session.getChannels(listOf(0u, 1u, 2u, 3u)) }

        // Only the window (2) should be primed before any response arrives.
        waitUntil { transport.sentData().size == 2 }
        delay(50)
        assertEquals("must not exceed the window before a response", 2, transport.sentData().size)

        // Each response refills one more request.
        transport.simulateReceive(makeChannelInfoPacket(0u, "zero", ByteArray(16)))
        waitUntil { transport.sentData().size == 3 }
        transport.simulateReceive(makeChannelInfoPacket(1u, "one", ByteArray(16)))
        waitUntil { transport.sentData().size == 4 }
        transport.simulateReceive(makeChannelInfoPacket(2u, "two", ByteArray(16)))
        transport.simulateReceive(makeChannelInfoPacket(3u, "three", ByteArray(16)))

        val result = task.await()
        assertEquals(listOf(0u, 1u, 2u, 3u).map { it.toUByte() }, result.received.map { it.index })
        assertTrue(result.missing.isEmpty())
        assertEquals("exactly 4 channel reads were issued", 4, transport.sentData().size)
        session.stop()
    }

    // MARK: - Gate #3: no orphaned continuations

    @Test
    fun `a cancelled getChannels does not leak a late channelInfo into a same-type successor`() = runBlocking {
        val transport = MockTransport()
        transport.setSupportsWriteWithoutResponse(true)
        val session = MeshCoreSession(transport, pipelineConfig(idleTimeout = 0.1))
        startSession(session, transport)

        val channelsTask = CoroutineScope(Dispatchers.Default).async { session.getChannels(listOf(0u, 1u)) }
        waitUntil { transport.sentData().size == 2 }

        channelsTask.cancel()
        try {
            channelsTask.await()
        } catch (error: Throwable) {
            // expected: cancelled
        }

        // Orphan frame for the cancelled pipeline. The successor is getChannel(index: 0), whose
        // matcher accepts any channelInfo(index: 0) — the same response type the pipeline reads —
        // so a leaked orphan would surface as the successor's result.
        transport.simulateReceive(makeChannelInfoPacket(0u, "late", ByteArray(16) { 0xAA.toByte() }))

        val nextTask = CoroutineScope(Dispatchers.Default).async { session.getChannel(0u) }
        waitUntil { transport.sentData().size == 3 }
        transport.simulateReceive(makeChannelInfoPacket(0u, "fresh", ByteArray(16) { 0xBB.toByte() }))

        // The successor must see its own fresh response — the orphaned "late" frame was drained
        // under the pipeline's held serializer slot, not handed to the next command.
        val next = nextTask.await()
        assertEquals(0u.toUByte(), next.index)
        assertEquals("fresh", next.name)
        session.stop()
    }

    // MARK: - Capability fallback

    @Test
    fun `falls back to serial reads when the transport lacks write-without-response`() = runBlocking {
        val transport = MockTransport() // default: supportsWriteWithoutResponse == false
        val session = MeshCoreSession(transport, pipelineConfig())
        startSession(session, transport)

        val task = CoroutineScope(Dispatchers.Default).async { session.getChannels(listOf(0u, 1u)) }

        // Serial path issues one read, waits for its response, then the next.
        waitUntil { transport.sentData().size == 1 }
        transport.simulateReceive(makeChannelInfoPacket(0u, "zero", ByteArray(16)))
        waitUntil { transport.sentData().size == 2 }
        transport.simulateReceive(makeChannelInfoPacket(1u, "one", ByteArray(16)))

        val result = task.await()
        assertEquals(listOf(0u, 1u).map { it.toUByte() }, result.received.map { it.index })
        assertTrue(result.missing.isEmpty())
        session.stop()
    }
}
