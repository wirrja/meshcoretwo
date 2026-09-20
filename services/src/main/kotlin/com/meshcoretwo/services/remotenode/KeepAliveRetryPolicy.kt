// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import kotlinx.coroutines.CancellationException

/**
 * Encapsulates keep-alive retry decisions for testability. Ported from `KeepAliveRetryPolicy.swift`,
 * dropping `failureReason` — Swift only ever feeds it to a `logger.warning` call, and this codebase's
 * business services don't log (see `ChannelService`'s class doc).
 *
 * Classifies errors into five categories:
 * - **Transient** (timeout, deviceError, notConnected): retried up to [maxConsecutiveFailures] times
 * - **Terminal** (sessionNotFound, contactNotFound, unknown errors): disconnect immediately
 * - **Skip** (floodRouted): not a failure, continue the loop
 * - **Stop** (CancellationException, cancelled): task was cancelled, exit quietly
 */
object KeepAliveRetryPolicy {
    enum class Action {
        /** Transient failure, try again next interval. */
        RETRY_NEXT_INTERVAL,

        /** Consecutive transient failures exceeded threshold. */
        DISCONNECT,

        /** Terminal local-state error, disconnect immediately. */
        DISCONNECT_NOW,

        /** Flood-routed session, skip this iteration. */
        SKIP,

        /** Task cancelled, exit loop quietly. */
        STOP,
        ;

        val shouldExitLoop: Boolean get() = this == STOP || this == DISCONNECT || this == DISCONNECT_NOW
    }

    /** The number of consecutive transient failures required before disconnecting. */
    const val MAX_CONSECUTIVE_FAILURES = 2

    /**
     * Evaluates a keep-alive error and returns the appropriate action, and the updated failure count
     * (Swift threads `consecutiveFailures` as `inout`; Kotlin returns it alongside the action instead).
     */
    fun evaluate(error: Throwable, consecutiveFailures: Int): Pair<Action, Int> {
        if (error is CancellationException) return Action.STOP to consecutiveFailures

        val nodeError = error as? RemoteNodeError ?: return Action.DISCONNECT_NOW to consecutiveFailures

        return when (nodeError) {
            is RemoteNodeError.Cancelled -> Action.STOP to consecutiveFailures
            is RemoteNodeError.FloodRouted -> Action.SKIP to consecutiveFailures
            is RemoteNodeError.SessionNotFound, is RemoteNodeError.ContactNotFound -> Action.DISCONNECT_NOW to consecutiveFailures
            else -> {
                val failures = consecutiveFailures + 1
                (if (failures >= MAX_CONSECUTIVE_FAILURES) Action.DISCONNECT else Action.RETRY_NEXT_INTERVAL) to failures
            }
        }
    }
}
