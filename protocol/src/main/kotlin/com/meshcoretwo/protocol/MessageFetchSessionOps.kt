// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/** Session operations for draining the device's message queue. */
interface MessageFetchSessionOps {
    /**
     * Returns one message at a time from the device's message queue. Call repeatedly until
     * [MessageResult.NoMoreMessages] is returned to drain the queue.
     *
     * @param timeout Optional timeout override in seconds. Uses the session's default timeout when `null`.
     * @throws MeshCoreError if the fetch fails.
     */
    suspend fun getMessage(timeout: Double? = null): MessageResult

    /** Starts automatic message fetching in response to device notifications. */
    suspend fun startAutoMessageFetching()

    /** Stops the automatic fetching started by [startAutoMessageFetching]. */
    fun stopAutoMessageFetching()
}
