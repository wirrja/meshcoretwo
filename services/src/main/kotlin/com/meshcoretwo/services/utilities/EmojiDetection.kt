// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import java.text.BreakIterator

/**
 * Approximates Swift's `Character.isEmoji` (`Character+EmojiDetection.swift`): `scalar.properties
 * .isEmoji && (scalar.value > 0x238C || unicodeScalars.count > 1)`, evaluated on the *first
 * user-perceived character* (extended grapheme cluster) of a string.
 *
 * Swift gets grapheme-cluster segmentation and the full Unicode `Emoji` property table for free.
 * Kotlin/JVM has neither built in: [BreakIterator.getCharacterInstance] (standard JDK, no extra
 * dependency) gives equivalent grapheme-cluster segmentation, but there's no JDK API for the
 * `Emoji` property, so [isEmojiCodePoint] hand-rolls a range table over the common emoji blocks
 * instead of the full `emoji-data.txt`. Known gap: a single-code-point keycap base (e.g. the
 * bare digit in `"1️⃣"`) isn't in any of these ranges, so a keycap sequence's *first* code point
 * fails the range check even though Swift's `scalar.properties.isEmoji` would pass it (with the
 * multi-scalar rule then confirming it) — full parity would need that property table, which this
 * module doesn't carry.
 */
internal object EmojiDetection {
    /** Whether [text] starts with an emoji character, by the approximation above. */
    fun startsWithEmoji(text: String): Boolean {
        if (text.isEmpty()) return false
        val boundary = BreakIterator.getCharacterInstance()
        boundary.setText(text)
        val end = boundary.next()
        if (end == BreakIterator.DONE) return false

        val firstGrapheme = text.substring(0, end)
        val codePointCount = firstGrapheme.codePointCount(0, firstGrapheme.length)
        val firstCodePoint = firstGrapheme.codePointAt(0)
        return isEmojiCodePoint(firstCodePoint) && (firstCodePoint > 0x238C || codePointCount > 1)
    }

    private fun isEmojiCodePoint(codePoint: Int): Boolean = EMOJI_RANGES.any { codePoint in it }

    // Common blocks carrying Unicode's Emoji property. Not exhaustive (see class doc).
    private val EMOJI_RANGES = listOf(
        0x203C..0x203C, // ‼
        0x2049..0x2049, // ⁉
        0x2122..0x2122, // ™
        0x2139..0x2139, // ℹ
        0x2194..0x21AA, // arrows
        0x231A..0x231B, // ⌚⌛
        0x2328..0x2328,
        0x23CF..0x23CF,
        0x23E9..0x23FA,
        0x24C2..0x24C2,
        0x25AA..0x25FE,
        0x2600..0x27BF, // misc symbols + dingbats (☀️.. ➿)
        0x2934..0x2935,
        0x2B00..0x2BFF, // stars, arrows
        0x3030..0x3030,
        0x303D..0x303D,
        0x3297..0x3297,
        0x3299..0x3299,
        0x1F000..0x1FFFF, // all supplementary emoji planes: emoticons, transport, symbols, etc.
    )
}
