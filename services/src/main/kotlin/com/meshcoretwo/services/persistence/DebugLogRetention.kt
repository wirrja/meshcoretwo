// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

/**
 * Retention policy for persisted debug log entries, shared by the connection-time prune and any
 * future diagnostics export so an export can return everything retention keeps. Ported from
 * `DebugLogRetention.swift`.
 */
object DebugLogRetention {
    /** Entries older than this window (in seconds) are pruned. */
    const val WINDOW_SECONDS: Long = 7 * 24 * 60 * 60

    /** Hard row ceiling enforced after the time-based prune, as a disk backstop when the window alone would exceed it. */
    const val MAX_ENTRIES: Int = 50_000

    /** Minimum time between prune passes triggered from [com.meshcoretwo.services.logging.DebugLogBuffer], in seconds. */
    const val PRUNE_INTERVAL_SECONDS: Long = 60 * 60
}
