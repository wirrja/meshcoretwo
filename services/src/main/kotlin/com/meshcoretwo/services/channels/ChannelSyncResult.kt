// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.channels

/** Result of a channel sync operation. Ported from `ChannelSyncResult` (`ChannelService.swift`). */
data class ChannelSyncResult(val channelsSynced: Int, val errors: List<ChannelSyncError> = emptyList()) {
    /** Whether sync completed without errors. */
    val isComplete: Boolean get() = errors.isEmpty()

    val requestTimeoutCount: Int get() = errors.count { it.errorType is ChannelSyncErrorType.Timeout }

    val sendTimeoutCount: Int get() = errors.count { it.errorType is ChannelSyncErrorType.SendTimeout }

    val circuitBreakerAborted: Boolean get() = errors.any { it.errorType is ChannelSyncErrorType.CircuitBreaker }

    /** Indices of channels that failed with retryable errors. */
    val retryableIndices: List<UByte> get() = errors.filter { it.isRetryable }.map { it.index }
}
