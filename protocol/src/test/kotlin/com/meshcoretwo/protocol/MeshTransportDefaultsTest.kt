// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshTransportDefaultsTest {
    @Test
    fun `Default sendWithoutResponse forwards to send`() = runTest {
        val transport = MockTransport()
        transport.connect()
        val payload = byteArrayOf(0xAB.toByte(), 0xCD.toByte())

        transport.sendWithoutResponse(payload)

        val sent = transport.sentData()
        assertEquals(1, sent.size)
        assertArrayEquals(payload, sent[0])
    }

    @Test
    fun `Default supportsWriteWithoutResponse is false`() = runTest {
        val transport = MockTransport()
        assertFalse(transport.supportsWriteWithoutResponse())
    }

    @Test
    fun `Default supportsPipelinedReads mirrors supportsWriteWithoutResponse`() = runTest {
        val transport = MockTransport()
        assertFalse(transport.supportsPipelinedReads())

        transport.setSupportsWriteWithoutResponse(true)
        assertTrue(transport.supportsPipelinedReads())
    }
}
