// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of AutoMessageFetchTests.swift. */
class AutoMessageFetchTest {
    private fun assertNoMoreMessages(result: MessageResult) {
        if (result !is MessageResult.NoMoreMessages) {
            fail("Expected NoMoreMessages, got $result")
        }
    }

    @Test
    fun `concurrent getMessage calls share one wire request`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MeshCore-Tests"))
        startSession(session, transport)

        val firstJob = CoroutineScope(Dispatchers.Default).launch { session.getMessage(timeout = 10.0) }
        val secondJob = CoroutineScope(Dispatchers.Default).launch { session.getMessage(timeout = 10.0) }

        waitUntil { transport.sentData().size == 1 }

        transport.simulateReceive(byteArrayOf(ResponseCode.NO_MORE_MESSAGES.value.toByte()))

        firstJob.join()
        secondJob.join()

        assertEquals(1, transport.sentData().size)
    }

    @Test
    fun `auto-fetch coalesces repeated messagesWaiting notifications`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MeshCore-Tests"))
        startSession(session, transport)
        session.startAutoMessageFetching()

        val waitingPacket = byteArrayOf(ResponseCode.MESSAGES_WAITING.value.toByte())
        transport.simulateReceive(waitingPacket)
        transport.simulateReceive(waitingPacket)
        transport.simulateReceive(waitingPacket)

        waitUntil { transport.sentData().size == 1 }

        transport.simulateReceive(byteArrayOf(ResponseCode.NO_MORE_MESSAGES.value.toByte()))
        delay(50)

        val sentCount = transport.sentData().size
        assertTrue(sentCount in 1..2)

        if (sentCount == 2) {
            transport.simulateReceive(byteArrayOf(ResponseCode.NO_MORE_MESSAGES.value.toByte()))
            delay(20)
            assertEquals(2, transport.sentData().size)
        }

        session.stopAutoMessageFetching()
    }

    @Test
    fun `manual getMessage shares in-flight auto-fetch poll`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MeshCore-Tests"))
        startSession(session, transport)
        session.startAutoMessageFetching()

        transport.simulateReceive(byteArrayOf(ResponseCode.MESSAGES_WAITING.value.toByte()))

        waitUntil { transport.sentData().size == 1 }

        val manualPollJob = CoroutineScope(Dispatchers.Default).launch {
            val result = session.getMessage(timeout = 10.0)
            assertNoMoreMessages(result)
        }
        delay(20)

        assertEquals(1, transport.sentData().size)

        transport.simulateReceive(byteArrayOf(ResponseCode.NO_MORE_MESSAGES.value.toByte()))

        manualPollJob.join()
        assertEquals(1, transport.sentData().size)
        session.stopAutoMessageFetching()
    }
}
