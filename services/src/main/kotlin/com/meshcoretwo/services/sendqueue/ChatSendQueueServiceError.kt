// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sendqueue

/** Errors that can occur enqueueing a send. Ported from `ChatSendQueueServiceError.swift`. */
sealed class ChatSendQueueServiceError(message: String) : Exception(message) {
    data class PersistFailed(val underlying: Throwable) : ChatSendQueueServiceError("Failed to queue message for sending: ${underlying.message}")

    object NotConnected : ChatSendQueueServiceError("Not connected to device.")
}
