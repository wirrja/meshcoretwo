// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import java.time.Instant

/**
 * Rewrites remote `clock sync` to `time <host-epoch>`. Companion firmware restamps CLI packets to
 * its own RTC, so the packet timestamp is not the phone clock. Ported from
 * `RemoteCLICommandRewriter.swift`.
 */
object RemoteCLICommandRewriter {
    const val CLOCK_SYNC_COMMAND = "clock sync"
    const val TIME_COMMAND_PREFIX = "time "

    fun rewrite(command: String, now: Instant = Instant.now()): String {
        val normalized = command.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
        if (normalized != CLOCK_SYNC_COMMAND) return command
        return TIME_COMMAND_PREFIX + epochSeconds32(now)
    }

    /** Saturates pre-1970 and post-2106 dates instead of trapping a UInt32 conversion. */
    private fun epochSeconds32(instant: Instant): UInt {
        val seconds = instant.epochSecond
        if (seconds <= 0) return 0u
        if (seconds >= UInt.MAX_VALUE.toLong()) return UInt.MAX_VALUE
        return seconds.toUInt()
    }
}
