// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HashtagUtilitiesTest {
    @Test
    fun `extractHashtags finds single hashtag`() {
        val result = HashtagUtilities.extractHashtags("Join #general today")
        assertEquals(listOf("#general"), result.map { it.name })
    }

    @Test
    fun `extractHashtags accepts uppercase hashtags`() {
        val result = HashtagUtilities.extractHashtags("Join #General today")
        assertEquals(listOf("#General"), result.map { it.name })
    }

    @Test
    fun `extractHashtags finds multiple hashtags`() {
        val result = HashtagUtilities.extractHashtags("Try #one and #two")
        assertEquals(listOf("#one", "#two"), result.map { it.name })
    }

    @Test
    fun `extractHashtags returns empty for no hashtags`() {
        assertTrue(HashtagUtilities.extractHashtags("No hashtags here").isEmpty())
    }

    @Test
    fun `extractHashtags excludes hashtags inside URLs`() {
        val result = HashtagUtilities.extractHashtags("See https://example.com#section and #general")
        assertEquals(listOf("#general"), result.map { it.name })
    }

    @Test
    fun `extractHashtags handles hashtag at end with punctuation`() {
        val result = HashtagUtilities.extractHashtags("Join #general.")
        assertEquals("#general", result.single().name)
    }

    @Test
    fun `extractHashtags handles adjacent hashtags`() {
        assertEquals(2, HashtagUtilities.extractHashtags("#one#two").size)
    }

    @Test
    fun `isValidHashtagName accepts valid names`() {
        for (name in listOf("general", "General", "TEST", "test-channel", "abc123", "a")) {
            assertTrue(name, HashtagUtilities.isValidHashtagName(name))
        }
    }

    @Test
    fun `isValidHashtagName rejects invalid names`() {
        for (name in listOf("", "-bad", "test_underscore", "test.dot", "bad!")) {
            assertFalse(name, HashtagUtilities.isValidHashtagName(name))
        }
    }

    @Test
    fun `normalizeHashtagName lowercases and strips prefix`() {
        assertEquals("general", HashtagUtilities.normalizeHashtagName("#General"))
        assertEquals("test", HashtagUtilities.normalizeHashtagName("#TEST"))
        assertEquals("general", HashtagUtilities.normalizeHashtagName("general"))
    }

    @Test
    fun `sanitizeHashtagNameInput lowercases and strips invalid characters`() {
        assertEquals("general", HashtagUtilities.sanitizeHashtagNameInput("General"))
        assertEquals("general", HashtagUtilities.sanitizeHashtagNameInput("-General"))
        assertEquals("general", HashtagUtilities.sanitizeHashtagNameInput("gen_eral"))
    }
}
