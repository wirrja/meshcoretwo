// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from `NodeSettingsResponseParserTests.swift`. */
class NodeSettingsResponseParserTest {
    // MARK: - Late Reply Recovery

    @Test
    fun `late reply recovers for the single unanswered query it parses for`() {
        val recovered = NodeSettingsResponseParser.recoveredResponse("> 22", setOf("get tx"))
        assertEquals("get tx", recovered?.first)
        assertEquals(CLIResponse.TxPower(22), recovered?.second)

        val radio = NodeSettingsResponseParser.recoveredResponse("> 915.000,250.0,10,5", setOf("get tx", "get radio"))
        assertEquals("get radio", radio?.first)
        assertEquals(CLIResponse.Radio(915.0, 250.0, 10, 5), radio?.second)
    }

    @Test
    fun `a bare double is ambiguous when both coordinates are unanswered`() {
        assertNull(NodeSettingsResponseParser.recoveredResponse("38.5", setOf("get lat", "get lon")))

        val single = NodeSettingsResponseParser.recoveredResponse("38.5", setOf("get lon"))
        assertEquals(CLIResponse.Longitude(38.5), single?.second)
    }

    @Test
    fun `query-independent shapes are never recovered`() {
        for (response in listOf("OK", "ERR: not allowed", "MeshCore v1.11.0 (2025-04-18)")) {
            assertNull(NodeSettingsResponseParser.recoveredResponse(response, setOf("get tx")))
        }
    }

    @Test
    fun `radio CSV never recovers as TX power`() {
        assertNull(NodeSettingsResponseParser.recoveredResponse("> 910.525,62.500,7,7", setOf("get tx")))
    }

    @Test
    fun `empty and free-form query sets recover nothing`() {
        assertNull(NodeSettingsResponseParser.recoveredResponse("22", emptySet()))
        assertNull(NodeSettingsResponseParser.recoveredResponse("Alpha Repeater", setOf("get name")))
    }

    // MARK: - Device Clock

    @Test
    fun `firmware clock response parses to the exact UTC date`() {
        val date = NodeSettingsResponseParser.utcDate("06:40 - 18/4/2025 UTC")!!
        val components = date.atZone(ZoneOffset.UTC)
        assertEquals(2025, components.year)
        assertEquals(4, components.monthValue)
        assertEquals(18, components.dayOfMonth)
        assertEquals(6, components.hour)
        assertEquals(40, components.minute)
    }

    @Test
    fun `non-clock text returns null`() {
        assertNull(NodeSettingsResponseParser.utcDate("Alpha Repeater"))
        assertNull(NodeSettingsResponseParser.utcDate("06:40 - 18/4/2025"))
    }

    @Test
    fun `clock response text is extracted from a bare clock line and from an OK sync line`() {
        assertEquals("06:40 - 18/4/2025 UTC", NodeSettingsResponseParser.clockResponseText("06:40 - 18/4/2025 UTC"))
        assertEquals(
            "15:35 - 14/8/2026 UTC",
            NodeSettingsResponseParser.clockResponseText("OK - clock set: 15:35 - 14/8/2026 UTC"),
        )
        assertNull(NodeSettingsResponseParser.clockResponseText("OK - clock set"))
        assertNull(NodeSettingsResponseParser.clockResponseText("Alpha Repeater"))
    }

    @Test
    fun `clock drift is node minus reference and null when the text has no clock`() {
        val node = NodeSettingsResponseParser.utcDate("06:40 - 18/4/2025 UTC")!!
        val now = node.plusSeconds(600)
        val drift = NodeSettingsResponseParser.clockDrift("06:40 - 18/4/2025 UTC", now)
        assertEquals(-600L, drift)

        assertNull(NodeSettingsResponseParser.clockDrift("OK - clock set", now))
    }

    // MARK: - Clock Sync

    @Test
    fun `clock sync outcomes classify OK, clock-ahead, generic error, and unexpected text`() {
        assertEquals(NodeSettingsResponseParser.ClockSyncOutcome.Synced, NodeSettingsResponseParser.classifyClockSyncResponse("OK - clock set"))
        assertEquals(
            NodeSettingsResponseParser.ClockSyncOutcome.ClockAhead,
            NodeSettingsResponseParser.classifyClockSyncResponse("ERR: clock cannot go backwards"),
        )
        assertEquals(
            NodeSettingsResponseParser.ClockSyncOutcome.Failed("invalid time"),
            NodeSettingsResponseParser.classifyClockSyncResponse("ERR: invalid time"),
        )
        assertEquals(NodeSettingsResponseParser.ClockSyncOutcome.Unexpected, NodeSettingsResponseParser.classifyClockSyncResponse("hello"))
    }

    // MARK: - Password

    @Test
    fun `password change succeeds on OK or the firmware echo, fails otherwise`() {
        assertTrue(NodeSettingsResponseParser.isPasswordChangeSuccessful("> password now: hunter2"))
        assertTrue(NodeSettingsResponseParser.isPasswordChangeSuccessful("OK"))
        assertFalse(NodeSettingsResponseParser.isPasswordChangeSuccessful("ERR: bad password"))
        assertFalse(NodeSettingsResponseParser.isPasswordChangeSuccessful("Alpha Repeater"))
    }

    // MARK: - Owner Info

    @Test
    fun `owner info wire and display forms round-trip`() {
        assertEquals("KD7ABC\nch 31", NodeSettingsResponseParser.displayOwnerInfo("KD7ABC|ch 31"))
        assertEquals("KD7ABC|ch 31", NodeSettingsResponseParser.wireOwnerInfo("KD7ABC\nch 31"))
        val display = "line one\nline two\nline three"
        assertEquals(display, NodeSettingsResponseParser.displayOwnerInfo(NodeSettingsResponseParser.wireOwnerInfo(display)))
    }
}
