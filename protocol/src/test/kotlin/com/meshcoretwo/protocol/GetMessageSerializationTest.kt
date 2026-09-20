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

/**
 * Port of GetMessageSerializationTests.swift.
 *
 * getMessage runs inside the request/response serializer, so a command running concurrently
 * with it owns the serializer first and any `.error` that arrives while that command is in
 * flight is the command's own. getMessage must not consume that error and report a spurious
 * device error for the message-fetch path; it sees only its own response once the unrelated
 * command releases the serializer.
 *
 * Unlike most of these ported files, this one relies on [startSession]'s cleared sent-data
 * history, so the expected send counts below are one lower than Swift's (which doesn't clear
 * after its own local startSession helper).
 */
class GetMessageSerializationTest {
    private fun makeNoMoreMessagesPacket(): ByteArray = byteArrayOf(ResponseCode.NO_MORE_MESSAGES.value.toByte())

    @Test
    fun `an unrelated error does not fail an in-flight getMessage`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))

        startSession(session, transport)

        // A simple command acquires the serializer first.
        val resetJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                session.factoryReset()
                fail("expected factoryReset to fail with a device error")
            } catch (error: MeshCoreError.DeviceError) {
                assertEquals(17u.toUByte(), error.code)
            }
        }
        waitUntil { transport.sentData().size == 1 }

        // getMessage is issued while the reset is outstanding; it must park behind it.
        val messageJob = CoroutineScope(Dispatchers.Default).launch {
            val result = session.getMessage()
            if (result !is MessageResult.NoMoreMessages) {
                fail("Expected getMessage to return NoMoreMessages, got $result")
            }
        }
        delay(50)
        assertTrue("getMessage must wait behind the in-flight command", transport.sentData().size == 1)

        // The error belongs to the reset command, which owns the serializer.
        transport.simulateError(17u)
        resetJob.join()

        // Only after the reset releases the serializer does getMessage send its frame.
        waitUntil { transport.sentData().size == 2 }

        // getMessage receives its own response and is unaffected by the earlier error.
        transport.simulateReceive(makeNoMoreMessagesPacket())
        messageJob.join()

        session.stop()
    }
}
