// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sendqueue

/** Retry timing for [ChatSendQueueService]. Ported from `ChatSendQueueConfig`. */
data class ChatSendQueueConfig(
    /** Maximum time to wait for a transport-open trigger before silently re-attempting the send. */
    val transportWaitTimeoutMs: Long = 30_000,
    /**
     * Number of channel-drain attempts before the queue spends a BLE round-trip to disambiguate
     * transient NOT_FOUND (pool exhaustion) from terminal NOT_FOUND (channel deleted on the
     * device).
     */
    val disambiguateAfterAttempts: Int = 3,
    /**
     * Backstop cap on consecutive `fetchChannel` throws. After this many the channel drain treats
     * NOT_FOUND as terminal so the user sees `.failed` and can manually retry.
     */
    val maxConsecutiveFetchChannelFailures: Int = 16,
) {
    companion object {
        val DEFAULT = ChatSendQueueConfig()
    }
}
