// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

// MARK: - Binary reading/writing on ByteArray

// Kotlin's ByteArray has value semantics under contentEquals()/contentHashCode() but
// reference semantics under ==, unlike Swift's Data. Callers that compare packet bytes
// (tests, crypto, dedup) must use contentEquals(); plain == silently compares identity.

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Computes the hex string representation of the data. */
val ByteArray.hexString: String
    get() {
        val chars = CharArray(size * 2)
        for (i in indices) {
            val byte = this[i].toInt() and 0xFF
            chars[i * 2] = HEX_DIGITS[byte shr 4]
            chars[i * 2 + 1] = HEX_DIGITS[byte and 0x0F]
        }
        return String(chars)
    }

/**
 * Parses a hex string into a byte array.
 *
 * Mirrors the Swift `Data(hexString:)` initializer exactly: an odd-length input silently
 * drops its trailing character (`length / 2` pairs are read) rather than failing; `null` is
 * returned only when a consumed pair contains a non-hex-digit character.
 */
fun String.decodeHex(): ByteArray? {
    val pairCount = length / 2
    val result = ByteArray(pairCount)
    var index = 0
    for (i in 0 until pairCount) {
        val hi = Character.digit(this[index], 16)
        val lo = Character.digit(this[index + 1], 16)
        if (hi < 0 || lo < 0) return null
        result[i] = ((hi shl 4) or lo).toByte()
        index += 2
    }
    return result
}

/** Reads a little-endian UInt32 from the specified offset, or 0 if out of bounds. */
fun ByteArray.readUInt32LE(offset: Int): UInt {
    if (offset < 0 || offset + 4 > size) return 0u
    val b0 = this[offset].toInt() and 0xFF
    val b1 = this[offset + 1].toInt() and 0xFF
    val b2 = this[offset + 2].toInt() and 0xFF
    val b3 = this[offset + 3].toInt() and 0xFF
    return (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)).toUInt()
}

/** Reads a little-endian Int32 from the specified offset, or 0 if out of bounds. */
fun ByteArray.readInt32LE(offset: Int): Int {
    if (offset < 0 || offset + 4 > size) return 0
    val b0 = this[offset].toInt() and 0xFF
    val b1 = this[offset + 1].toInt() and 0xFF
    val b2 = this[offset + 2].toInt() and 0xFF
    val b3 = this[offset + 3].toInt() and 0xFF
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

/** Reads a little-endian UInt16 from the specified offset, or 0 if out of bounds. */
fun ByteArray.readUInt16LE(offset: Int): UShort {
    if (offset < 0 || offset + 2 > size) return 0u
    val b0 = this[offset].toInt() and 0xFF
    val b1 = this[offset + 1].toInt() and 0xFF
    return (b0 or (b1 shl 8)).toUShort()
}

/** Reads a little-endian Int16 from the specified offset, or 0 if out of bounds. */
fun ByteArray.readInt16LE(offset: Int): Short {
    if (offset < 0 || offset + 2 > size) return 0
    val b0 = this[offset].toInt() and 0xFF
    val b1 = this[offset + 1].toInt() and 0xFF
    return (b0 or (b1 shl 8)).toShort()
}

/** Alias for [readUInt32LE]. */
fun ByteArray.readUInt32(offset: Int): UInt = readUInt32LE(offset)

/** Alias for [readInt32LE]. */
fun ByteArray.readInt32(offset: Int): Int = readInt32LE(offset)

/** Alias for [readUInt16LE]. */
fun ByteArray.readUInt16(offset: Int): UShort = readUInt16LE(offset)

/** Alias for [readInt16LE]. */
fun ByteArray.readInt16(offset: Int): Short = readInt16LE(offset)

/** Little-endian byte encoding, for building packets with `data += value.toLittleEndianBytes()`. */
fun UInt.toLittleEndianBytes(): ByteArray = byteArrayOf(
    (this and 0xFFu).toByte(),
    ((this shr 8) and 0xFFu).toByte(),
    ((this shr 16) and 0xFFu).toByte(),
    ((this shr 24) and 0xFFu).toByte(),
)

/** Little-endian byte encoding of a signed 32-bit value. */
fun Int.toLittleEndianBytes(): ByteArray = toUInt().toLittleEndianBytes()

/** Little-endian byte encoding of an unsigned 16-bit value. */
fun UShort.toLittleEndianBytes(): ByteArray {
    val v = toUInt()
    return byteArrayOf((v and 0xFFu).toByte(), ((v shr 8) and 0xFFu).toByte())
}

/** Little-endian byte encoding of a signed 16-bit value. */
fun Short.toLittleEndianBytes(): ByteArray = toUShort().toLittleEndianBytes()

// MARK: - SNR Helper

/**
 * Converts the raw SNR byte to a floating-point value.
 *
 * The MeshCore protocol encodes SNR as a signed byte where the value is SNR * 4.
 */
val Byte.snrValue: Double
    get() = this / 4.0

/** Converts the raw SNR byte (interpreted as signed) to a floating-point value. */
val UByte.snrValue: Double
    get() = toByte().snrValue

// MARK: - Data Padding and Writing

/**
 * Content-equality for two nullable byte arrays: `null` only equals `null`, otherwise compares
 * with [contentEquals]. Kotlin's top-level `private` is file-scoped (unlike Java's
 * package-private), so this lives here, shared, rather than duplicated per payload type that
 * has an optional `ByteArray` field.
 */
internal fun nullableBytesEqual(a: ByteArray?, b: ByteArray?): Boolean = when {
    a == null || b == null -> a == null && b == null
    else -> a.contentEquals(b)
}

/** Whether this array starts with the given [prefix], mirroring Swift's `Collection.starts(with:)`. */
fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

/**
 * Returns the first [n] bytes, or the whole array if it has fewer than [n] bytes.
 *
 * Unlike [paddedOrTruncated], this never pads — mirrors Swift's `Data.prefix(_:)`.
 */
fun ByteArray.prefixBytes(n: Int): ByteArray = if (size <= n) this else copyOfRange(0, n)

/**
 * Returns data padded with zeros or truncated to the specified length.
 *
 * `ByteArray.copyOf(newSize)` already zero-pads when growing and truncates when shrinking,
 * matching Swift's `paddedOrTruncated(to:)`.
 */
fun ByteArray.paddedOrTruncated(to: Int): ByteArray {
    if (to < 0) return ByteArray(0)
    return copyOf(to)
}

private fun strictUtf8Decoder() = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)

/**
 * Strictly decodes the entire byte array as UTF-8, or returns `null` if any byte sequence is
 * invalid. Mirrors Swift's `String(data:encoding:.utf8)` (which returns `nil` on any invalid
 * byte), as opposed to [decodingLongestValidUtf8Prefix] (which trims back to the last valid
 * prefix) or a plain `String(bytes, Charsets.UTF_8)` (which silently replaces invalid bytes).
 */
fun ByteArray.decodeUtf8Strict(): String? = try {
    strictUtf8Decoder().decode(ByteBuffer.wrap(this)).toString()
} catch (e: CharacterCodingException) {
    null
}

private val controlCharactersRegex = Regex("^[\\p{Cc}\\p{Cf}]+|[\\p{Cc}\\p{Cf}]+$")

/** Trims leading/trailing Unicode control (Cc) and format (Cf) characters, like Swift's `CharacterSet.controlCharacters`. */
fun String.trimControlCharacters(): String = replace(controlCharactersRegex, "")

/**
 * Decodes the longest UTF-8-valid prefix of these bytes as a `String`.
 *
 * Firmware name fields are fixed C-strings truncated byte-wise, so a field can end
 * mid-codepoint; this drops the trailing partial sequence rather than fail the whole
 * name. Decode-side counterpart to [utf8Prefix].
 */
fun ByteArray.decodingLongestValidUtf8Prefix(): String {
    fun tryDecode(length: Int): String? = try {
        strictUtf8Decoder().decode(ByteBuffer.wrap(this, 0, length)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    tryDecode(size)?.let { return it }
    for (length in size - 1 downTo 1) {
        tryDecode(length)?.let { return it }
    }
    return ""
}

/**
 * Returns the longest prefix of the string whose UTF-8 encoding fits within [maxBytes].
 *
 * Never splits a multi-byte character. Returns the empty string when [maxBytes] is zero or
 * negative. Iterates by Unicode code point rather than by grapheme cluster (unlike Swift's
 * `Character`-based iteration), so a combining-mark sequence could in principle split where
 * Swift would not — immaterial for the fixed-width name/label fields this is used on.
 */
fun String.utf8Prefix(maxBytes: Int): String {
    if (maxBytes <= 0) return ""

    var byteCount = 0
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val charCount = Character.charCount(codePoint)
        val codePointBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        if (byteCount + codePointBytes > maxBytes) break
        byteCount += codePointBytes
        index += charCount
    }
    return substring(0, index)
}

/**
 * Returns the UTF-8 bytes of the string, padded or truncated to the specified length.
 *
 * Truncation is UTF-8-safe: multi-byte characters are never split.
 */
fun String.utf8PaddedOrTruncated(to: Int): ByteArray =
    utf8Prefix(to).toByteArray(Charsets.UTF_8).paddedOrTruncated(to)
