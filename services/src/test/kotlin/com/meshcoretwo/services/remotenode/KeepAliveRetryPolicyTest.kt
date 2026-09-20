// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.MeshCoreError
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ported from `RemoteNodeKeepAliveTests.swift`, dropping the `failureReason` scenarios — that
 * function isn't ported (see [KeepAliveRetryPolicy]'s class doc, it only ever fed a dropped log line).
 */
class KeepAliveRetryPolicyTest {
    @Test
    fun `single transient failure retries without disconnecting`() {
        val (action, failures) = KeepAliveRetryPolicy.evaluate(RemoteNodeError.SessionError(MeshCoreError.Timeout), 0)
        assertEquals(1, failures)
        assertEquals(KeepAliveRetryPolicy.Action.RETRY_NEXT_INTERVAL, action)
    }

    @Test
    fun `two consecutive transient failures triggers disconnect`() {
        val (action, failures) = KeepAliveRetryPolicy.evaluate(RemoteNodeError.SessionError(MeshCoreError.Timeout), 1)
        assertEquals(2, failures)
        assertEquals(KeepAliveRetryPolicy.Action.DISCONNECT, action)
    }

    @Test
    fun `sessionNotFound disconnects immediately without incrementing counter`() {
        val (action, failures) = KeepAliveRetryPolicy.evaluate(RemoteNodeError.SessionNotFound, 0)
        assertEquals(0, failures)
        assertEquals(KeepAliveRetryPolicy.Action.DISCONNECT_NOW, action)
    }

    @Test
    fun `contactNotFound disconnects immediately without incrementing counter`() {
        val (action, failures) = KeepAliveRetryPolicy.evaluate(RemoteNodeError.ContactNotFound, 0)
        assertEquals(0, failures)
        assertEquals(KeepAliveRetryPolicy.Action.DISCONNECT_NOW, action)
    }

    @Test
    fun `deviceError is treated as transient failure`() {
        val (action, failures) = KeepAliveRetryPolicy.evaluate(RemoteNodeError.SessionError(MeshCoreError.DeviceError(7u)), 0)
        assertEquals(1, failures)
        assertEquals(KeepAliveRetryPolicy.Action.RETRY_NEXT_INTERVAL, action)
    }

    @Test
    fun `notConnected is treated as transient failure`() {
        val (action, failures) = KeepAliveRetryPolicy.evaluate(RemoteNodeError.SessionError(MeshCoreError.NotConnected), 0)
        assertEquals(1, failures)
        assertEquals(KeepAliveRetryPolicy.Action.RETRY_NEXT_INTERVAL, action)
    }

    @Test
    fun `floodRouted is not counted as a failure`() {
        val (action, failures) = KeepAliveRetryPolicy.evaluate(RemoteNodeError.FloodRouted, 0)
        assertEquals(0, failures)
        assertEquals(KeepAliveRetryPolicy.Action.SKIP, action)
    }

    @Test
    fun `CancellationException stops the loop quietly`() {
        val (action, failures) = KeepAliveRetryPolicy.evaluate(CancellationException(), 0)
        assertEquals(0, failures)
        assertEquals(KeepAliveRetryPolicy.Action.STOP, action)
    }

    @Test
    fun `RemoteNodeError Cancelled stops the loop quietly`() {
        val (action, failures) = KeepAliveRetryPolicy.evaluate(RemoteNodeError.Cancelled, 0)
        assertEquals(0, failures)
        assertEquals(KeepAliveRetryPolicy.Action.STOP, action)
    }

    @Test
    fun `unknown non-RemoteNodeError disconnects immediately`() {
        class PersistenceError : Exception()
        val (action, failures) = KeepAliveRetryPolicy.evaluate(PersistenceError(), 0)
        assertEquals(0, failures)
        assertEquals(KeepAliveRetryPolicy.Action.DISCONNECT_NOW, action)
    }
}
