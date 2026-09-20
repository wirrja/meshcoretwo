// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

/**
 * Declared per-model counts, used to validate backup integrity after decoding. Ported from
 * `AppBackupEnvelope.swift`'s `BackupManifest`. Unlike the Swift source, [validate] takes the
 * decoded envelope's actual counts as a `Map` rather than the envelope itself — the envelope type
 * doesn't exist yet (see PLAN.md's "Backup/restore — план среза"); once it does, its own actual
 * counts by [BackupModelKind] are exactly what this compares against.
 */
data class BackupManifest(
    val deviceCount: Int = 0,
    val contactCount: Int = 0,
    val channelCount: Int = 0,
    val messageCount: Int = 0,
    val messageRepeatCount: Int = 0,
    val reactionCount: Int = 0,
    val roomMessageCount: Int = 0,
    val remoteNodeSessionCount: Int = 0,
    val savedTracePathCount: Int = 0,
    val blockedChannelSenderCount: Int = 0,
    val nodeStatusSnapshotCount: Int = 0,
    val discoveredNodeCount: Int = 0,
) {
    /** Returns the declared count for [kind]. Backs [BackupModelKind]-driven UI iteration. */
    fun count(kind: BackupModelKind): Int = when (kind) {
        BackupModelKind.MESSAGES -> messageCount
        BackupModelKind.CONTACTS -> contactCount
        BackupModelKind.CHANNELS -> channelCount
        BackupModelKind.DEVICES -> deviceCount
        BackupModelKind.ROOM_MESSAGES -> roomMessageCount
        BackupModelKind.REACTIONS -> reactionCount
        BackupModelKind.MESSAGE_REPEATS -> messageRepeatCount
        BackupModelKind.SAVED_TRACE_PATHS -> savedTracePathCount
        BackupModelKind.REMOTE_NODE_SESSIONS -> remoteNodeSessionCount
        BackupModelKind.BLOCKED_CHANNEL_SENDERS -> blockedChannelSenderCount
        BackupModelKind.NODE_STATUS_SNAPSHOTS -> nodeStatusSnapshotCount
        BackupModelKind.DISCOVERED_NODES -> discoveredNodeCount
    }

    /** `true` if every declared count matches [actualCounts] (a missing key reads as 0). */
    fun validate(actualCounts: Map<BackupModelKind, Int>): Boolean =
        BackupModelKind.entries.all { kind -> count(kind) == (actualCounts[kind] ?: 0) }

    companion object {
        fun from(actualCounts: Map<BackupModelKind, Int>): BackupManifest = BackupManifest(
            deviceCount = actualCounts[BackupModelKind.DEVICES] ?: 0,
            contactCount = actualCounts[BackupModelKind.CONTACTS] ?: 0,
            channelCount = actualCounts[BackupModelKind.CHANNELS] ?: 0,
            messageCount = actualCounts[BackupModelKind.MESSAGES] ?: 0,
            messageRepeatCount = actualCounts[BackupModelKind.MESSAGE_REPEATS] ?: 0,
            reactionCount = actualCounts[BackupModelKind.REACTIONS] ?: 0,
            roomMessageCount = actualCounts[BackupModelKind.ROOM_MESSAGES] ?: 0,
            remoteNodeSessionCount = actualCounts[BackupModelKind.REMOTE_NODE_SESSIONS] ?: 0,
            savedTracePathCount = actualCounts[BackupModelKind.SAVED_TRACE_PATHS] ?: 0,
            blockedChannelSenderCount = actualCounts[BackupModelKind.BLOCKED_CHANNEL_SENDERS] ?: 0,
            nodeStatusSnapshotCount = actualCounts[BackupModelKind.NODE_STATUS_SNAPSHOTS] ?: 0,
            discoveredNodeCount = actualCounts[BackupModelKind.DISCOVERED_NODES] ?: 0,
        )
    }
}
