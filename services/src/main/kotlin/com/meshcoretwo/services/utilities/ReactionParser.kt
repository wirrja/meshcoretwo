// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Parses and builds MeshCore's native emoji-reaction wire format, and the
 * `"👍:3,❤️:2"`-style summary string cached on
 * [com.meshcoretwo.services.persistence.MessageDto.reactionSummary]. Ported from
 * `ReactionParser.swift`.
 *
 * The third-party "meshcore-open" reaction formats (`MeshCoreOpenReactionParser.swift` — a
 * different wire encoding plus a reimplementation of Dart's `String.hashCode`, for interop with
 * a different client app) are **not** ported — see
 * [com.meshcoretwo.services.reactions.ReactionService]'s class doc for the full MVP boundary.
 */
object ReactionParser {
    // Crockford Base32: excludes I, L, O, U to avoid ambiguity.
    private const val CROCKFORD_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

    private val decodeTable: IntArray = IntArray(128) { -1 }.also { table ->
        for ((index, char) in CROCKFORD_ALPHABET.withIndex()) {
            table[char.code] = index
            table[char.uppercaseChar().code] = index
        }
        // Common substitutions (both cases).
        table['O'.code] = 0
        table['o'.code] = 0
        table['I'.code] = 1
        table['i'.code] = 1
        table['L'.code] = 1
        table['l'.code] = 1
    }

    private fun encodeCrockfordBase32(bytes: ByteArray): String {
        require(bytes.size == 5)
        var bits = 0L
        for (byte in bytes) bits = (bits shl 8) or (byte.toLong() and 0xFF)
        val result = StringBuilder(8)
        for (shift in 35 downTo 0 step 5) {
            val index = ((bits shr shift) and 0x1F).toInt()
            result.append(CROCKFORD_ALPHABET[index])
        }
        return result.toString()
    }

    private fun isValidCrockfordBase32(text: String): Boolean = text.all { it.code < 128 && decodeTable[it.code] >= 0 }

    private fun normalizeCrockfordBase32(text: String): String {
        val result = StringBuilder(text.length)
        for (char in text) {
            if (char.code >= 128) continue
            val value = decodeTable[char.code]
            if (value >= 0) result.append(CROCKFORD_ALPHABET[value])
        }
        return result.toString()
    }

    /** Parsed channel-reaction data extracted from wire format. */
    data class ParsedReaction(val emoji: String, val targetSender: String, val messageHash: String)

    /** Parsed DM-reaction data (shorter format without a sender). */
    data class ParsedDMReaction(val emoji: String, val messageHash: String)

    /** Whether [text] matches the native reaction wire format (channel or DM per [isDM]). */
    fun isReactionText(text: String, isDM: Boolean): Boolean = if (isDM) parseDM(text) != null else parse(text) != null

    /**
     * Parses channel-reaction text using an end-to-start strategy.
     * Format: `{emoji}@[{sender}]\nxxxxxxxx`
     */
    fun parse(text: String): ParsedReaction? {
        val newlineIndex = text.lastIndexOf('\n')
        if (newlineIndex < 0) return null

        val rawHash = text.substring(newlineIndex + 1)
        if (rawHash.length != 8 || !isValidCrockfordBase32(rawHash)) return null
        val messageHash = normalizeCrockfordBase32(rawHash)

        val withoutHash = text.substring(0, newlineIndex)
        val atBracketIndex = withoutHash.indexOf("@[")
        if (atBracketIndex < 0) return null

        val emoji = withoutHash.substring(0, atBracketIndex)
        if (emoji.isEmpty() || !EmojiDetection.startsWithEmoji(emoji)) return null

        val afterAtBracket = withoutHash.substring(atBracketIndex + 2)
        if (!afterAtBracket.endsWith("]")) return null
        val sender = afterAtBracket.dropLast(1)
        if (sender.isEmpty()) return null

        return ParsedReaction(emoji, sender, messageHash)
    }

    /**
     * Parses DM-reaction text, returns `null` if the format doesn't match.
     * Format: `{emoji}\nxxxxxxxx` (no sender field)
     */
    fun parseDM(text: String): ParsedDMReaction? {
        // Reject channel format.
        if (text.contains("@[")) return null

        val newlineIndex = text.lastIndexOf('\n')
        if (newlineIndex < 0) return null

        val rawHash = text.substring(newlineIndex + 1)
        if (rawHash.length != 8 || !isValidCrockfordBase32(rawHash)) return null
        val messageHash = normalizeCrockfordBase32(rawHash)

        val emoji = text.substring(0, newlineIndex)
        if (emoji.isEmpty() || !EmojiDetection.startsWithEmoji(emoji)) return null

        return ParsedDMReaction(emoji, messageHash)
    }

    /** Builds DM reaction wire text: `{emoji}\n{hash}`. */
    fun buildDMReactionText(emoji: String, targetText: String, targetTimestamp: UInt): String =
        "$emoji\n${generateMessageHash(targetText, targetTimestamp)}"

    /** Generates the 8-char Crockford Base32 message identifier: `SHA256(text || LE32(timestamp))[0..5)`. */
    fun generateMessageHash(text: String, timestamp: UInt): String {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val timestampBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(timestamp.toInt()).array()
        val digest = MessageDigest.getInstance("SHA-256").digest(textBytes + timestampBytes)
        return encodeCrockfordBase32(digest.copyOf(5))
    }

    /** Builds a summary string (`"👍:3,❤️:2"`) from emoji/count pairs, sorted by count descending. */
    fun buildSummary(reactions: List<Pair<String, Int>>): String =
        reactions.sortedByDescending { it.second }.joinToString(",") { "${it.first}:${it.second}" }

    /** Parses a summary string back into emoji/count pairs. */
    fun parseSummary(summary: String?): List<Pair<String, Int>> {
        if (summary.isNullOrEmpty()) return emptyList()
        return summary.split(",").mapNotNull { part ->
            val components = part.split(":")
            if (components.size != 2) return@mapNotNull null
            val count = components[1].toIntOrNull() ?: return@mapNotNull null
            components[0] to count
        }
    }
}
