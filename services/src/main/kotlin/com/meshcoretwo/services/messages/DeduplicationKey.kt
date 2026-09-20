// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import java.security.MessageDigest
import java.util.UUID

/**
 * Single source of truth for content-based deduplication key generation. Ported from
 * `DeduplicationKey.swift`. Content-based (not the RX-log packet hash) because that hash is
 * per-encrypted-packet and differs between retry attempts, which must not drive dedup.
 *
 * The three prefix constants are public (Swift's are `internal`, visible target-wide) since
 * [com.meshcoretwo.services.backup.BackupDedupKeys] rewrites the channel/DM prefix of an existing
 * key during backup import when a channel/contact is relocated to a different local slot/ID.
 */
object DeduplicationKey {
    const val CHANNEL_PREFIX = "ch-"
    const val DIRECT_MESSAGE_PREFIX = "dm-"
    const val OUTGOING_IDENTITY_PREFIX = "out-"
    private const val UNKNOWN_CONTACT_PLACEHOLDER = "unknown"

    fun contentBased(contactID: UUID?, channelIndex: UByte?, senderNodeName: String?, timestamp: UInt, content: String): String {
        val contentHash = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        val hashPrefix = contentHash.copyOf(4).joinToString("") { "%02X".format(it) }

        if (channelIndex != null) {
            return "$CHANNEL_PREFIX$channelIndex-$timestamp-${senderNodeName ?: ""}-$hashPrefix"
        }
        val contactSegment = contactID?.toString() ?: UNKNOWN_CONTACT_PLACEHOLDER
        return "$DIRECT_MESSAGE_PREFIX$contactSegment-$timestamp-$hashPrefix"
    }
}
