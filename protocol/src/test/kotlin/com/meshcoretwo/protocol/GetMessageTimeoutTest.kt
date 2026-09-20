// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of GetMessageTimeoutTests.swift. */
class GetMessageTimeoutTest {
    @Test
    fun `getMessage times out when no response arrives`() = runBlocking {
        val transport = MockTransport()
        transport.connect()

        val configuration = SessionConfiguration(defaultTimeout = 0.02, clientIdentifier = "MeshCore-Tests")
        val session = MeshCoreSession(transport, configuration)

        try {
            session.getMessage()
            fail("expected MeshCoreError.Timeout")
        } catch (error: MeshCoreError) {
            // expected
        }
    }

    @Test
    fun `getMessage timeout override can be shorter than the session default`() = runBlocking {
        val transport = MockTransport()
        transport.connect()

        val configuration = SessionConfiguration(defaultTimeout = 2.0, clientIdentifier = "MeshCore-Tests")
        val session = MeshCoreSession(transport, configuration)
        val start = System.nanoTime()

        try {
            session.getMessage(timeout = 0.02)
            fail("expected MeshCoreError.Timeout")
        } catch (error: MeshCoreError) {
            // expected
        }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 1000)
    }

    @Test
    fun `getMessage timeout override can extend beyond the session default`() = runBlocking {
        val transport = MockTransport()
        transport.connect()

        val configuration = SessionConfiguration(defaultTimeout = 0.02, clientIdentifier = "MeshCore-Tests")
        val session = MeshCoreSession(transport, configuration)
        val start = System.nanoTime()

        try {
            session.getMessage(timeout = 0.12)
            fail("expected MeshCoreError.Timeout")
        } catch (error: MeshCoreError) {
            // expected
        }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs >= 80)
    }
}
