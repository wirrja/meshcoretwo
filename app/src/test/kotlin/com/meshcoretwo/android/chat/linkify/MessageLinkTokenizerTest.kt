// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat.linkify

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.contacts.ContactService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLinkTokenizerTest {
    private val samplePublicKeyHex = "a1".repeat(32)

    @Test
    fun `detects a plain http url`() {
        val tokens = MessageLinkTokenizer.tokenize("check https://example.com/path please").tokens
        assertEquals(listOf(LinkToken(6..29, LinkToken.Kind.URL, "https://example.com/path")), tokens)
    }

    @Test
    fun `trims trailing sentence punctuation off a url`() {
        val tokens = MessageLinkTokenizer.tokenize("See https://example.com.").tokens
        assertEquals("https://example.com", tokens.single().value)
    }

    @Test
    fun `linkifies a bare domain without a scheme, inferring http`() {
        val tokens = MessageLinkTokenizer.tokenize("visit example.com today").tokens
        val token = tokens.single()
        assertEquals(6..16, token.range)
        assertEquals("http://example.com", token.value)
    }

    @Test
    fun `linkifies a bare www domain without a scheme`() {
        val tokens = MessageLinkTokenizer.tokenize("see www.example.com now").tokens
        assertEquals("http://www.example.com", tokens.single().value)
    }

    @Test
    fun `does not linkify ordinary sentence text with periods`() {
        assertTrue(MessageLinkTokenizer.tokenize("Mr. Smith said e.g. this is fine").tokens.isEmpty())
    }

    @Test
    fun `an explicit https scheme on a domain is kept as-is, not doubled`() {
        val tokens = MessageLinkTokenizer.tokenize("visit https://example.com today").tokens
        assertEquals("https://example.com", tokens.single().value)
    }

    @Test
    fun `detects a hashtag`() {
        val tokens = MessageLinkTokenizer.tokenize("join #general now").tokens
        assertEquals(listOf(LinkToken(5..12, LinkToken.Kind.HASHTAG, "#general")), tokens)
    }

    @Test
    fun `does not detect a hashtag fragment inside a url`() {
        val tokens = MessageLinkTokenizer.tokenize("see https://example.com#section and #general").tokens
        assertEquals(listOf(LinkToken.Kind.URL, LinkToken.Kind.HASHTAG), tokens.map { it.kind })
        assertEquals("#general", tokens.last().value)
    }

    @Test
    fun `detects a meshcore channel add link`() {
        val text = "join meshcore://channel/add?name=Test&secret=aabb here"
        val tokens = MessageLinkTokenizer.tokenize(text).tokens
        val token = tokens.single()
        assertEquals(LinkToken.Kind.MESHCORE_CHANNEL_LINK, token.kind)
        assertEquals("meshcore://channel/add?name=Test&secret=aabb", token.value)
    }

    @Test
    fun `ignores a meshcore link whose host is not channel`() {
        val tokens = MessageLinkTokenizer.tokenize("meshcore://contact/add?name=Bob&public_key=aa").tokens
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `a hashtag inside a meshcore link wins the overlap, leaving the link unstyled`() {
        val text = "meshcore://channel/add?name=#general"
        val tokens = MessageLinkTokenizer.tokenize(text).tokens
        assertEquals(listOf(LinkToken.Kind.HASHTAG), tokens.map { it.kind })
        assertEquals("#general", tokens.single().value)
    }

    @Test
    fun `returns no tokens for plain text`() {
        assertTrue(MessageLinkTokenizer.tokenize("no links here").tokens.isEmpty())
    }

    @Test
    fun `normalizes a contact share token to its display name and links it`() {
        val text = "look who I found: <$samplePublicKeyHex:2:Repeater7>"
        val result = MessageLinkTokenizer.tokenize(text)

        assertEquals("look who I found: Repeater7", result.displayText)
        val token = result.tokens.single()
        assertEquals(LinkToken.Kind.CONTACT_SHARE, token.kind)
        val expectedStart = result.displayText.indexOf("Repeater7")
        assertEquals(expectedStart until expectedStart + "Repeater7".length, token.range)
        assertEquals(
            ContactType.REPEATER,
            ContactService.parseContactShareToken(token.value)?.contactType,
        )
    }

    @Test
    fun `a contact share token shadows a url or hashtag it happens to contain`() {
        // The name field is attacker-controlled free text; it can itself look like a hashtag.
        val text = "<$samplePublicKeyHex:1:#general>"
        val result = MessageLinkTokenizer.tokenize(text)

        assertEquals("#general", result.displayText)
        assertEquals(listOf(LinkToken.Kind.CONTACT_SHARE), result.tokens.map { it.kind })
    }

    @Test
    fun `leaves an invalid contact share token as literal unlinked text`() {
        val text = "<not-a-valid-token:1:Bob>"
        val result = MessageLinkTokenizer.tokenize(text)

        assertEquals(text, result.displayText)
        assertTrue(result.tokens.isEmpty())
    }

    @Test
    fun `strips a bidi override from a contact share display name`() {
        val text = "<$samplePublicKeyHex:1:Ali‮ce>"
        val result = MessageLinkTokenizer.tokenize(text)

        assertEquals("Alice", result.displayText)
    }

    @Test
    fun `detects urls and hashtags against the normalized text, ranges included`() {
        val text = "<$samplePublicKeyHex:1:Bob> shared https://example.com and #general"
        val result = MessageLinkTokenizer.tokenize(text)

        assertEquals("Bob shared https://example.com and #general", result.displayText)
        assertEquals(
            listOf(LinkToken.Kind.CONTACT_SHARE, LinkToken.Kind.URL, LinkToken.Kind.HASHTAG),
            result.tokens.map { it.kind },
        )
    }

    @Test
    fun `normalizes a mention to its name, dropping the bracket markup`() {
        val result = MessageLinkTokenizer.tokenize("hey @[Alice] look at this")

        // The `@` itself stays in the display text (and in the token's range) — only the `[]`
        // wrapper is dropped, matching Swift's `mentionReplacements`' `"@\(name)"` replacement.
        assertEquals("hey @Alice look at this", result.displayText)
        val token = result.tokens.single()
        assertEquals(LinkToken.Kind.MENTION, token.kind)
        assertEquals("Alice", token.value)
        assertEquals(4..9, token.range)
    }

    @Test
    fun `does not detect a plain at-sign without brackets as a mention`() {
        assertTrue(MessageLinkTokenizer.tokenize("email me at bob@example.com").tokens.none { it.kind == LinkToken.Kind.MENTION })
    }

    @Test
    fun `detects two distinct mentions in one message`() {
        val result = MessageLinkTokenizer.tokenize("@[Alice] and @[Bob] should see this")

        assertEquals(listOf("Alice", "Bob"), result.tokens.filter { it.kind == LinkToken.Kind.MENTION }.map { it.value })
    }

    @Test
    fun `a mention name that looks like a hashtag or url is not double-detected`() {
        val result = MessageLinkTokenizer.tokenize("@[#general] said hi")

        assertEquals("@#general said hi", result.displayText)
        assertEquals(listOf(LinkToken.Kind.MENTION), result.tokens.map { it.kind })
        assertEquals("#general", result.tokens.single().value)
    }

    @Test
    fun `a mention inside a contact share name is left to the contact share token, not double-detected`() {
        // The contact-share name is attacker-controlled free text and can itself contain `@[...]`.
        val text = "<$samplePublicKeyHex:1:@[Alice]>"
        val result = MessageLinkTokenizer.tokenize(text)

        assertEquals("@[Alice]", result.displayText)
        assertEquals(listOf(LinkToken.Kind.CONTACT_SHARE), result.tokens.map { it.kind })
    }

    @Test
    fun `a mention and a later contact share both normalize correctly in one message`() {
        val text = "@[Alice] shared <$samplePublicKeyHex:1:Bob>"
        val result = MessageLinkTokenizer.tokenize(text)

        assertEquals("@Alice shared Bob", result.displayText)
        assertEquals(listOf(LinkToken.Kind.MENTION, LinkToken.Kind.CONTACT_SHARE), result.tokens.map { it.kind })
        assertEquals("Alice", result.tokens.first().value)
    }
}
