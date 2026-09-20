// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Ported from `FirmwareSuggestedTimeoutTests.swift`. */
class FirmwareSuggestedTimeoutTest {
    private val tolerance = 0.0001

    // MARK: - Zero-hop

    @Test
    fun `zero-hop honors a small valid hint instead of inflating it`() {
        val timeout = FirmwareSuggestedTimeout.sanitizedSeconds(3200u, FirmwareSuggestedTimeout.Profile.ZERO_HOP)
        assertTrue(abs(timeout - 3.84) < tolerance)
        assertTrue(timeout < 5.0)
    }

    @Test
    fun `zero-hop floors a tiny hint`() {
        assertEquals(1.0, FirmwareSuggestedTimeout.sanitizedSeconds(500u, FirmwareSuggestedTimeout.Profile.ZERO_HOP), tolerance)
    }

    @Test
    fun `zero-hop honors a slow max-range preset hint up to the ceiling`() {
        assertEquals(24.0, FirmwareSuggestedTimeout.sanitizedSeconds(20000u, FirmwareSuggestedTimeout.Profile.ZERO_HOP), tolerance)
    }

    @Test
    fun `zero-hop defaults on a missing hint`() {
        assertEquals(5.0, FirmwareSuggestedTimeout.sanitizedSeconds(0u, FirmwareSuggestedTimeout.Profile.ZERO_HOP), tolerance)
    }

    @Test
    fun `zero-hop caps an absurd hint`() {
        assertEquals(30.0, FirmwareSuggestedTimeout.sanitizedSeconds(68_719_800u, FirmwareSuggestedTimeout.Profile.ZERO_HOP), tolerance)
    }

    // MARK: - Flood

    @Test
    fun `flood adds return-leg grace on top of a sane hint`() {
        assertEquals(14.0, FirmwareSuggestedTimeout.sanitizedSeconds(5000u, FirmwareSuggestedTimeout.Profile.FLOOD), tolerance)
    }

    @Test
    fun `flood grace lifts a small hint above the floor`() {
        assertEquals(11.6, FirmwareSuggestedTimeout.sanitizedSeconds(3000u, FirmwareSuggestedTimeout.Profile.FLOOD), tolerance)
    }

    @Test
    fun `flood defaults on a missing hint`() {
        assertEquals(30.0, FirmwareSuggestedTimeout.sanitizedSeconds(0u, FirmwareSuggestedTimeout.Profile.FLOOD), tolerance)
    }

    @Test
    fun `flood caps an absurd hint`() {
        assertEquals(60.0, FirmwareSuggestedTimeout.sanitizedSeconds(68_719_800u, FirmwareSuggestedTimeout.Profile.FLOOD), tolerance)
    }

    // MARK: - Path discovery

    @Test
    fun `path discovery floors a fast-preset hint to the multi-hop minimum`() {
        assertEquals(FirmwareSuggestedTimeout.PATH_DISCOVERY_MINIMUM_OVERALL_SECONDS, FirmwareSuggestedTimeout.pathDiscoverySeconds(5000u), tolerance)
        assertEquals(20.0, FirmwareSuggestedTimeout.pathDiscoverySeconds(5000u), tolerance)
    }

    @Test
    fun `path discovery honors a larger flood budget above the minimum`() {
        assertEquals(32.0, FirmwareSuggestedTimeout.pathDiscoverySeconds(20000u), tolerance)
    }

    @Test
    fun `path discovery retransmit interval is nil on a missing hint, else double firmware est with a floor`() {
        assertNull(FirmwareSuggestedTimeout.pathDiscoveryRetransmitIntervalMs(0u))
        assertEquals(5_000L, FirmwareSuggestedTimeout.pathDiscoveryRetransmitIntervalMs(1000u))
        assertEquals(10_000L, FirmwareSuggestedTimeout.pathDiscoveryRetransmitIntervalMs(5000u))
    }
}
