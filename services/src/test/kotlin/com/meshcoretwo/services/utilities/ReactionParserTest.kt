// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of (a representative subset of) `ReactionParserTests.swift`. */
class ReactionParserTest {
    @Test
    fun `generateMessageHash is 8 Crockford Base32 chars`() {
        val hash = ReactionParser.generateMessageHash("hello world", 1_700_000_000u)
        assertEquals(8, hash.length)
        assertTrue(hash.all { it in "0123456789abcdefghjkmnpqrstvwxyz" })
    }

    @Test
    fun `generateMessageHash is deterministic for the same input`() {
        val a = ReactionParser.generateMessageHash("hello", 100u)
        val b = ReactionParser.generateMessageHash("hello", 100u)
        assertEquals(a, b)
    }

    @Test
    fun `generateMessageHash differs when text or timestamp changes`() {
        val base = ReactionParser.generateMessageHash("hello", 100u)
        assertTrue(base != ReactionParser.generateMessageHash("hellp", 100u))
        assertTrue(base != ReactionParser.generateMessageHash("hello", 101u))
    }

    @Test
    fun `parse round-trips a channel reaction built by buildReactionText-equivalent format`() {
        val hash = ReactionParser.generateMessageHash("target text", 42u)
        val text = "👍@[Alice]\n$hash"

        val parsed = ReactionParser.parse(text)!!

        assertEquals("👍", parsed.emoji)
        assertEquals("Alice", parsed.targetSender)
        assertEquals(hash, parsed.messageHash)
    }

    @Test
    fun `parse normalizes an uppercase hash suffix to lowercase canonical form`() {
        val hash = ReactionParser.generateMessageHash("x", 1u)
        val text = "👍@[Bob]\n${hash.uppercase()}"

        assertEquals(hash, ReactionParser.parse(text)!!.messageHash)
    }

    @Test
    fun `parse handles a sender name containing a colon`() {
        val hash = ReactionParser.generateMessageHash("x", 1u)
        val parsed = ReactionParser.parse("😀@[Room: General]\n$hash")!!
        assertEquals("Room: General", parsed.targetSender)
    }

    @Test
    fun `parse rejects text with no newline, no at-bracket, empty emoji, empty sender, or a bad hash`() {
        val hash = ReactionParser.generateMessageHash("x", 1u)
        assertNull(ReactionParser.parse("👍@[Alice]$hash")) // no newline
        assertNull(ReactionParser.parse("👍 Alice\n$hash")) // no "@["
        assertNull(ReactionParser.parse("@[Alice]\n$hash")) // empty emoji
        assertNull(ReactionParser.parse("👍@[]\n$hash")) // empty sender
        assertNull(ReactionParser.parse("👍@[Alice]\nabcdefu1")) // 'u' is not in the Crockford alphabet (no substitution)
        assertNull(ReactionParser.parse("👍@[Alice]\n${hash}9")) // 9 chars, not 8
    }

    @Test
    fun `parseDM round-trips buildDMReactionText`() {
        val text = ReactionParser.buildDMReactionText("❤️", "hi there", 999u)

        val parsed = ReactionParser.parseDM(text)!!

        assertEquals("❤️", parsed.emoji)
        assertEquals(ReactionParser.generateMessageHash("hi there", 999u), parsed.messageHash)
    }

    @Test
    fun `parseDM rejects channel-format text containing at-bracket`() {
        val hash = ReactionParser.generateMessageHash("x", 1u)
        assertNull(ReactionParser.parseDM("👍@[Alice]\n$hash"))
    }

    @Test
    fun `parseDM rejects an empty or non-emoji leading character`() {
        val hash = ReactionParser.generateMessageHash("x", 1u)
        assertNull(ReactionParser.parseDM("\n$hash"))
        assertNull(ReactionParser.parseDM("plain\n$hash"))
    }

    @Test
    fun `isReactionText matches the appropriate format per isDM`() {
        val channelText = "👍@[Alice]\n${ReactionParser.generateMessageHash("x", 1u)}"
        val dmText = ReactionParser.buildDMReactionText("👍", "x", 1u)

        assertTrue(ReactionParser.isReactionText(channelText, isDM = false))
        assertFalse(ReactionParser.isReactionText(channelText, isDM = true))
        assertTrue(ReactionParser.isReactionText(dmText, isDM = true))
        assertFalse(ReactionParser.isReactionText("just a normal message", isDM = false))
    }

    @Test
    fun `buildSummary sorts pairs by count descending`() {
        val summary = ReactionParser.buildSummary(listOf("😂" to 1, "👍" to 3, "❤️" to 2))
        assertEquals("👍:3,❤️:2,😂:1", summary)
    }

    @Test
    fun `parseSummary round-trips buildSummary`() {
        val parsed = ReactionParser.parseSummary("👍:3,❤️:2,😂:1")
        assertEquals(listOf("👍" to 3, "❤️" to 2, "😂" to 1), parsed)
    }

    @Test
    fun `parseSummary handles null, empty, and malformed input`() {
        assertEquals(emptyList<Pair<String, Int>>(), ReactionParser.parseSummary(null))
        assertEquals(emptyList<Pair<String, Int>>(), ReactionParser.parseSummary(""))
        assertEquals(listOf("👍" to 3), ReactionParser.parseSummary("👍:3,malformed"))
    }
}
