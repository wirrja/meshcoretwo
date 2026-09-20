// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

/** Session operations for sending direct and channel messages. */
interface MessagingSessionOps {
    /**
     * Returns the device's self info after session start.
     *
     * Populated once the session handshake completes; `null` before that.
     */
    val currentSelfInfo: SelfInfo?

    /**
     * Sends a direct message to a contact.
     *
     * @param destination The recipient's public key (6-byte prefix).
     * @param text The message text to send.
     * @param timestamp The timestamp of the message.
     * @param attempt Retry attempt counter (0 for first attempt). Included in ACK hash.
     * @return A [MessageSentInfo] containing information about the sent message, including the ACK code.
     * @throws MeshCoreError if the message fails to send or the device returns an error.
     */
    suspend fun sendMessage(
        destination: ByteArray,
        text: String,
        timestamp: Instant = Instant.now(),
        attempt: UByte = 0u,
    ): MessageSentInfo

    /**
     * Sends a message to a channel.
     *
     * @param channel The channel index (0-7).
     * @param text The message text to send.
     * @param timestamp The timestamp of the message.
     * @throws MeshCoreError if the channel message fails to send.
     */
    suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant)
}
