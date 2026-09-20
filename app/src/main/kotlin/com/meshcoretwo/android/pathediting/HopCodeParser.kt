// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import com.meshcoretwo.protocol.hexString

/**
 * Pure parser for the comma-separated hex-code bulk-add entry shared by the contact path editor
 * (once ported) and the trace path builder. The single source of truth for both the bulk-add
 * preview and the actual add, so the panel can never show a status that differs from what tapping
 * "Add" produces. Ported from `HopCodeParser`.
 */
object HopCodeParser {
    /**
     * Classifies each code in [input] without mutating any path.
     *
     * @param hashSize bytes per hop; a code must be exactly `hashSize * 2` hex digits.
     * @param existingHashes hash-byte prefixes already in the path, as uppercase hex — a plain
     *   `Set<ByteArray>` would compare by reference (Kotlin's `ByteArray` has no structural
     *   `equals`), so this is keyed by hex string, the same adaptation
     *   [com.meshcoretwo.protocol.ContactManager.getByPublicKey] already makes for the same reason.
     * @param remainingCapacity hops still addable, or `null` when unlimited; resolvable codes
     *   beyond it become [HopCodeStatus.PathFull].
     * @param resolve maps a hash prefix to a node's full public key and display name, or `null`
     *   when no node matches.
     */
    fun classify(
        input: String,
        hashSize: Int,
        existingHashes: Set<String>,
        remainingCapacity: Int?,
        resolve: (ByteArray) -> Pair<ByteArray, String?>?,
    ): List<HopCodeClassification> {
        val codes = input.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

        val seen = mutableSetOf<String>()
        val uniqueCodes = codes.filter { seen.add(it) }

        val pathHashes = existingHashes.toMutableSet()
        var added = 0

        return uniqueCodes.map { code ->
            val hashData = parseHex(code, hashSize) ?: return@map HopCodeClassification(code, HopCodeStatus.InvalidFormat)
            val hashHex = hashData.hexString.uppercase()
            if (pathHashes.contains(hashHex)) return@map HopCodeClassification(code, HopCodeStatus.AlreadyInPath)

            val resolved = resolve(hashData) ?: return@map HopCodeClassification(code, HopCodeStatus.NotFound)
            if (remainingCapacity != null && added >= remainingCapacity) return@map HopCodeClassification(code, HopCodeStatus.PathFull)

            added += 1
            pathHashes.add(hashHex)
            val hop = PathHop(hashBytes = hashData, publicKey = resolved.first, resolvedName = resolved.second)
            HopCodeClassification(code, HopCodeStatus.WillAdd(hop))
        }
    }

    /** Parses a hex [code] into exactly [hashSize] bytes, or `null` if malformed. */
    private fun parseHex(code: String, hashSize: Int): ByteArray? {
        if (code.length != hashSize * 2 || !code.all { it.isHexDigit() }) return null
        val result = ByteArray(hashSize)
        for (i in 0 until hashSize) {
            val hi = Character.digit(code[i * 2], 16)
            val lo = Character.digit(code[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            result[i] = ((hi shl 4) or lo).toByte()
        }
        return result
    }

    private fun Char.isHexDigit(): Boolean = Character.digit(this, 16) >= 0
}
