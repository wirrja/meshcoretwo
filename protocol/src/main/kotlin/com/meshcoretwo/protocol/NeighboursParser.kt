// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// MARK: - Neighbours Parser

/** Specialized parser for remote node neighbour lists. */
object NeighboursParser {
    /**
     * Parses Neighbours response data from binary protocol.
     *
     * ### Binary Format
     * - Offset 0 (2 bytes): Total neighbours count (Int16 LE)
     * - Offset 2 (2 bytes): Results count in this response (Int16 LE)
     * - Entries: `[prefix:N][secs_ago:4][snr:1]` where N = [prefixLength].
     *
     * @param data Raw response data.
     * @param publicKeyPrefix Target node's public key prefix.
     * @param tag Request tag.
     * @param prefixLength Expected length of neighbour pubkey prefixes (default 4).
     * @return A [NeighboursResponse] containing the parsed list.
     */
    fun parse(data: ByteArray, publicKeyPrefix: ByteArray, tag: ByteArray, prefixLength: Int = 4): NeighboursResponse {
        if (data.size < 4) {
            return NeighboursResponse(publicKeyPrefix = publicKeyPrefix, tag = tag, totalCount = 0, neighbours = emptyList())
        }

        val totalCount = data.readInt16LE(0).toInt()
        val resultsCount = data.readInt16LE(2).toInt()

        val neighbours = mutableListOf<Neighbour>()
        val entrySize = prefixLength + 4 + 1 // pubkey + secs_ago + snr
        var offset = 4

        for (i in 0 until resultsCount) {
            if (offset + entrySize > data.size) break

            val keyPrefix = data.copyOfRange(offset, offset + prefixLength)
            offset += prefixLength

            val secondsAgo = data.readInt32LE(offset)
            offset += 4

            val snr = data[offset].snrValue
            offset += 1

            neighbours.add(Neighbour(publicKeyPrefix = keyPrefix, secondsAgo = secondsAgo, snr = snr))
        }

        return NeighboursResponse(publicKeyPrefix = publicKeyPrefix, tag = tag, totalCount = totalCount, neighbours = neighbours)
    }
}
