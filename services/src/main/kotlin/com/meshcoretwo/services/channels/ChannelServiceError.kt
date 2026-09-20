// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.channels

import com.meshcoretwo.protocol.MeshCoreError

/**
 * Errors [ChannelService] can throw. Ported from `ChannelServiceError.swift`, trimmed to the
 * cases this vertical slice's methods actually throw — `notConnected`/`channelNotFound`/
 * `saveFailed`/`circuitBreakerOpen` are declared in Swift but never actually constructed by
 * `ChannelService.swift` either (its circuit breaker records a [ChannelSyncErrorType.CircuitBreaker]
 * into the sync result instead of throwing, and `channelNotFound`/`saveFailed` belong to
 * not-yet-ported callers); add them when a ported method needs to throw them.
 */
sealed class ChannelServiceError(message: String) : Exception(message) {
    /** Another [ChannelService.syncChannels]/[ChannelService.retryFailedChannels] call is already running. */
    object SyncAlreadyInProgress : ChannelServiceError("Channel sync is already in progress.")

    /** The device replied with a different channel index than requested. */
    object InvalidChannelIndex : ChannelServiceError("Invalid channel index.")

    /** A secret passed to [ChannelService.setChannelWithSecret] isn't exactly 16 bytes. */
    object SecretHashingFailed : ChannelServiceError("Failed to hash channel secret.")

    /** The underlying session operation failed; [error] carries the specific reason. */
    data class SessionError(val error: MeshCoreError) : ChannelServiceError(error.message ?: "session error")
}
