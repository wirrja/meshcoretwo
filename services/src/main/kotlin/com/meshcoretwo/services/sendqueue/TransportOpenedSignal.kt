// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sendqueue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-purpose wake signal: a drain suspended in [ChatSendQueueService] waits for the BLE
 * transport to reopen. Ported from `BLETransportOpenedSignal.swift` (a Swift `actor`) using a
 * [Mutex]-guarded waiter list instead of actor isolation.
 *
 * [wait] throws [CancellationException] if the calling coroutine is cancelled before [fire]
 * lands; callers handle the throw with the same logic as a timeout (park the envelope, requeue).
 */
class TransportOpenedSignal {
    private val mutex = Mutex()
    private var armed = false
    private val waiters = mutableListOf<CompletableDeferred<Unit>>()

    /**
     * Suspend until [fire] lands. If the signal is already armed at call time, the call returns
     * immediately and consumes the armed flag.
     */
    suspend fun wait() {
        val waiter = mutex.withLock {
            if (armed) {
                armed = false
                null
            } else {
                CompletableDeferred<Unit>().also { waiters.add(it) }
            }
        } ?: return

        try {
            waiter.await()
        } catch (error: CancellationException) {
            mutex.withLock { waiters.remove(waiter) }
            throw error
        }
    }

    /**
     * Mark the signal as fired. Wakes every waiter; arms the flag for the next [wait] call if no
     * waiters are currently suspended.
     */
    suspend fun fire() {
        val toResume = mutex.withLock {
            if (waiters.isEmpty()) {
                armed = true
                emptyList()
            } else {
                val copy = waiters.toList()
                waiters.clear()
                copy
            }
        }
        toResume.forEach { it.complete(Unit) }
    }

    /**
     * Drop any armed-pending state. Call sites: only after a successful send, not before each
     * attempt. The consume-on-wait semantic in [wait] already handles "fire landed during the
     * previous attempt" cleanly.
     */
    suspend fun clear() {
        mutex.withLock { armed = false }
    }
}
