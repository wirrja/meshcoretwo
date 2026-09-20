// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.logging

import android.util.Log
import com.meshcoretwo.services.persistence.DebugLogEntryDto
import com.meshcoretwo.services.persistence.DebugLogLevel

/** Longest [DebugLogEntryDto.message] persisted — ported from `DebugLogEntryDTO.init`'s `message.prefix(4000)` guard against unbounded memory growth. */
private const val MAX_MESSAGE_LENGTH = 4000

/**
 * Drop-in replacement for [android.util.Log] that also records to [DebugLogBuffer.shared].
 * Writes to `Log` for every level; `.info` and above also persist to Room. Ported from
 * `PersistentLogger.swift`'s wrapper over `os.Logger` — see that file's `8c4eb521` fix
 * ("perf(logs): batch RxLog saves, skip debug persist"), which stopped persisting `.debug` since
 * it's the highest-volume level and never needed for the exported diagnostics window.
 */
class PersistentLogger(private val subsystem: String, private val category: String) {
    fun debug(message: String) {
        Log.d(category, message)
    }

    fun info(message: String) {
        Log.i(category, message)
        persist(DebugLogLevel.INFO, message)
    }

    fun notice(message: String) {
        Log.i(category, message)
        persist(DebugLogLevel.NOTICE, message)
    }

    fun warning(message: String) {
        Log.w(category, message)
        persist(DebugLogLevel.WARNING, message)
    }

    fun error(message: String) {
        Log.e(category, message)
        persist(DebugLogLevel.ERROR, message)
    }

    fun fault(message: String) {
        Log.wtf(category, message)
        persist(DebugLogLevel.FAULT, message)
    }

    private fun persist(level: DebugLogLevel, message: String) {
        DebugLogBuffer.record(
            DebugLogEntryDto(
                level = level,
                subsystem = subsystem,
                category = category,
                message = message.take(MAX_MESSAGE_LENGTH),
            ),
        )
    }
}
