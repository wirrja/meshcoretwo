// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sendqueue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serial drain of envelopes. Ported from `SendQueue.swift` (a Swift `actor`) using a
 * [Mutex]-guarded pending list and a single drain [Job] instead of actor isolation; multiple
 * [enqueue] calls during an active drain append to the same drain pass.
 *
 * Errors thrown by [send]:
 * - [CancellationException] re-inserts the envelope at the front of the queue and ends the drain
 *   pass. [taskCompleted] then respawns the drain if the finishing job wasn't itself cancelled
 *   (see below), so the requeued envelope retries. This covers send closures that use
 *   [CancellationException] as a "park and retry" signal (see [ChatSendQueueService]'s
 *   `parkAndCancel`), not only genuine coroutine cancellation.
 * - Any other error fires [onError] and the drain continues with the next envelope. Per-envelope
 *   failures never stop the queue.
 *
 * After the pending list empties, [onDrain] fires exactly once per drain completion with the most
 * recent non-cancellation error (or `null`). An enqueue landing during the [onDrain] suspension is
 * picked up by the outer `do-while`, so a follow-up envelope cannot sit unscheduled.
 *
 * Unlike the Swift actor (where `Task.isCancelled` cleanly distinguishes "cancellation thrown as a
 * signal" from "the actor's own Task was cancelled via `cancelDrain()`"), Kotlin coroutines don't
 * offer that distinction on the exception alone. This port keys off [Job.isCancelled] on the
 * specific drain [Job] instead: [cancelDrain] cancels that job, which [taskCompleted] observes via
 * [Job.invokeOnCompletion] and does not respawn from — even if the job's own body swallowed a
 * [CancellationException] before returning normally, a job [cancelDrain] cancelled is still
 * reported [Job.isCancelled] by the coroutine machinery.
 */
class SendQueue<Envelope>(
    private val send: suspend (Envelope) -> Unit,
    private val onError: suspend (Throwable, Envelope) -> Unit,
    private val onDrain: suspend (Throwable?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val pending = ArrayDeque<Envelope>()
    private var processingTask: Job? = null

    /** Append an envelope and ensure a drain job is running. */
    suspend fun enqueue(envelope: Envelope) {
        mutex.withLock {
            pending.addLast(envelope)
            ensureDrainingLocked()
        }
    }

    /** Number of envelopes waiting. Exposed for tests; no production consumer. */
    internal suspend fun pendingCount(): Int = mutex.withLock { pending.size }

    /**
     * Await the current drain pass to completion. If no drain is in progress this returns
     * immediately. Used by tests to synchronize on the drain job without polling.
     */
    internal suspend fun awaitDrainCompletion() {
        val job = mutex.withLock { processingTask }
        job?.join()
    }

    /**
     * Cancel the in-flight drain job and suppress the auto-respawn that would otherwise follow a
     * [CancellationException]-driven re-insertion. A subsequent [enqueue] schedules a fresh drain
     * job because [processingTask] is back to `null`.
     */
    fun cancelDrain() {
        processingTask?.cancel()
    }

    private fun ensureDrainingLocked() {
        if (processingTask != null) return
        spawnDrainTaskLocked()
    }

    private fun spawnDrainTaskLocked() {
        val job = scope.launch { drain() }
        processingTask = job
        job.invokeOnCompletion { scope.launch { taskCompleted(job) } }
    }

    private suspend fun taskCompleted(finishedJob: Job) {
        val shouldRespawn = mutex.withLock {
            if (processingTask === finishedJob) processingTask = null
            !finishedJob.isCancelled && pending.isNotEmpty()
        }
        if (shouldRespawn) mutex.withLock { ensureDrainingLocked() }
    }

    private suspend fun drain() {
        var lastError: Throwable? = null
        do {
            while (true) {
                val envelope = mutex.withLock { if (pending.isEmpty()) null else pending.removeFirst() } ?: break
                try {
                    send(envelope)
                } catch (error: CancellationException) {
                    mutex.withLock { pending.addFirst(envelope) }
                    return
                } catch (error: Throwable) {
                    lastError = error
                    onError(error, envelope)
                }
            }
            // Outer re-check after onDrain suspends. The handler typically triggers UI refreshes,
            // during which an enqueue can land. Without this outer pass, that envelope would sit
            // in pending with no scheduled drain until a future enqueue.
            onDrain(lastError)
        } while (mutex.withLock { pending.isNotEmpty() })
    }
}
