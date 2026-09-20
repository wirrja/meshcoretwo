// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

import android.bluetooth.BluetoothGatt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val DEVICE_A = "AA:BB:CC:DD:EE:01"

class ReconnectPolicyTest {
    private lateinit var policy: ReconnectPolicy

    @Before
    fun setUp() {
        policy = ReconnectPolicy()
    }

    // MARK: - Bond-verification bookkeeping

    @Test
    fun `recordBondVerification then hasBondVerification is true`() {
        policy.recordBondVerification(DEVICE_A, 1_000L)
        assertTrue(policy.hasBondVerification(DEVICE_A))
    }

    @Test
    fun `clearBondVerification removes the stamp`() {
        policy.recordBondVerification(DEVICE_A, 1_000L)
        policy.clearBondVerification(DEVICE_A)
        assertFalse(policy.hasBondVerification(DEVICE_A))
    }

    @Test
    fun `refreshBondVerification updates an existing stamp and returns true`() {
        policy.recordBondVerification(DEVICE_A, 1_000L)
        val refreshed = policy.refreshBondVerification(DEVICE_A, 2_000L)
        assertTrue(refreshed)
        assertEquals(2_000L, policy.bondVerificationDates[DEVICE_A])
    }

    @Test
    fun `refreshBondVerification never creates a stamp for an unverified device`() {
        val refreshed = policy.refreshBondVerification(DEVICE_A, 2_000L)
        assertFalse(refreshed)
        assertNull(policy.bondVerificationDates[DEVICE_A])
    }

    // MARK: - isBondRecentlyVerified

    @Test
    fun `isBondRecentlyVerified is false when never verified`() {
        assertFalse(ReconnectPolicy.isBondRecentlyVerified(null, nowMillis = 1_000L))
    }

    @Test
    fun `isBondRecentlyVerified is true within the grace window`() {
        val now = 10_000_000L
        val lastVerified = now - ReconnectPolicy.BOND_VERIFICATION_GRACE_MILLIS + 1
        assertTrue(ReconnectPolicy.isBondRecentlyVerified(lastVerified, now))
    }

    @Test
    fun `isBondRecentlyVerified is false once the grace window elapses`() {
        val now = 10_000_000L
        val lastVerified = now - ReconnectPolicy.BOND_VERIFICATION_GRACE_MILLIS
        assertFalse(ReconnectPolicy.isBondRecentlyVerified(lastVerified, now))
    }

    // MARK: - resolveConnectFailure: definitive bond failure

    @Test
    fun `a definitive auth failure tears down immediately regardless of budget`() {
        val decision = policy.resolveConnectFailure(
            DEVICE_A,
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
            nowMillis = 0L,
            appActive = true,
        )
        val teardown = decision as ReconnectPolicy.ConnectFailureDecision.TearDown
        assertEquals(ReconnectPolicy.TeardownReason.DefinitiveBondFailure, teardown.reason)
        assertEquals(0, policy.reconnectConnectFailures)
    }

    // MARK: - resolveConnectFailure: retry budget

    @Test
    fun `retries below budget without touching bond verification`() {
        repeat(ReconnectPolicy.MAX_RECONNECT_CONNECT_FAILURES - 1) { i ->
            val decision = policy.resolveConnectFailure(DEVICE_A, ReconnectPolicy.GATT_ERROR, nowMillis = 0L, appActive = true)
            val retry = decision as ReconnectPolicy.ConnectFailureDecision.RetryPendingConnect
            assertEquals(i + 1, retry.failureCount)
        }
    }

    @Test
    fun `a non-ambiguous status exhausting the budget tears down when app is active`() {
        // GATT_ERROR is Android's "ambiguous" status; a distinct status keeps the majority-ambiguous
        // gate from ever engaging, so an exhausted budget always escalates.
        repeat(ReconnectPolicy.MAX_RECONNECT_CONNECT_FAILURES - 1) {
            policy.resolveConnectFailure(DEVICE_A, ReconnectPolicy.GATT_ERROR + 1, nowMillis = 0L, appActive = true)
        }
        val decision = policy.resolveConnectFailure(DEVICE_A, ReconnectPolicy.GATT_ERROR + 1, nowMillis = 0L, appActive = true)
        val teardown = decision as ReconnectPolicy.ConnectFailureDecision.TearDown
        assertEquals(ReconnectPolicy.TeardownReason.RetryBudgetExhausted, teardown.reason)
        assertEquals(0, policy.reconnectConnectFailures)
    }

    // MARK: - resolveConnectFailure: ambiguous-majority grace window

    @Test
    fun `a recently-verified bond holds the episode past an exhausted ambiguous-majority budget`() {
        val now = 10_000_000L
        policy.recordBondVerification(DEVICE_A, now - 1_000L)
        repeat(ReconnectPolicy.MAX_RECONNECT_CONNECT_FAILURES - 1) {
            policy.resolveConnectFailure(DEVICE_A, ReconnectPolicy.GATT_ERROR, nowMillis = now, appActive = true)
        }
        val decision = policy.resolveConnectFailure(DEVICE_A, ReconnectPolicy.GATT_ERROR, nowMillis = now, appActive = true)
        val hold = decision as ReconnectPolicy.ConnectFailureDecision.ContinueEpisodeAfterBudget
        assertTrue(hold.reason is ReconnectPolicy.BudgetHoldReason.AmbiguousFailureGraced)
        assertEquals(0, policy.reconnectConnectFailures)
    }

    @Test
    fun `an ambiguous-majority budget exhaustion tears down as bond-suspect once grace elapses`() {
        val now = 10_000_000L
        policy.recordBondVerification(DEVICE_A, now - ReconnectPolicy.BOND_VERIFICATION_GRACE_MILLIS)
        repeat(ReconnectPolicy.MAX_RECONNECT_CONNECT_FAILURES - 1) {
            policy.resolveConnectFailure(DEVICE_A, ReconnectPolicy.GATT_ERROR, nowMillis = now, appActive = true)
        }
        val decision = policy.resolveConnectFailure(DEVICE_A, ReconnectPolicy.GATT_ERROR, nowMillis = now, appActive = true)
        val teardown = decision as ReconnectPolicy.ConnectFailureDecision.TearDown
        assertTrue(teardown.reason is ReconnectPolicy.TeardownReason.BondSuspect)
    }

    @Test
    fun `an ambiguous-majority budget exhaustion holds instead of tearing down while backgrounded`() {
        val now = 10_000_000L
        repeat(ReconnectPolicy.MAX_RECONNECT_CONNECT_FAILURES - 1) {
            policy.resolveConnectFailure(DEVICE_A, ReconnectPolicy.GATT_ERROR, nowMillis = now, appActive = false)
        }
        val decision = policy.resolveConnectFailure(DEVICE_A, ReconnectPolicy.GATT_ERROR, nowMillis = now, appActive = false)
        val hold = decision as ReconnectPolicy.ConnectFailureDecision.ContinueEpisodeAfterBudget
        assertEquals(ReconnectPolicy.BudgetHoldReason.BackgroundHold, hold.reason)
    }

    @Test
    fun `linkReestablished resets the failure tallies mid-episode`() {
        policy.resolveConnectFailure(DEVICE_A, ReconnectPolicy.GATT_ERROR, nowMillis = 0L, appActive = true)
        policy.linkReestablished()
        val decision = policy.resolveConnectFailure(DEVICE_A, ReconnectPolicy.GATT_ERROR, nowMillis = 0L, appActive = true)
        val retry = decision as ReconnectPolicy.ConnectFailureDecision.RetryPendingConnect
        assertEquals(1, retry.failureCount)
    }
}
