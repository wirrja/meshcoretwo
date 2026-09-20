// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from `CLIResponseTests.swift`. */
class CLIResponseTest {
    // MARK: - TX Power

    @Test
    fun `bare integer parses as TX power`() {
        assertEquals(CLIResponse.TxPower(22), CLIResponse.parse("> 22", "get tx"))
        assertEquals(CLIResponse.TxPower(-5), CLIResponse.parse("-5", "get tx"))
    }

    @Test
    fun `ZephCore adaptive power annotation reads the max ceiling`() {
        assertEquals(CLIResponse.TxPower(22), CLIResponse.parse("> 22dBm (apc=off)", "get tx"))
        assertEquals(
            CLIResponse.TxPower(22),
            CLIResponse.parse("> 16dBm (apc=on max=22 reduction=6 margin=18.5 target=16)", "get tx"),
        )
    }

    @Test
    fun `radio CSV never parses as TX power`() {
        // A "get radio" reply misattributed to "get tx" once showed 910 dBm for a 910.525 MHz
        // repeater; the leading integer of a decimal or CSV must not match.
        assertEquals(CLIResponse.Raw("910.525,62.500,7,7"), CLIResponse.parse("> 910.525,62.500,7,7", "get tx"))
        assertEquals(CLIResponse.Raw("910.5"), CLIResponse.parse("910.5", "get tx"))
    }

    // MARK: - Radio

    @Test
    fun `radio CSV parses only for the radio query`() {
        assertEquals(CLIResponse.Radio(915.0, 250.0, 10, 5), CLIResponse.parse("> 915.000,250.0,10,5", "get radio"))
        assertEquals(CLIResponse.Raw("22"), CLIResponse.parse("> 22", "get radio"))
    }

    // MARK: - Device Time

    @Test
    fun `clock reply parses as device time only for the clock query`() {
        assertEquals(CLIResponse.DeviceTime("06:40 - 18/4/2025 UTC"), CLIResponse.parse("06:40 - 18/4/2025 UTC", "clock"))
        // The ":" + "/" shape also appears in free-form text; without the clock query it must stay
        // raw instead of being adopted as a timestamp.
        assertEquals(CLIResponse.Raw("Contact: KD7ABC / 145.230"), CLIResponse.parse("Contact: KD7ABC / 145.230"))
        assertEquals(CLIResponse.Raw("06:40 - 18/4/2025 UTC"), CLIResponse.parse("06:40 - 18/4/2025 UTC", "get radio"))
    }

    // MARK: - Response Matching

    @Test
    fun `structured get queries reject replies of the wrong shape`() {
        assertFalse(CLIResponse.isPlausibleResponse("> 910.525,62.500,7,7", "get tx"))
        assertFalse(CLIResponse.isPlausibleResponse("> 22", "get radio"))
        assertFalse(CLIResponse.isPlausibleResponse("Alpha Repeater", "get lat"))
        assertFalse(CLIResponse.isPlausibleResponse("> 22", "clock"))
    }

    @Test
    fun `structured get queries accept their own shape and errors`() {
        assertTrue(CLIResponse.isPlausibleResponse("> 22", "get tx"))
        assertTrue(CLIResponse.isPlausibleResponse("> 915.000,250.0,10,5", "get radio"))
        assertTrue(CLIResponse.isPlausibleResponse("-36.8485", "get lat"))
        assertTrue(CLIResponse.isPlausibleResponse("ERR: not allowed", "get tx"))
    }

    @Test
    fun `free-form and action commands accept any reply`() {
        // Firmware success replies are not uniformly "OK"-prefixed.
        assertTrue(CLIResponse.isPlausibleResponse("password now: hunter2", "password hunter2"))
        assertTrue(CLIResponse.isPlausibleResponse("OK", "set tx 22"))
        assertTrue(CLIResponse.isPlausibleResponse("Alpha Repeater", "get name"))
        assertTrue(CLIResponse.isPlausibleResponse("regions saved", "region save"))
    }

    @Test
    fun `echoed wire prefix splits into prefix and body`() {
        val split = CLIResponse.splitEchoedPrefix("3A|> 22dBm (apc=off)")
        assertEquals("3A|", split?.first)
        assertEquals("> 22dBm (apc=off)", split?.second)
    }

    @Test
    fun `multi-line body survives prefix splitting`() {
        val split = CLIResponse.splitEchoedPrefix("0F|US/CA^\n  local F")
        assertEquals("US/CA^\n  local F", split?.second)
    }

    @Test
    fun `ordinary reply text is not mistaken for a wire prefix`() {
        // Only two uppercase hex digits plus the separator qualify.
        assertNull(CLIResponse.splitEchoedPrefix("> 22dBm (apc=off)"))
        assertNull(CLIResponse.splitEchoedPrefix("3a|lowercase"))
        assertNull(CLIResponse.splitEchoedPrefix("no|t hex"))
        assertNull(CLIResponse.splitEchoedPrefix("FF|"))
        assertNull(CLIResponse.splitEchoedPrefix(""))
    }
}
