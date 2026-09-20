// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.decodePathLen

/** Configuration for login timeout based on path length. Ported from `LoginTimeoutConfig.swift`. */
object LoginTimeoutConfig {
    /** Base timeout for direct (0-hop) connections, in milliseconds. */
    private const val DIRECT_TIMEOUT_MS = 5_000L

    /** Additional timeout per hop in the path, in milliseconds. */
    private const val PER_HOP_TIMEOUT_MS = 10_000L

    /** Maximum timeout regardless of path length, in milliseconds. */
    const val MAXIMUM_TIMEOUT_MS = 60_000L

    /**
     * Calculates the appropriate timeout, in milliseconds, based on path length. An undecodable
     * byte (mode 3, notably the 0xFF flood sentinel) means no known path: the login floods both
     * ways, so this budgets for the worst case rather than pricing it as a zero-hop direct exchange.
     */
    fun timeoutMs(pathLength: UByte): Long {
        val decoded = decodePathLen(pathLength) ?: return MAXIMUM_TIMEOUT_MS
        val total = DIRECT_TIMEOUT_MS + PER_HOP_TIMEOUT_MS * decoded.hopCount
        return minOf(total, MAXIMUM_TIMEOUT_MS)
    }
}
