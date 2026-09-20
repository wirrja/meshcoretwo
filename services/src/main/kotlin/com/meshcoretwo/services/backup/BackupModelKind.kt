// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

/**
 * A model type carried in a full-app backup. Ported from `AppBackupEnvelope.swift`'s
 * `BackupModelKind`. Iterating [entries] drives per-kind UI rows, totals, and manifest lookups
 * from a single source of truth once the rest of the backup epic lands (see PLAN.md's
 * "Backup/restore — план среза").
 */
enum class BackupModelKind {
    MESSAGES,
    CONTACTS,
    CHANNELS,
    DEVICES,
    ROOM_MESSAGES,
    REACTIONS,
    MESSAGE_REPEATS,
    SAVED_TRACE_PATHS,
    REMOTE_NODE_SESSIONS,
    BLOCKED_CHANNEL_SENDERS,
    NODE_STATUS_SNAPSHOTS,
    DISCOVERED_NODES,
}
