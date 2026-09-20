// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Port of WiFiTransportTests.swift, substituting `java.net.ServerSocket` for `NWListener`.
 *
 * One Swift test is intentionally not ported: "Cancelling connect() does not leave it parked"
 * relies on TEST-NET-1 (192.0.2.1, RFC 5737) being unroutable so the TCP SYN is silently dropped.
 * This sandbox's network egress accepts/completes a connect to that address near-instantly
 * (verified by hand — not a real network, some proxy answers everything), so there is no
 * deterministic way here to make a real socket connect actually hang long enough to prove
 * cancellation aborts it early rather than the connect finishing on its own. The
 * `invokeOnCancellation { newSocket.close() }` mechanism in [WiFiTransport.connect] is still in
 * place (mirrors the BLE connect-cancellation idiom in `services`) — it is just unverified by an
 * automated test in this environment.
 */
class WiFiTransportTest {
    @Test
    fun `initial state is disconnected`() = runBlocking {
        val transport = WiFiTransport()
        assertFalse(transport.isConnected())
    }

    @Test
    fun `advertises pipelined reads without a write-without-response characteristic`() = runBlocking {
        val transport = WiFiTransport()
        assertTrue(transport.supportsPipelinedReads())
        assertFalse(transport.supportsWriteWithoutResponse())
    }

    @Test
    fun `connect without configuration throws notConfigured`() = runBlocking {
        val transport = WiFiTransport()
        try {
            transport.connect()
            fail("expected WiFiTransportError.NotConfigured")
        } catch (error: WiFiTransportError.NotConfigured) {
            // expected
        }
    }

    @Test
    fun `connection to invalid host fails`() = runBlocking {
        val transport = WiFiTransport()
        transport.setConnectionInfo("999.999.999.999", 5000)
        try {
            transport.connect()
            fail("expected a WiFiTransportError")
        } catch (error: WiFiTransportError) {
            // expected
        }
    }

    @Test
    fun `send without connection throws notConnected`() = runBlocking {
        val transport = WiFiTransport()
        try {
            transport.send(byteArrayOf(0x01, 0x02, 0x03))
            fail("expected WiFiTransportError.NotConnected")
        } catch (error: WiFiTransportError.NotConnected) {
            // expected
        }
    }

    @Test
    fun `disconnect when not connected is safe`() = runBlocking {
        val transport = WiFiTransport()
        transport.disconnect() // should not throw or crash
        assertFalse(transport.isConnected())
    }

    @Test
    fun `connectionInfo returns configured host and port`() = runBlocking {
        val transport = WiFiTransport()
        assertNull(transport.connectionInfo())

        transport.setConnectionInfo("192.168.1.50", 5000)
        val info = transport.connectionInfo()
        assertEquals("192.168.1.50", info?.first)
        assertEquals(5000, info?.second)
    }

    @Test
    fun `disconnection handler not called on user-initiated disconnect`() = runBlocking {
        val transport = WiFiTransport()
        val called = AtomicBoolean(false)
        transport.setDisconnectionHandler { called.set(true) }

        transport.disconnect()
        delay(100) // give any async callbacks time to fire

        assertFalse("Handler should not be called on user disconnect", called.get())
    }

    @Test
    fun `disconnection handler not called on initial connect failure`() = runBlocking {
        val transport = WiFiTransport()
        val called = AtomicBoolean(false)
        transport.setDisconnectionHandler { called.set(true) }
        transport.setConnectionInfo("999.999.999.999", 5000)

        try {
            transport.connect()
        } catch (error: WiFiTransportError) {
            // expected to fail
        }
        delay(100)

        assertFalse("Handler should not be called on initial connect failure", called.get())
    }

    @Test
    fun `connect is idempotent - second call does not create a new TCP connection`() = runBlocking {
        val acceptCount = AtomicInteger(0)
        val acceptedSockets = CopyOnWriteArrayList<Socket>()
        val server = startLocalServer {
            acceptCount.incrementAndGet()
            acceptedSockets.add(it)
        }
        try {
            val transport = WiFiTransport()
            transport.setConnectionInfo("127.0.0.1", server.localPort)

            transport.connect()
            assertTrue("Transport should be connected after first connect()", transport.isConnected())

            waitUntil { acceptCount.get() >= 1 }

            transport.connect() // second connect() should be a no-op
            assertTrue("Transport should still be connected after second connect()", transport.isConnected())

            // Give time for any spurious second accept to arrive. A fixed delay is correct
            // here: we're waiting for something that should NOT happen.
            delay(500)

            assertEquals("Expected 1 TCP accept — second connect() should be a no-op", 1, acceptCount.get())

            transport.disconnect()
        } finally {
            acceptedSockets.forEach { runCatching { it.close() } }
            server.close()
        }
    }

    @Test
    fun `peer close finishes the stream and fires the disconnection handler`() = runBlocking {
        val acceptedSockets = CopyOnWriteArrayList<Socket>()
        val server = startLocalServer { acceptedSockets.add(it) }
        try {
            val transport = WiFiTransport()
            val disconnected = AtomicBoolean(false)
            transport.setDisconnectionHandler { disconnected.set(true) }
            transport.setConnectionInfo("127.0.0.1", server.localPort)
            transport.connect()

            val streamEnded = AtomicBoolean(false)
            val consumeJob = CoroutineScope(Dispatchers.Default).launch {
                transport.receivedData.collect { }
                streamEnded.set(true)
            }

            waitUntil { acceptedSockets.isNotEmpty() }

            // The radio closes the TCP stream cleanly (FIN).
            acceptedSockets.first().shutdownOutput()

            waitUntil { disconnected.get() }
            waitUntil { streamEnded.get() }
            assertFalse("Peer close should mark the transport disconnected", transport.isConnected())

            consumeJob.cancelAndJoin()
            transport.disconnect()
        } finally {
            acceptedSockets.forEach { runCatching { it.close() } }
            server.close()
        }
    }
}

/** Starts a TCP server on an ephemeral loopback port, invoking [onAccept] for each accepted client. */
private fun startLocalServer(onAccept: (Socket) -> Unit): ServerSocket {
    val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    thread(isDaemon = true, name = "WiFiTransportTest-server") {
        try {
            while (!server.isClosed) {
                onAccept(server.accept())
            }
        } catch (error: IOException) {
            // Server socket closed by the test; exit the accept loop.
        }
    }
    return server
}
