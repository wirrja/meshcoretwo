// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex

/**
 * Serializes every command-response exchange that relies on event matching.
 *
 * Many MeshCore commands wait for generic events such as `.ok`, `.error`, or a singleton typed
 * response. Binary requests (status, telemetry, owner info, etc.) additionally learn their
 * `expectedAck` from a `.messageSent` event whose tag is not known in advance. Because
 * [EventDispatcher] broadcasts to every live subscription with no per-command correlation, two
 * exchanges in flight at once can consume each other's responses. Routing every exchange through
 * one serializer guarantees a single request/response is outstanding at a time, which is the only
 * structural defense given the learned (not precomputed) ack.
 *
 * Swift's version hand-rolls a continuation queue for [acquire]/[release] because raw
 * `CheckedContinuation`s are not cancellable, so a caller cancelled while queued only notices
 * once its turn arrives. Kotlin's [Mutex] is a fair, cancellable suspend primitive, so [acquire]
 * here drops a cancelled waiter immediately instead of leaving it queued — a deliberate, strictly
 * beneficial divergence, not a behavior gap: it still guarantees a single in-flight exchange, and
 * lets the next real waiter proceed sooner.
 */
class RequestResponseSerializer {
    private val mutex = Mutex()

    /**
     * Runs the held operation independently of the caller's coroutine, so cancelling the caller
     * does not abort an in-flight exchange — mirroring Swift's unstructured `Task { ... }`, which
     * is not a child of the calling task either.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Acquires the serializer, suspending if another request/response exchange is active. */
    suspend fun acquire() {
        mutex.lock()
    }

    /** Releases the serializer for the next waiting request. */
    fun release() {
        mutex.unlock()
    }

    /**
     * Executes a request/response operation while holding the serializer.
     *
     * The slot is held until the wire exchange the operation owns actually terminates — a
     * matching response is consumed or the command's own timeout elapses — even after the caller
     * has been resumed. If the caller's coroutine is cancelled mid-flight, it is resumed
     * immediately with [CancellationException], but the operation keeps running (in [scope], not
     * the caller's job) so a late (orphaned) response is drained here under the held slot instead
     * of leaking to the next command, which would otherwise consume it as its own. A command
     * cancelled before its exchange begins releases the slot without running the operation.
     */
    suspend fun <T> withSerialization(operation: suspend () -> T): T {
        acquire()

        if (!currentCoroutineContext().isActive) {
            release()
            throw CancellationException("withSerialization cancelled before start")
        }

        val deferred = scope.async {
            try {
                operation()
            } finally {
                release()
            }
        }

        return deferred.await()
    }
}
