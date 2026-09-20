// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from `ChannelMessageFormatTests` (embedded in `HeardRepeatsServiceTests.swift`). */
class ChannelMessageFormatTest {
    @Test
    fun `parse with valid format returns sender and message`() {
        val result = ChannelMessageFormat.parse("NodeName: Hello world")

        assertEquals("NodeName", result?.first)
        assertEquals("Hello world", result?.second)
    }

    @Test
    fun `parse with no colon returns null`() {
        assertNull(ChannelMessageFormat.parse("No colon here"))
    }

    @Test
    fun `parse with colon at start returns null`() {
        assertNull(ChannelMessageFormat.parse(": Message without sender"))
    }

    @Test
    fun `parse with empty message returns empty text`() {
        val result = ChannelMessageFormat.parse("Sender:")

        assertEquals("Sender", result?.first)
        assertEquals("", result?.second)
    }

    @Test
    fun `parse with message containing colons only splits on first`() {
        val result = ChannelMessageFormat.parse("Sender: Time is 10:30:00")

        assertEquals("Sender", result?.first)
        assertEquals("Time is 10:30:00", result?.second)
    }

    @Test
    fun `parse trims whitespace from message`() {
        val result = ChannelMessageFormat.parse("Node:   Padded message   ")

        assertEquals("Padded message", result?.second)
    }

    @Test
    fun `parse preserves spaces in sender name`() {
        val result = ChannelMessageFormat.parse("Node With Spaces: Message")

        assertEquals("Node With Spaces", result?.first)
    }
}
