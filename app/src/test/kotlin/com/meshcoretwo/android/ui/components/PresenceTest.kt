// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `null last heard is none`() {
        assertEquals(PresenceLevel.NONE, presenceLevel(null, now))
    }

    @Test
    fun `just now is active`() {
        assertEquals(PresenceLevel.ACTIVE, presenceLevel(now, now))
    }

    @Test
    fun `four minutes ago is active`() {
        assertEquals(PresenceLevel.ACTIVE, presenceLevel(now.minus(Duration.ofMinutes(4)), now))
    }

    @Test
    fun `exactly five minutes ago is still active`() {
        assertEquals(PresenceLevel.ACTIVE, presenceLevel(now.minus(Duration.ofMinutes(5)), now))
    }

    @Test
    fun `six minutes ago is recent`() {
        assertEquals(PresenceLevel.RECENT, presenceLevel(now.minus(Duration.ofMinutes(6)), now))
    }

    @Test
    fun `exactly one hour ago is still recent`() {
        assertEquals(PresenceLevel.RECENT, presenceLevel(now.minus(Duration.ofHours(1)), now))
    }

    @Test
    fun `over one hour ago is none`() {
        assertEquals(PresenceLevel.NONE, presenceLevel(now.minus(Duration.ofHours(1).plusSeconds(1)), now))
    }

    @Test
    fun `future timestamp is none`() {
        assertEquals(PresenceLevel.NONE, presenceLevel(now.plusSeconds(60), now))
    }
}
