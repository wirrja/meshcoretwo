// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.channels

/**
 * The classification of one channel-sync failure. Ported from `ChannelSyncError.ErrorType`
 * (`ChannelService.swift`) as a sealed class rather than an enum: [DeviceError] carries a
 * per-case payload Kotlin enums can't attach.
 *
 * Drops the `WiFiTransportError`/`MeshTransportError` branches [classifyError] mapped in Swift:
 * every error this classifies flows through [ChannelService.fetchChannel], which already wraps
 * everything into a [ChannelServiceError] — a raw transport error never reaches this layer here.
 */
sealed class ChannelSyncErrorType {
    object Timeout : ChannelSyncErrorType()
    object SendTimeout : ChannelSyncErrorType()
    object TransportError : ChannelSyncErrorType()
    object CircuitBreaker : ChannelSyncErrorType()
    data class DeviceError(val code: UByte) : ChannelSyncErrorType()
    object DatabaseError : ChannelSyncErrorType()
    object Unknown : ChannelSyncErrorType()
}

/** Detailed error information for one failed channel sync. Ported from `ChannelSyncError`. */
data class ChannelSyncError(val index: UByte, val errorType: ChannelSyncErrorType, val description: String) {
    /** Whether this error type is potentially recoverable with retry. */
    val isRetryable: Boolean
        get() = when (errorType) {
            is ChannelSyncErrorType.Timeout, is ChannelSyncErrorType.SendTimeout -> true
            else -> false
        }

    val countsTowardCircuitBreaker: Boolean
        get() = when (errorType) {
            is ChannelSyncErrorType.Timeout, is ChannelSyncErrorType.SendTimeout, is ChannelSyncErrorType.TransportError -> true
            else -> false
        }
}
