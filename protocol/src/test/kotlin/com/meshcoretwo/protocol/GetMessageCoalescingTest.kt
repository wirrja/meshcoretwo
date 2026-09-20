// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Port of GetMessageCoalescingTests.swift.
 *
 * A `getMessage` call that arrives while another is in flight must coalesce onto the in-flight
 * exchange and share its outcome. Every path out of the leader (result, device error, timeout)
 * must release the coalesced caller; a coalesced caller must never be parked beyond the leader's
 * own timeout, even when its task is cancelled.
 *
 * Unlike most of these ported files, this one relies on [startSession]'s cleared sent-data
 * history, so send-count assertions below are one lower than Swift's.
 */
class GetMessageCoalescingTest {
    private fun makeNoMoreMessagesPacket(): ByteArray = byteArrayOf(ResponseCode.NO_MORE_MESSAGES.value.toByte())

    @Test
    fun `a coalesced caller shares the leader's frame and result`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val leader = CoroutineScope(Dispatchers.Default).async { session.getMessage() }
        waitUntil { transport.sentData().size == 1 }

        val follower = CoroutineScope(Dispatchers.Default).async { session.getMessage() }
        delay(100)
        assertEquals("a coalesced caller must not send its own frame", 1, transport.sentData().size)

        transport.simulateReceive(makeNoMoreMessagesPacket())

        val leaderResult = leader.await()
        val followerResult = follower.await()
        if (leaderResult !is MessageResult.NoMoreMessages || followerResult !is MessageResult.NoMoreMessages) {
            fail("Expected both callers to see NoMoreMessages, got $leaderResult and $followerResult")
        }
        assertEquals("the shared exchange must produce exactly one frame", 1, transport.sentData().size)

        session.stop()
    }

    @Test
    fun `a coalesced caller is released when the leader times out`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val leader = CoroutineScope(Dispatchers.Default).async { session.getMessage(timeout = 0.15) }
        waitUntil { transport.sentData().size == 1 }

        val follower = CoroutineScope(Dispatchers.Default).async { session.getMessage() }
        delay(50)

        // No response ever arrives; the leader's timeout must release both callers.
        assertThrowsMeshCoreError { leader.await() }
        assertThrowsMeshCoreError { follower.await() }

        session.stop()
    }

    @Test
    fun `a coalesced caller is released when the leader fails with a device error`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val leader = CoroutineScope(Dispatchers.Default).async { session.getMessage() }
        waitUntil { transport.sentData().size == 1 }

        val follower = CoroutineScope(Dispatchers.Default).async { session.getMessage() }
        delay(50)

        transport.simulateError(9u)

        assertThrowsMeshCoreError { leader.await() }
        assertThrowsMeshCoreError { follower.await() }

        session.stop()
    }

    /**
     * Swift's `Task.cancel()` is cooperative: a cancelled Task's `.value` still waits for the
     * awaited work to genuinely finish, so a cancelled coalesced caller only unblocks once the
     * leader's own timeout fires. Kotlin's `Deferred.await()` is cancellable and reacts to the
     * *caller's own* cancellation immediately, regardless of whether the awaited Deferred has
     * completed — a strictly stronger guarantee (the cancelled caller can never be parked at
     * all, not even bounded by the leader's timeout). This port checks that Kotlin-appropriate
     * outcome instead of literally replaying Swift's bounded-wait assertion.
     */
    @Test
    fun `a cancelled coalesced caller does not hang`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val leader = CoroutineScope(Dispatchers.Default).async { session.getMessage(timeout = 0.3) }
        waitUntil { transport.sentData().size == 1 }

        val follower = CoroutineScope(Dispatchers.Default).launch { session.getMessage() }
        delay(50)
        follower.cancel()

        withTimeout(2000) { follower.join() }
        assertTrue(follower.isCancelled)

        // The leader is unaffected by the follower's cancellation and still resolves via its
        // own timeout.
        assertThrowsMeshCoreError { leader.await() }

        session.stop()
    }

    private suspend fun assertThrowsMeshCoreError(block: suspend () -> Unit) {
        try {
            block()
            fail("expected a MeshCoreError")
        } catch (error: MeshCoreError) {
            // expected
        }
    }
}
