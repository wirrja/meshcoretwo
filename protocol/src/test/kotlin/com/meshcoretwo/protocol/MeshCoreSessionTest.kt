// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A first smoke-test pass over [MeshCoreSession] covering the highest-risk wiring: the
 * start/appStart handshake, a simple command round-trip, a multi-frame contact fetch, and
 * waitForEvent's timeout path. The rest of PLAN.md's ~3000-line Swift Session test-file port
 * lives in the other `MeshCoreSession*Test.kt` files alongside this one.
 */
class MeshCoreSessionTest {
    @Test
    fun `start populates currentSelfInfo and transitions connectionState to connected`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 2.0))

        startSession(session, transport)

        assertNotNull(session.currentSelfInfo)
        assertEquals("TestNode", session.currentSelfInfo?.name)
        assertEquals(ConnectionState.Connected, session.connectionState.first())
    }

    @Test
    fun `stop disconnects the transport and finishes event subscriptions`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 2.0))
        startSession(session, transport)

        session.stop()

        assertEquals(ConnectionState.Disconnected, session.connectionState.first())
        assertTrue(!transport.isConnected())
    }

    @Test
    fun `setName sends a simple command and completes on OK`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 2.0))
        startSession(session, transport)

        val job = CoroutineScope(Dispatchers.Default).launch { session.setName("Alice") }
        waitUntil { transport.sentData().isNotEmpty() }
        transport.simulateOK()
        job.join()

        assertEquals(1, transport.sentData().size)
    }

    @Test
    fun `getContacts assembles a full list from a start-contact-end sequence`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 2.0))
        startSession(session, transport)

        val publicKey = ByteArray(32) { 0xAB.toByte() }
        val job = CoroutineScope(Dispatchers.Default).launch {
            val contacts = session.getContacts()
            assertEquals(1, contacts.size)
            assertEquals("Bob", contacts.first().advertisedName)
        }
        waitUntil { transport.sentData().isNotEmpty() }
        transport.simulateReceive(makeContactsStartPacket(1u))
        transport.simulateReceive(makeContactPacket(publicKey, "Bob"))
        transport.simulateReceive(makeContactsEndPacket(1u))
        job.join()
    }

    @Test
    fun `waitForEvent returns null when no matching event arrives before the timeout`() = runTest {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 1.0))

        val result = session.waitForEvent(EventFilter.ok, timeout = 1.0)

        assertNull(result)
    }
}
