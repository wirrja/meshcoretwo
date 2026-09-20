// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

/**
 * Detects and normalizes `#name` hashtag-channel references in message text. Ported from
 * `HashtagUtilities.swift` (`MC1Services`) for the message-text linkifier
 * ([com.meshcoretwo.android.chat.linkify.MessageLinkTokenizer]) and reused by the existing
 * "join hashtag channel" composer input, which used to keep its own inline copy of the
 * sanitize step ([com.meshcoretwo.android.channels.sanitizeHashtag] before this slice).
 */
object HashtagUtilities {
    private val hashtagRegex = Regex("#[A-Za-z0-9][A-Za-z0-9-]*")

    /** One detected hashtag with its UTF-16-index range into the searched text, e.g. `"#general"`. */
    data class DetectedHashtag(val name: String, val range: IntRange)

    /**
     * Extracts every valid hashtag from [text], skipping ones inside [urlRanges] (an http/https
     * scan the caller already ran — passed in so callers that scan for URLs anyway, like
     * [com.meshcoretwo.android.chat.linkify.MessageLinkTokenizer], don't pay for a second one).
     */
    fun extractHashtags(text: String, urlRanges: List<IntRange>): List<DetectedHashtag> {
        if (text.isEmpty()) return emptyList()
        return hashtagRegex.findAll(text).mapNotNull { match ->
            val range = match.range
            if (urlRanges.any { range.first >= it.first && range.last <= it.last }) return@mapNotNull null
            DetectedHashtag(match.value, range)
        }.toList()
    }

    /** Standalone convenience for a caller with no URL scan of its own: finds http/https ranges itself. */
    fun extractHashtags(text: String): List<DetectedHashtag> = extractHashtags(text, findUrlRanges(text))

    /** True when [name] (without the leading `#`) starts alphanumeric and is otherwise `[A-Za-z0-9-]*`. */
    fun isValidHashtagName(name: String): Boolean {
        val first = name.firstOrNull() ?: return false
        if (!isAllowedHashtagNameChar(first, allowsHyphen = false)) return false
        return name.all { isAllowedHashtagNameChar(it, allowsHyphen = true) }
    }

    /** Live-sanitizes composer input: lowercases, drops disallowed characters, trims leading hyphens. */
    fun sanitizeHashtagNameInput(input: String): String =
        input.lowercase().filter { isAllowedHashtagNameChar(it, allowsHyphen = true) }.trimStart('-')

    /** Lowercases and strips a leading `#`, if present. */
    fun normalizeHashtagName(name: String): String =
        (if (name.startsWith("#")) name.substring(1) else name).lowercase()

    private fun isAllowedHashtagNameChar(char: Char, allowsHyphen: Boolean): Boolean = when {
        char in '0'..'9' || char in 'A'..'Z' || char in 'a'..'z' -> true
        char == '-' -> allowsHyphen
        else -> false
    }

    /** Rough stand-in for Swift's `NSDataDetector` link scan: http/https only, same as the tokenizer. */
    private val urlRegex = Regex("""https?://\S+""")

    private fun findUrlRanges(text: String): List<IntRange> = urlRegex.findAll(text).map { it.range }.toList()
}
