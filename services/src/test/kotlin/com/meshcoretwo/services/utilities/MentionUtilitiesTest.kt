// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.persistence.ContactDto
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionUtilitiesTest {
    private fun contactDto(name: String, nickname: String? = null, type: ContactType = ContactType.CHAT) = ContactDto(
        id = UUID.randomUUID(), radioID = UUID.randomUUID(), publicKey = ByteArray(32), name = name,
        typeRawValue = type.value, flags = 0u, outPathLength = 0xFFu, outPath = ByteArray(0),
        lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0, lastModified = 0u, lastHeardTimestamp = 0u,
        nickname = nickname, isBlocked = false, isMuted = false, isFavorite = false, lastMessageDate = null,
        unreadCount = 0, unreadMentionCount = 0, ocvPreset = null, customOCVArrayString = null, avatarImageData = null,
    )
    @Test
    fun `extractMentions finds every mention, ignoring plain @-mentions without brackets`() {
        val mentions = MentionUtilities.extractMentions("Hey @[Alice] and @[Bob], not @Carol though")

        assertEquals(listOf("Alice", "Bob"), mentions)
    }

    @Test
    fun `extractMentions returns an empty list when there are no mentions`() {
        assertTrue(MentionUtilities.extractMentions("no mentions here").isEmpty())
    }

    @Test
    fun `containsSelfMention matches case-insensitively`() {
        assertTrue(MentionUtilities.containsSelfMention("hi @[Alice], how are you?", "alice"))
        assertTrue(MentionUtilities.containsSelfMention("hi @[ALICE]", "Alice"))
    }

    @Test
    fun `containsSelfMention is false when the name isn't mentioned`() {
        assertFalse(MentionUtilities.containsSelfMention("hi @[Bob]", "Alice"))
        assertFalse(MentionUtilities.containsSelfMention("no mentions here", "Alice"))
    }

    @Test
    fun `createMention wraps a name in the mention format`() {
        assertEquals("@[Alice]", MentionUtilities.createMention("Alice"))
    }

    @Test
    fun `detectActiveMention returns null when there is no active mention`() {
        assertNull(MentionUtilities.detectActiveMention(""))
        assertNull(MentionUtilities.detectActiveMention("no mention here"))
        assertNull(MentionUtilities.detectActiveMention("email@example.com"))
    }

    @Test
    fun `detectActiveMention returns the empty string for a standalone at-sign`() {
        assertEquals("", MentionUtilities.detectActiveMention("hi @"))
    }

    @Test
    fun `detectActiveMention returns the partial query being typed`() {
        assertEquals("Al", MentionUtilities.detectActiveMention("hi @Al"))
        assertEquals("Al", MentionUtilities.detectActiveMention("@Al"))
    }

    @Test
    fun `detectActiveMention still returns the trailing word after the last at-sign, even once whitespace follows it`() {
        // Faithful to Swift: the function only ever looks at the rightmost `@` in the whole
        // string and takes the non-whitespace run after it — it has no notion of "the query
        // ended because the user kept typing", so "Alice" stays the query until another `@`
        // appears later in the text (or the existing one is edited/removed).
        assertEquals("Alice", MentionUtilities.detectActiveMention("hi @Alice "))
        assertEquals("Alice", MentionUtilities.detectActiveMention("hi @Alice and then some more words"))
    }

    @Test
    fun `detectActiveMention ignores a completed bracket mention and finds a later query`() {
        assertEquals("Bo", MentionUtilities.detectActiveMention("hi @[Alice] @Bo"))
    }

    @Test
    fun `detectActiveMention returns null after a completed bracket mention with no further at-sign`() {
        assertNull(MentionUtilities.detectActiveMention("hi @[Alice] typing more"))
        assertNull(MentionUtilities.detectActiveMention("hi @[Alice]"))
    }

    @Test
    fun `detectActiveMention returns null for an unclosed bracket mention`() {
        assertNull(MentionUtilities.detectActiveMention("hi @[Ali"))
    }

    @Test
    fun `filterContacts keeps only chat-type contacts matching the query, sorted alphabetically`() {
        val alice = contactDto("Alice")
        val bob = contactDto("Bob")
        val repeaterNamedAl = contactDto("Alpine Repeater", type = ContactType.REPEATER)

        val result = MentionUtilities.filterContacts(listOf(bob, alice, repeaterNamedAl), query = "al")

        assertEquals(listOf(alice), result)
    }

    @Test
    fun `filterContacts with an empty query returns every chat contact alphabetically`() {
        val alice = contactDto("Alice")
        val bob = contactDto("Bob")

        val result = MentionUtilities.filterContacts(listOf(bob, alice), query = "")

        assertEquals(listOf(alice, bob), result)
    }

    @Test
    fun `filterContacts matches against the nickname when set`() {
        val contact = contactDto("Alice", nickname = "Ace")

        assertTrue(MentionUtilities.filterContacts(listOf(contact), query = "ace").contains(contact))
        assertTrue(MentionUtilities.filterContacts(listOf(contact), query = "alice").isEmpty())
    }

    @Test
    fun `appendMention on an empty draft is just the mention plus a trailing space`() {
        assertEquals("@[Alice] ", MentionUtilities.appendMention("Alice", ""))
    }

    @Test
    fun `appendMention adds a separating space when the draft doesn't already end in whitespace`() {
        assertEquals("hey @[Alice] ", MentionUtilities.appendMention("Alice", "hey"))
    }

    @Test
    fun `appendMention adds no extra separator when the draft already ends in whitespace`() {
        assertEquals("hey @[Alice] ", MentionUtilities.appendMention("Alice", "hey "))
    }

    @Test
    fun `buildReplyText mentions the sender then previews up to 10 characters of the message`() {
        assertEquals("@[Alice]\n>short\n", MentionUtilities.buildReplyText("Alice", "short"))
        assertEquals("@[Alice]\n>0123456789..\n", MentionUtilities.buildReplyText("Alice", "0123456789 and then some more"))
    }

    @Test
    fun `buildReplyText strips the quoted message's own leading mention from the preview`() {
        assertEquals("@[Alice]\n>hello\n", MentionUtilities.buildReplyText("Alice", "@[Bob] hello"))
    }
}
