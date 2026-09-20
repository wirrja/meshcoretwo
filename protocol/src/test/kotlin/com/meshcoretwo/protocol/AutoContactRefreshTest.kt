// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of AutoContactRefreshTests.swift. */
class AutoContactRefreshTest {
    private suspend fun simulateEmptyContactsResponse(transport: MockTransport, lastModified: UInt) {
        transport.simulateReceive(makeContactsStartPacket(0u))
        transport.simulateReceive(makeContactsEndPacket(lastModified))
    }

    @Test
    fun `auto-refresh coalesces bursty contact invalidations`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MeshCore-Tests"))
        startSession(session, transport)
        session.setAutoUpdateContacts(true)

        val advertisementPacket = makeAdvertisementPacket(ByteArray(32) { 0x22 })
        transport.simulateReceive(advertisementPacket)
        transport.simulateReceive(advertisementPacket)
        transport.simulateReceive(advertisementPacket)

        waitUntil { transport.sentData().size >= 1 }

        assertTrue(transport.sentData().size == 1)
        assertArrayEquals(PacketBuilder.getContacts(), transport.sentData().first())

        simulateEmptyContactsResponse(transport, lastModified = 1u)
        delay(50)

        val sentAfterFirstResponse = transport.sentData().size
        assertTrue(sentAfterFirstResponse in 1..2)

        if (sentAfterFirstResponse == 2) {
            simulateEmptyContactsResponse(transport, lastModified = 2u)
            delay(50)
        }

        assertTrue(transport.sentData().size <= 2)
    }
}
