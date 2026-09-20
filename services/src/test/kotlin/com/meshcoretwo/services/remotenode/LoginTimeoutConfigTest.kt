// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.MessageSentInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ported from `LoginTimeoutConfigTests.swift`, trimmed to [LoginTimeoutConfig.timeoutMs]/
 * [RemoteOperationTimeoutPolicy.loginTimeoutMs] — the `cliTimeout` scenarios belong to the
 * not-yet-ported CLI extension's `RemoteOperationTimeoutPolicy.cliTimeout`.
 */
class LoginTimeoutConfigTest {
    private fun sentInfo(timeoutMs: UInt) = MessageSentInfo(route = 0u, expectedAck = byteArrayOf(0x00), suggestedTimeoutMs = timeoutMs)

    @Test
    fun `direct path mode 0 uses base timeout only`() {
        assertEquals(5_000L, LoginTimeoutConfig.timeoutMs(0x00u))
    }

    @Test
    fun `direct path mode 1 uses base timeout only, not mode bits`() {
        // Mode 1, 0 hops encodes as 0x40.
        assertEquals(5_000L, LoginTimeoutConfig.timeoutMs(0x40u))
    }

    @Test
    fun `direct path mode 2 uses base timeout only, not mode bits`() {
        // Mode 2, 0 hops encodes as 0x80.
        assertEquals(5_000L, LoginTimeoutConfig.timeoutMs(0x80u))
    }

    @Test
    fun `mode 1 with 3 hops computes timeout from hop count`() {
        // Mode 1, 3 hops encodes as 0x43.
        assertEquals(35_000L, LoginTimeoutConfig.timeoutMs(0x43u)) // 5 + 3*10
    }

    @Test
    fun `mode 0 with 5 hops computes correct timeout`() {
        assertEquals(55_000L, LoginTimeoutConfig.timeoutMs(5u)) // 5 + 5*10
    }

    @Test
    fun `flood routing 0xFF budgets for the worst case`() {
        // 0xFF is mode 3 (reserved), meaning no known path.
        assertEquals(LoginTimeoutConfig.MAXIMUM_TIMEOUT_MS, LoginTimeoutConfig.timeoutMs(0xFFu))
    }

    @Test
    fun `timeout is capped at maximum`() {
        // Mode 0, 6 hops gives 5 + 60 = 65s, capped at 60s.
        assertEquals(60_000L, LoginTimeoutConfig.timeoutMs(6u))
    }

    @Test
    fun `login timeout policy gives flood logins the full login maximum`() {
        // A short firmware estimate must not starve a flood login; clamped to loginMaximum.
        assertEquals(20_000L, RemoteOperationTimeoutPolicy.loginTimeoutMs(sentInfo(4724u), 0xFFu))
    }

    @Test
    fun `login timeout policy clamps long firmware suggestions`() {
        assertEquals(20_000L, RemoteOperationTimeoutPolicy.loginTimeoutMs(sentInfo(20_000u), 0u))
    }

    @Test
    fun `login timeout policy respects path floor when firmware is shorter`() {
        // Mode 1, 3 hops = 35s path floor, clamped down to the 20s login maximum.
        assertEquals(20_000L, RemoteOperationTimeoutPolicy.loginTimeoutMs(sentInfo(1_000u), 0x43u))
    }
}
