// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import com.meshcoretwo.services.messages.DeduplicationKey
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Composite key builders backup export/import uses to tell "the same row, seen twice" from "two
 * distinct rows" across a full-database export/import round trip. Ported from the first extension
 * block of `PersistenceStore+BackupHelpers.swift` (composite key builders only — the
 * `fetchExisting*ByKey`/`mergeBackupMetadata` half of that file needs real batched Room queries
 * and belongs with the ID-remapping/batch-insert sub-slice these keys feed into, see PLAN.md's
 * "Backup/restore — план среза").
 *
 * Public key-building functions use `java.util.Base64` (not `android.util.Base64`) for the
 * public-key/path-bytes segments — pure JVM, so this stays testable under plain JUnit like the
 * rest of `services`' Robolectric-free-when-possible utilities; the encoding only has to be
 * stable and unique within one device's export, never compared against a Swift-produced key.
 */
object BackupDedupKeys {
    fun savedTracePathKey(radioID: UUID, pathBytes: ByteArray, hashSize: Int): String =
        "$radioID-${pathBytes.base64()}-$hashSize"

    fun contactKey(radioID: UUID, publicKey: ByteArray): String = "$radioID-${publicKey.base64()}"

    fun channelKey(radioID: UUID, index: UByte): String = "$radioID-$index"

    /**
     * Dedup key for backup reconciliation. Outgoing messages key on their UUID so two intentional
     * sends with identical text/timestamp/recipient do not collapse on restore. Incoming messages
     * fall back to their stored live-sync dedup key (or a content-based derivation when the field
     * was never populated — e.g. pre-schema rows), scoped by `radioID` so two companion radios
     * that both received the same wire packet restore as two rows rather than collapsing into one
     * under a single radio.
     */
    fun messageBackupKey(dto: MessageDto): String {
        if (dto.direction == MessageDirection.OUTGOING) {
            return "${DeduplicationKey.OUTGOING_IDENTITY_PREFIX}${dto.id}"
        }
        val base = dto.deduplicationKey ?: DeduplicationKey.contentBased(
            contactID = dto.contactID,
            channelIndex = dto.channelIndex,
            senderNodeName = dto.senderNodeName,
            timestamp = dto.timestamp,
            content = dto.text,
        )
        return "${dto.radioID}-$base"
    }

    fun reactionKey(messageID: UUID, senderName: String, emoji: String): String = "$messageID-$senderName-$emoji"

    fun roomMessageKey(sessionID: UUID, deduplicationKey: String): String = "$sessionID-$deduplicationKey"

    fun blockedChannelSenderKey(radioID: UUID, name: String): String = "$radioID-$name"

    fun remoteNodeSessionKey(radioID: UUID, publicKey: ByteArray): String = "$radioID-${publicKey.base64()}"

    fun discoveredNodeKey(radioID: UUID, publicKey: ByteArray): String = "$radioID-${publicKey.base64()}"

    /**
     * Two distinct snapshots for one node within the same millisecond intentionally coalesce (the
     * second is counted as skipped) — acceptable, rare diagnostic coalescing; the id is
     * deliberately not part of the key so re-import stays idempotent.
     */
    fun nodeStatusSnapshotKey(nodePublicKey: ByteArray, timestamp: Instant): String =
        "${nodePublicKey.base64()}-${timestamp.toEpochMilli()}"

    /**
     * Rewrites the contact-UUID segment of a DM dedup key. Only the `dm-{uuid}-` prefix is
     * touched, never raw substrings elsewhere in the key, so a UUID that happens to appear inside
     * the trailing hash (vanishingly unlikely, but possible) is preserved.
     */
    fun rewriteDMDeduplicationKey(key: String, from: UUID, to: UUID): String {
        val oldPrefix = "${DeduplicationKey.DIRECT_MESSAGE_PREFIX}$from-"
        if (!key.startsWith(oldPrefix)) return key
        return "${DeduplicationKey.DIRECT_MESSAGE_PREFIX}$to-" + key.substring(oldPrefix.length)
    }

    /**
     * Rewrites the channel-index segment of a content-based channel dedup key when a backup
     * channel is relocated to a different local slot. Only the leading `ch-{index}-` segment is
     * touched, mirroring [rewriteDMDeduplicationKey], so a numeric run elsewhere in the key
     * (timestamp, hash) cannot be misinterpreted as the index. Keys that don't carry this prefix
     * (outgoing identity keys, DM keys) pass through unchanged.
     */
    fun rewriteChannelDeduplicationKey(key: String, from: UByte, to: UByte): String {
        val oldPrefix = "${DeduplicationKey.CHANNEL_PREFIX}$from-"
        if (!key.startsWith(oldPrefix)) return key
        return "${DeduplicationKey.CHANNEL_PREFIX}$to-" + key.substring(oldPrefix.length)
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
}
