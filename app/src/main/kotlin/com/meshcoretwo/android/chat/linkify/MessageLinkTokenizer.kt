// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat.linkify

import com.meshcoretwo.services.contacts.ContactService
import com.meshcoretwo.services.utilities.HashtagUtilities
import com.meshcoretwo.services.utilities.MentionUtilities

/**
 * Detects every link kind [LinkToken.Kind] declares in one pass over a raw message body, merging
 * overlaps by that enum's fixed priority so the result is sorted and non-overlapping. Ported from
 * `MessageLinkTokenizer.swift`, trimmed to contact-share/mention/URL/hashtag/meshcore-channel-link
 * detection — see [LinkToken]'s doc for what's still deferred (`coordinate`) and why.
 *
 * Unlike the other three kinds, which run directly against the raw text and produce ranges into
 * it, `<pubkeyHex:type:name>` contact-share tokens and `@[name]` mention tokens go through a
 * normalizing pre-pass first — the same shape as iOS's `MessageTextNormalizer`, minus its
 * identity-color resolution (this port resolves a mention's color at render time instead, see
 * [LinkToken]'s doc): each valid token is rewritten — a contact share to its sanitized display
 * name, a mention to `@name` (dropping the `[]` wrapper) — before any other detector runs, so a
 * message renders "Alice" instead of the raw `<a1b2...64 hex chars...:1:Alice>` blob or `@[Alice]`
 * markup, and a name that happens to look like a URL/hashtag can't be double-detected by the later
 * passes (they only ever see the shrunk text). A mention whose original span falls inside a
 * contact-share token is skipped — a contact-share name is attacker-controlled and may itself
 * contain literal `@[...]` text, which the contact-share rewrite alone should own — matching
 * Swift's `excludedRanges` guard in `mentionReplacements`. [tokenize] therefore returns the
 * *display* string alongside the tokens, not just the tokens — every caller must render
 * [TokenizeResult.displayText], not the original.
 *
 * URL detection also autolinks a bare domain like `example.com` (no scheme), matching Swift's
 * `NSDataDetector` link type, which infers an `http://` scheme for exactly this case. The Android
 * platform has an equivalent (`android.util.Patterns.WEB_URL`, what `Linkify.WEB_URLS` uses
 * internally) but it isn't usable here: its static initializer throws under this module's plain
 * JUnit unit tests (no Robolectric — `services` uses it for Room/Context, `app` deliberately
 * doesn't), and adding that dependency just for this one pattern isn't worth losing this file's
 * fast, dependency-free tests. [urlRegex] + [commonTopLevelDomains] is a hand-rolled equivalent
 * instead: a `www.`-prefixed match is always accepted (that prefix alone is a strong enough
 * signal), while a plain `word.word` match is only accepted when its final label is a recognized
 * top-level domain — the same reason `Mr. Smith`/`e.g. this`/version strings like `9.3.0` don't get
 * linkified by `Patterns.WEB_URL` or `NSDataDetector` either: an unqualified run of prose with
 * periods needs a plausible TLD or `www.` before it reads as a domain. The TLD list is a curated
 * common subset, not the full IANA registry — a conscious, bounded approximation, not an attempt to
 * reimplement `Patterns.WEB_URL`'s real list.
 */
object MessageLinkTokenizer {
    /** [displayText] must be rendered instead of the string originally passed to [tokenize] — [tokens]'s ranges are into it, not the original. */
    data class TokenizeResult(val displayText: String, val tokens: List<LinkToken>)

    private val urlRegex = Regex(
        """(?i)(?:https?://[^\s<>"]+)""" +
            """|(?:\bwww\.[^\s<>"]+)""" +
            """|(?:\b[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+\b(?:/[^\s<>"]*)?)""",
    )
    private val commonTopLevelDomains = setOf(
        "com", "org", "net", "edu", "gov", "mil", "int",
        "io", "co", "me", "dev", "app", "xyz", "info", "biz", "name", "pro", "tech",
        "us", "uk", "ca", "au", "de", "fr", "es", "it", "nl", "se", "no", "fi", "dk",
        "ru", "cn", "jp", "kr", "in", "br", "mx", "za", "ch", "at", "be", "pl", "pt", "gr", "ie", "nz",
        "tv", "cc", "fm", "to", "ly", "sh", "gg", "ai", "gl",
    )
    private val meshcoreLinkRegex = Regex("""meshcore://[^\s<>"]+""")
    private val meshcoreHostRegex = Regex("""^meshcore://([^/?#]+)""")

    /** Bidi-control code points stripped from a contact-share display name — a name is
     * attacker-controlled (the sender's advertised name), so a bidi override could otherwise
     * visually disguise the name shown before "Add". Not the full set Swift's
     * `Unicode.Scalar.Properties.isBidiControl`/`isDefaultIgnorableCodePoint` strip — `Character`
     * has no equivalent property table — but covers the characters actually capable of reordering
     * or hiding text (LRM/RLM/embeddings/overrides/isolates/ALM). */
    private val bidiControlCodePoints = setOf(
        0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x061C,
    )
    private const val TRIMMABLE_TRAILING_PUNCTUATION = ".,;:!?)"

    fun tokenize(text: String): TokenizeResult {
        val (displayText, preTokens) = normalizePrePass(text)
        val urls = detectUrls(displayText)
        val hits = ArrayList<LinkToken>(preTokens.size + urls.size)
        hits += preTokens
        hits += urls
        hits += detectHashtags(displayText, urlRanges = urls.map { it.range })
        hits += detectMeshcoreChannelLinks(displayText)
        return TokenizeResult(displayText, resolveOverlaps(hits))
    }

    // MARK: - Normalizing pre-pass (contact share + mention)

    /** One pending rewrite discovered on the original string, carrying the replacement text and the
     * token to emit once its final range in the rewritten string is known. */
    private data class Replacement(val originalRange: IntRange, val replacementText: String, val kind: LinkToken.Kind, val value: String)

    /**
     * Rewrites every valid `<pubkeyHex:type:name>` contact-share token to its sanitized display
     * name and every `@[name]` mention to `@name`, in one left-to-right pass over [text] — both
     * kinds of replacement are collected from the *original* string first and sorted by position,
     * so a rewrite earlier in the string can't desync a later one's range (the same reason Swift's
     * `MessageTextNormalizer` does both rewrites in one combined pass rather than two sequential
     * ones). An invalid contact-share token (bad hex/type/empty name, per
     * [ContactService.parseContactShareToken]) or one that sanitizes to an empty name is left as
     * literal, unlinked text — same as Swift keeping the literal token rather than emitting an
     * empty, invisible chip.
     */
    private fun normalizePrePass(text: String): Pair<String, List<LinkToken>> {
        val contactShareRanges = if (text.contains('<')) ContactService.contactShareTokenRegex.findAll(text).map { it.range }.toList() else emptyList()

        val replacements = ArrayList<Replacement>()
        for (range in contactShareRanges) {
            val match = text.substring(range.first, range.last + 1)
            val result = ContactService.parseContactShareToken(match) ?: continue
            val displayName = sanitizeContactShareName(result.name)
            if (displayName.isEmpty()) continue
            // A [ContactService.exportContactURI] `meshcore://contact/add` URI would read more
            // naturally as this token's value, but building one goes through `android.net.Uri`,
            // which isn't mocked under this module's plain JUnit tests (no Robolectric — see this
            // object's doc). [ContactService.formatContactShareToken] is the pure-Kotlin
            // equivalent and round-trips through the matching [ContactService
            // .parseContactShareToken] `AddContactScreen` uses to prefill from it.
            val shareToken = ContactService.formatContactShareToken(displayName, result.publicKey, result.contactType)
            replacements += Replacement(range, displayName, LinkToken.Kind.CONTACT_SHARE, shareToken)
        }
        if (text.contains('@')) {
            for (match in MentionUtilities.mentionRegex.findAll(text)) {
                if (contactShareRanges.any { it overlaps match.range }) continue
                val name = match.groupValues[1]
                replacements += Replacement(match.range, "@$name", LinkToken.Kind.MENTION, name)
            }
        }
        if (replacements.isEmpty()) return text to emptyList()
        replacements.sortBy { it.originalRange.first }

        val displayText = StringBuilder()
        val tokens = ArrayList<LinkToken>(replacements.size)
        var cursor = 0
        for (replacement in replacements) {
            displayText.append(text, cursor, replacement.originalRange.first)
            val start = displayText.length
            displayText.append(replacement.replacementText)
            val end = displayText.length
            tokens += LinkToken(start until end, replacement.kind, replacement.value)
            cursor = replacement.originalRange.last + 1
        }
        displayText.append(text, cursor, text.length)
        return displayText.toString() to tokens
    }

    private fun sanitizeContactShareName(name: String): String = buildString {
        var i = 0
        while (i < name.length) {
            val codePoint = name.codePointAt(i)
            if (!isStrippableCodePoint(codePoint)) appendCodePoint(codePoint)
            i += Character.charCount(codePoint)
        }
    }

    private fun isStrippableCodePoint(codePoint: Int): Boolean {
        if (codePoint in bidiControlCodePoints) return true
        return when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            else -> false
        }
    }

    // MARK: - URLs

    private fun detectUrls(text: String): List<LinkToken> =
        urlRegex.findAll(text).mapNotNull { match ->
            val range = trimTrailingPunctuation(text, match.range) ?: return@mapNotNull null
            val matched = text.substring(range.first, range.last + 1)
            val value = when {
                matched.startsWith("http://", ignoreCase = true) || matched.startsWith("https://", ignoreCase = true) -> matched
                matched.contains("://") -> return@mapNotNull null
                matched.startsWith("www.", ignoreCase = true) -> "http://$matched"
                isRecognizedBareDomain(matched) -> "http://$matched"
                else -> return@mapNotNull null
            }
            LinkToken(range, LinkToken.Kind.URL, value)
        }.toList()

    private fun isRecognizedBareDomain(matched: String): Boolean {
        val host = matched.substringBefore('/')
        val tld = host.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return tld in commonTopLevelDomains
    }

    private fun detectHashtags(text: String, urlRanges: List<IntRange>): List<LinkToken> =
        HashtagUtilities.extractHashtags(text, urlRanges).map { hashtag ->
            LinkToken(hashtag.range, LinkToken.Kind.HASHTAG, hashtag.name)
        }

    /** Only `meshcore://channel/...` links are tokenized — `contact`/`map` links have no tap
     * destination yet (see [LinkToken]'s doc), so they're left as plain, unlinked text. */
    private fun detectMeshcoreChannelLinks(text: String): List<LinkToken> =
        meshcoreLinkRegex.findAll(text).mapNotNull { match ->
            val range = trimTrailingPunctuation(text, match.range) ?: return@mapNotNull null
            val candidate = text.substring(range.first, range.last + 1)
            val host = meshcoreHostRegex.find(candidate)?.groupValues?.get(1)
            if (host != "channel") return@mapNotNull null
            LinkToken(range, LinkToken.Kind.MESHCORE_CHANNEL_LINK, candidate)
        }.toList()

    /** Strips trailing punctuation a greedy regex may over-capture; `null` if nothing is left. */
    private fun trimTrailingPunctuation(text: String, range: IntRange): IntRange? {
        var end = range.last
        while (end >= range.first && text[end] in TRIMMABLE_TRAILING_PUNCTUATION) end--
        return if (end < range.first) null else range.first..end
    }

    /**
     * Accepts detections highest-priority first, dropping any that overlaps one already accepted,
     * then returns the survivors in document order — same greedy merge as the iOS source's
     * `resolveOverlaps`.
     */
    private fun resolveOverlaps(tokens: List<LinkToken>): List<LinkToken> {
        val byPriority = tokens.sortedWith(compareBy({ it.kind.ordinal }, { it.range.first }))
        val accepted = ArrayList<LinkToken>(byPriority.size)
        for (token in byPriority) {
            if (accepted.none { it.range overlaps token.range }) accepted += token
        }
        return accepted.sortedBy { it.range.first }
    }

    private infix fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
}
