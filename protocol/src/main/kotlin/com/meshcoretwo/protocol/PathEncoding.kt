// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// MARK: - Path Encoding Utilities

/**
 * Limits for the multibyte path-length encoding, shared by [encodePathLen],
 * `setPathHashMode`, and the config-import path validator so the valid ranges live in one place.
 */
object PathEncoding {
    /** Highest valid path hash-size mode (0 = 1-byte, 1 = 2-byte, 2 = 3-byte hashes; mode 3 is reserved). */
    const val MAX_PATH_HASH_MODE = 2
    /** Highest hop count representable in the encoded path-length byte's lower 6 bits. */
    const val MAX_HOP_COUNT = 63
    /**
     * Maximum encoded out-path length in bytes (firmware `MAX_PATH_SIZE`); firmware
     * `isValidPathLen` rejects `hash_count * hash_size` beyond this.
     */
    const val MAX_PATH_BYTES = 64
}

/** Decoded components of a multibyte-encoded path length byte. */
data class PathLenDecoded(
    /** Bytes per hop hash (1, 2, or 3). */
    val hashSize: Int,
    /** Number of hops in the path (0-63). */
    val hopCount: Int,
    /** Total path byte length (hashSize * hopCount). */
    val byteLength: Int,
)

/**
 * Decodes a multibyte-encoded path length byte.
 *
 * The encoding packs two fields into a single byte:
 * - Bits 7-6: hash size mode (0=1-byte, 1=2-byte, 2=3-byte, 3=reserved)
 * - Bits 5-0: hop count (0-63)
 *
 * @param encoded The raw path length byte from the wire.
 * @return Decoded components, or `null` if mode 3 (reserved).
 */
fun decodePathLen(encoded: UByte): PathLenDecoded? {
    val mode = encoded.toInt() shr 6
    if (mode >= 3) return null // mode 3 is reserved
    val hashSize = mode + 1
    val hopCount = encoded.toInt() and 63
    return PathLenDecoded(hashSize = hashSize, hopCount = hopCount, byteLength = hashSize * hopCount)
}

/**
 * Encodes hash size and hop count into a single path length byte.
 *
 * This is the inverse of [decodePathLen].
 *
 * @param hashSize Bytes per hop (1, 2, or 3).
 * @param hopCount Number of hops (0-63).
 * @return The encoded path length byte.
 */
fun encodePathLen(hashSize: Int, hopCount: Int): UByte {
    require(hashSize in 1..3) { "hashSize must be 1, 2, or 3" }
    val mode = (hashSize - 1).toUByte()
    val hops = minOf(hopCount, PathEncoding.MAX_HOP_COUNT).toUByte()
    return ((mode.toInt() shl 6) or hops.toInt()).toUByte()
}
