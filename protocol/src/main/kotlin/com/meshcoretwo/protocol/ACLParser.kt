// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// MARK: - ACL Parser

/** Specialized parser for Access Control List data. */
object ACLParser {
    /**
     * Parses ACL entries from binary protocol data.
     *
     * ### Binary Format
     * (Per entry): `[pubkey_prefix:6][permissions:1]` (7 bytes total)
     */
    fun parse(data: ByteArray): List<ACLEntry> {
        val entries = mutableListOf<ACLEntry>()
        var offset = 0

        while (offset + 7 <= data.size) {
            val keyPrefix = data.copyOfRange(offset, offset + 6)
            val permissions = data[offset + 6].toUByte()
            offset += 7

            // Skip null entries (all zeros)
            if (keyPrefix.all { it == 0.toByte() }) {
                continue
            }

            entries.add(ACLEntry(keyPrefix = keyPrefix, permissions = permissions))
        }

        return entries
    }
}
