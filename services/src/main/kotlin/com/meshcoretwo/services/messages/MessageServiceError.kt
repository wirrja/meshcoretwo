// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import com.meshcoretwo.protocol.MeshCoreError

/** Errors that can occur during message operations. Ported from `MessageServiceError.swift`. */
sealed class MessageServiceError(message: String) : Exception(message) {
    /** Not connected to a device. */
    object NotConnected : MessageServiceError("Not connected to device.")

    /** Contact not found in database. */
    object ContactNotFound : MessageServiceError("Contact not found.")

    /** Channel not found in database. */
    object ChannelNotFound : MessageServiceError("Channel not found.")

    /** Message send operation failed. */
    data class SendFailed(val reason: String) : MessageServiceError("Send failed: $reason")

    /** Attempted to send message to invalid recipient (e.g. a repeater). */
    object InvalidRecipient : MessageServiceError("Cannot send messages to this recipient.")

    /** Message text exceeds maximum allowed length. */
    object MessageTooLong : MessageServiceError("Message exceeds the maximum allowed length.")

    /** Underlying MeshCore session error. */
    data class SessionError(val error: MeshCoreError) : MessageServiceError(error.message ?: "session error")
}
