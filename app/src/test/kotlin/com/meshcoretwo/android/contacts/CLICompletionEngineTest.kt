// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [CLICompletionEngine], the remote-session-only subset of `CLICompletionEngineTests.swift`
 * — see the class doc there for which Swift cases (local-session vocabulary, `session`/`login`,
 * custom-var keys) don't apply and are intentionally not ported here.
 */
class CLICompletionEngineTest {
    @Test
    fun `empty input returns all commands`() {
        val suggestions = CLICompletionEngine.completions("")
        assertTrue(suggestions.contains("help"))
        assertTrue(suggestions.contains("clear"))
        assertTrue(suggestions.contains("ver"))
    }

    @Test
    fun `partial command returns matching commands`() {
        assertEquals(listOf("help"), CLICompletionEngine.completions("hel"))
    }

    @Test
    fun `repeater commands available`() {
        assertTrue(CLICompletionEngine.completions("v").contains("ver"))
    }

    @Test
    fun `no app-CLI session commands offered`() {
        val suggestions = CLICompletionEngine.completions("")
        assertFalse(suggestions.contains("session"))
        assertFalse(suggestions.contains("logout"))
        assertFalse(suggestions.contains("login"))
    }

    @Test
    fun `log prefix does not suggest logout`() {
        val suggestions = CLICompletionEngine.completions("log")
        assertFalse(suggestions.contains("logout"))
        assertTrue(suggestions.contains("log"))
    }

    @Test
    fun `region subcommands complete after region space`() {
        val suggestions = CLICompletionEngine.completions("region ")
        assertTrue(suggestions.containsAll(listOf("load", "get", "put", "home", "default", "save", "list")))
    }

    @Test
    fun `region list completes with allowed and denied`() {
        assertEquals(listOf("allowed", "denied"), CLICompletionEngine.completions("region list "))
    }

    @Test
    fun `region returns empty after subcommand complete`() {
        assertTrue(CLICompletionEngine.completions("region load ").isEmpty())
    }

    @Test
    fun `gps subcommands complete after gps space`() {
        val suggestions = CLICompletionEngine.completions("gps ")
        assertTrue(suggestions.containsAll(listOf("on", "off", "sync", "advert")))
    }

    @Test
    fun `gps advert values complete for third argument`() {
        val suggestions = CLICompletionEngine.completions("gps advert ")
        assertEquals(listOf("none", "prefs", "share"), suggestions)
    }

    @Test
    fun `gps advert filters third argument by prefix`() {
        assertEquals(listOf("share"), CLICompletionEngine.completions("gps advert s"))
    }

    @Test
    fun `get completes all parameters excluding serial-only`() {
        val suggestions = CLICompletionEngine.completions("get ")
        assertTrue(suggestions.contains("name"))
        assertTrue(suggestions.contains("flood.max"))
        assertFalse(suggestions.contains("prv.key"))
        assertFalse(suggestions.contains("acl"))
        assertTrue(suggestions.contains("freq"))
    }

    @Test
    fun `get returns empty after parameter complete`() {
        assertTrue(CLICompletionEngine.completions("get name ").isEmpty())
    }

    @Test
    fun `set excludes serial-only param freq`() {
        val suggestions = CLICompletionEngine.completions("set ")
        assertFalse(suggestions.contains("freq"))
        assertTrue(suggestions.contains("prv.key"))
    }

    @Test
    fun `set loop detect suggests values`() {
        assertEquals(listOf("minimal", "moderate", "off", "strict"), CLICompletionEngine.completions("set loop.detect "))
    }

    @Test
    fun `set loop detect filters values by prefix`() {
        assertEquals(listOf("minimal", "moderate"), CLICompletionEngine.completions("set loop.detect m"))
    }

    @Test
    fun `set path hash mode suggests values`() {
        assertEquals(listOf("0", "1", "2"), CLICompletionEngine.completions("set path.hash.mode "))
    }

    @Test
    fun `set returns empty after value complete`() {
        assertTrue(CLICompletionEngine.completions("set repeat on ").isEmpty())
    }

    @Test
    fun `set on off value groups`() {
        assertEquals(listOf("off", "on"), CLICompletionEngine.completions("set repeat "))
        assertEquals(listOf("off", "on"), CLICompletionEngine.completions("set allow.read.only "))
        assertEquals(listOf("off", "on"), CLICompletionEngine.completions("set bridge.enabled "))
        assertEquals(listOf("off", "on"), CLICompletionEngine.completions("set radio.rxgain "))
    }

    @Test
    fun `set multi acks suggests 0 1`() {
        assertEquals(listOf("0", "1"), CLICompletionEngine.completions("set multi.acks "))
    }

    @Test
    fun `set bridge source suggests tx rx`() {
        assertEquals(listOf("rx", "tx"), CLICompletionEngine.completions("set bridge.source "))
    }

    @Test
    fun `sensor subcommands complete`() {
        assertEquals(listOf("get", "list", "set"), CLICompletionEngine.completions("sensor "))
    }

    @Test
    fun `clear subcommands complete`() {
        assertTrue(CLICompletionEngine.completions("clear ").contains("stats"))
    }

    @Test
    fun `clear returns empty after stats complete`() {
        assertTrue(CLICompletionEngine.completions("clear stats ").isEmpty())
    }

    @Test
    fun `log subcommands complete and filter by prefix`() {
        val suggestions = CLICompletionEngine.completions("log st")
        assertTrue(suggestions.contains("start"))
        assertTrue(suggestions.contains("stop"))
        assertFalse(suggestions.contains("erase"))
    }

    @Test
    fun `log returns empty after subcommand complete`() {
        assertTrue(CLICompletionEngine.completions("log start ").isEmpty())
    }

    @Test
    fun `powersaving values complete and filter`() {
        assertEquals(listOf("off", "on"), CLICompletionEngine.completions("powersaving "))
        assertEquals(listOf("off", "on"), CLICompletionEngine.completions("powersaving o"))
    }

    @Test
    fun `powersaving returns empty after value complete`() {
        assertTrue(CLICompletionEngine.completions("powersaving on ").isEmpty())
    }

    @Test
    fun `clock subcommands complete`() {
        assertTrue(CLICompletionEngine.completions("clock ").contains("sync"))
    }

    @Test
    fun `clock returns empty after subcommand complete`() {
        assertTrue(CLICompletionEngine.completions("clock sync ").isEmpty())
    }

    @Test
    fun `start ota subcommand completes and closes`() {
        assertEquals(listOf("ota"), CLICompletionEngine.completions("start "))
        assertTrue(CLICompletionEngine.completions("start ota ").isEmpty())
    }

    @Test
    fun `advert zerohop and discover neighbors appear in suggestions`() {
        val advert = CLICompletionEngine.completions("advert")
        assertTrue(advert.contains("advert"))
        assertTrue(advert.contains("advert.zerohop"))
        assertTrue(CLICompletionEngine.completions("disc").contains("discover.neighbors"))
    }

    @Test
    fun `uppercase input still respects arity and case-folds`() {
        assertTrue(CLICompletionEngine.completions("GPS ADVERT ").contains("share"))
        assertTrue(CLICompletionEngine.completions("LOG START ").isEmpty())
    }
}
