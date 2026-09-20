// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Port of V115GetMessageTests.swift. */
class V115GetMessageTest {
    @Test
    fun `getMessage yields channelDatagram`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))

        startSession(session, transport)

        val job = CoroutineScope(Dispatchers.Default).launch {
            val result = session.getMessage()
            val datagram = (result as? MessageResult.ChannelDatagramResult)?.datagram
                ?: run { fail("Expected ChannelDatagramResult, got $result"); return@launch }

            assertEquals(2u.toUByte(), datagram.channelIndex)
            assertEquals(0xFFFFu.toUShort(), datagram.dataType)
            assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte()), datagram.data)
        }

        waitUntil { transport.sentData().isNotEmpty() }

        // 0x1B + snr/rsv header + channel + pathLen + data_type + data_len + payload
        val payload = byteArrayOf(
            0x1B,
            0x00, 0x00, 0x00,
            0x02, // channel 2
            0xFF.toByte(), // path_len: 0xFF means direct route
            0xFF.toByte(), 0xFF.toByte(), // data_type 0xFFFF
            0x03, // data_len
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(),
        )
        transport.simulateReceive(payload)

        job.join()
        session.stop()
    }
}
