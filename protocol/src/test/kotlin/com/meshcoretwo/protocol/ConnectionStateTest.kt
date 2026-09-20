// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Port of ConnectionStateTests.swift.
 *
 * Swift reads [MeshCoreSession.connectionState] through a manual `AsyncIterator`, which
 * losslessly buffers every published value for its one subscriber, so it can assert on an
 * exact `.connecting, .connected` sequence. Kotlin's [MutableStateFlow] is conflated instead:
 * `start()`'s `Connecting`→`Connected` writes are two plain `.value =` assignments with no
 * *guaranteed* suspension in between (Kotlin's `Mutex.withLock` takes an uncontended lock on
 * its fast path without actually yielding to the dispatcher), so a collector on another thread
 * can legitimately never observe `Connecting` at all — it only ever sees the latest value.
 * Asserting an exact 3-element sequence here previously deadlocked a `take(3)` collector waiting
 * on a fourth-ever transition that conflation had already collapsed away. These tests instead
 * check the durable facts: the initial state is `Disconnected` (read directly, not collected),
 * the state after `start()` completes is `Connected`, and an intermediate state, if the
 * background collector happened to catch one, is `Connecting`/`Reconnecting` and never anything
 * else — never a hard requirement that it was seen at all.
 */
class ConnectionStateTest {
    @Test
    fun `initial start emits connecting then connected`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test"))
        assertEquals(ConnectionState.Disconnected, session.connectionState.first())

        val observedStates = CopyOnWriteArrayList<ConnectionState>()
        val observerJob = CoroutineScope(Dispatchers.Default).launch {
            session.connectionState.collect { observedStates.add(it) }
        }

        val startJob = CoroutineScope(Dispatchers.Default).launch { session.start() }
        waitUntil { transport.sentData().isNotEmpty() }
        transport.simulateReceive(makeSelfInfoPacket())
        startJob.join()

        assertEquals(ConnectionState.Connected, session.connectionState.first())
        val intermediate = observedStates.firstOrNull { it != ConnectionState.Disconnected && it != ConnectionState.Connected }
        if (intermediate != null) {
            assertEquals(ConnectionState.Connecting, intermediate)
        }

        observerJob.cancelAndJoin()
        session.stop()
    }

    @Test
    fun `reconnect start emits reconnecting then connected`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test"))
        assertEquals(ConnectionState.Disconnected, session.connectionState.first())

        val observedStates = CopyOnWriteArrayList<ConnectionState>()
        val observerJob = CoroutineScope(Dispatchers.Default).launch {
            session.connectionState.collect { observedStates.add(it) }
        }

        val startJob = CoroutineScope(Dispatchers.Default).launch { session.start(reconnectingAttempt = 1) }
        waitUntil { transport.sentData().isNotEmpty() }
        transport.simulateReceive(makeSelfInfoPacket())
        startJob.join()

        assertEquals(ConnectionState.Connected, session.connectionState.first())
        val intermediate = observedStates.firstOrNull { it != ConnectionState.Disconnected && it != ConnectionState.Connected }
        if (intermediate != null) {
            assertEquals(ConnectionState.Reconnecting(1), intermediate)
        }

        observerJob.cancelAndJoin()
        session.stop()
    }

    @Test
    fun `failed appStart unwinds start so it can be retried`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 0.05, clientIdentifier = "Test"))

        val observedStates = CopyOnWriteArrayList<ConnectionState>()
        val observerJob = CoroutineScope(Dispatchers.Default).launch {
            session.connectionState.collect { observedStates.add(it) }
        }
        waitUntil { observedStates.firstOrNull() == ConnectionState.Disconnected }

        // No selfInfo response arrives, so appStart times out.
        try {
            session.start()
            fail("expected MeshCoreError.Timeout")
        } catch (error: MeshCoreError) {
            // expected
        }

        assertTrue(session.connectionState.first() is ConnectionState.Failed)
        assertNull(session.currentSelfInfo)

        // A retry must attempt the handshake again rather than silently no-op.
        try {
            session.start()
            fail("expected MeshCoreError.Timeout")
        } catch (error: MeshCoreError) {
            // expected
        }
        assertEquals("retry start() should send a fresh appStart", 2, transport.sentData().size)

        observerJob.cancelAndJoin()
    }

    @Test
    fun `getContact rejects short public key before sending`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test"))

        try {
            session.getContact(ByteArray(31) { 0xAA.toByte() })
            fail("expected MeshCoreError")
        } catch (error: MeshCoreError) {
            // expected
        }

        assertTrue(transport.sentData().isEmpty())
    }

    @Test
    fun `requestStatus rejects short public key before sending`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test"))

        try {
            session.requestStatus(ByteArray(31) { 0xBB.toByte() })
            fail("expected MeshCoreError")
        } catch (error: MeshCoreError) {
            // expected
        }

        assertTrue(transport.sentData().isEmpty())
    }

    @Test
    fun `setPathHashMode rejects reserved mode before sending`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test"))

        try {
            session.setPathHashMode(3u)
            fail("expected MeshCoreError")
        } catch (error: MeshCoreError) {
            // expected
        }

        assertTrue(transport.sentData().isEmpty())
    }
}
