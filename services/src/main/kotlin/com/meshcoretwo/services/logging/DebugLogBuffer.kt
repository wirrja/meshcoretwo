// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.logging

import android.util.Log
import com.meshcoretwo.services.persistence.DebugLogEntryDto
import com.meshcoretwo.services.persistence.DebugLogLevel
import com.meshcoretwo.services.persistence.DebugLogPersisting
import com.meshcoretwo.services.persistence.DebugLogRetention
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Buffers debug log entries and flushes them to [DebugLogPersisting] in batches (size- or
 * time-triggered), with backpressure handling for save failures. Ported from `DebugLogBuffer.swift`'s
 * `actor`.
 *
 * ## Concurrency
 *
 * Swift's actor is reentrant: a suspended `await` inside one actor-isolated call lets another
 * actor-isolated call start, which is exactly what [flushBuffer] relies on — two size-triggered
 * flushes can be in flight (both blocked on [dataStore] saves) at once, each racing the shared
 * backlog cap. A [kotlinx.coroutines.sync.Mutex] would give *different* semantics (the second
 * caller blocks until the first fully completes, including its suspension), so this port uses the
 * same single-dispatcher-confinement pattern already established for
 * [com.meshcoretwo.services.connection.ConnectionManager]: a private
 * [Dispatchers.Default.limitedParallelism(1)][kotlinx.coroutines.CoroutineDispatcher] lets only
 * one coroutine's synchronous code run at a time, but yields the slot to the next queued
 * coroutine whenever the running one suspends — structurally the same reentrancy Swift's actor
 * gives [flushBuffer].
 *
 * [append]/[flush]/[shutdown] are the public entry points, each wrapping its body in
 * `withContext(dispatcher)`. Internal callers ([record], [shared]'s setter drain, [scheduleFlush],
 * [flushNow]) call the `...Impl`/private bodies directly instead of through those wrappers —
 * nesting `withContext` on an already-confined `limitedParallelism(1)` dispatcher risks a real
 * deadlock (see [com.meshcoretwo.services.connection.ConnectionManagerPairing]'s class doc for the
 * fuller explanation of why, first documented there for the same pattern).
 */
class DebugLogBuffer(private val dataStore: DebugLogPersisting) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var buffer: MutableList<DebugLogEntryDto> = mutableListOf()
    private var flushJob: Job? = null
    private var isFlushScheduled = false

    /**
     * Seeded to now so the first successful flush after connect does not repeat the connect-time
     * prune ([com.meshcoretwo.services.ServiceContainer.startEventMonitoring]'s
     * `pruneDebugLogEntries` call) on this store.
     */
    private var lastPrune = Instant.now()

    /**
     * Entries lost since the last successful save, to a save failure or the requeue cap. Reported
     * as a synthesized log entry on the next successful save, since those windows are otherwise
     * invisible in the persisted log itself.
     */
    private var droppedEntryCount = 0

    /**
     * Guards [persistDropSummaryIfNeeded] against reentrancy: [flushBuffer] runs concurrently with
     * itself via [flushNow]'s launched coroutine, and two flushes succeeding together must not
     * persist duplicate summaries.
     */
    private var isPersistingDropSummary = false

    suspend fun append(entry: DebugLogEntryDto) = withContext(dispatcher) { appendImpl(entry) }

    suspend fun flush() = withContext(dispatcher) {
        flushJob?.cancel()
        flushJob = null
        isFlushScheduled = false
        flushBuffer()
    }

    suspend fun shutdown() = withContext(dispatcher) {
        flushJob?.cancel()
        flushJob = null
        isFlushScheduled = false
        flushBuffer()
    }

    private fun appendImpl(entry: DebugLogEntryDto) {
        buffer.add(entry)
        if (buffer.size >= MAX_BUFFER_SIZE) {
            flushNow()
        } else {
            scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        if (isFlushScheduled) return
        isFlushScheduled = true

        flushJob = scope.launch {
            delay(FLUSH_INTERVAL_MS)
            isFlushScheduled = false
            flushBuffer()
        }
    }

    private fun flushNow() {
        flushJob?.cancel()
        flushJob = null
        isFlushScheduled = false
        scope.launch { flushBuffer() }
    }

    private suspend fun flushBuffer() {
        if (buffer.isEmpty()) return
        val entries = buffer
        buffer = mutableListOf()

        try {
            dataStore.saveDebugLogEntries(entries)
            persistDropSummaryIfNeeded()
            pruneIfDue()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to save debug logs", error)

            // Backpressure: only re-queue if total won't exceed limit.
            val entriesToRequeue = entries.take(MAX_BUFFER_SIZE)
            if (buffer.size + entriesToRequeue.size < MAX_BUFFER_SIZE * 2) {
                buffer.addAll(0, entriesToRequeue)
                droppedEntryCount += entries.size - entriesToRequeue.size
            } else {
                droppedEntryCount += entries.size
            }
        }
    }

    /** Overrides [lastPrune] for tests that need to force [pruneIfDue] to run or skip. */
    fun setLastPruneForTesting(instant: Instant) {
        lastPrune = instant
    }

    /**
     * Runs a window/count prune when at least [DebugLogRetention.PRUNE_INTERVAL_SECONDS] has
     * elapsed since the last pass — keeps a long-lived connection's log bounded without a
     * connect/disconnect cycle, matching Swift's `8c4eb521` fix. A failed prune leaves [lastPrune]
     * unmoved so the next successful flush retries it.
     */
    private suspend fun pruneIfDue() {
        val now = Instant.now()
        if (now.epochSecond - lastPrune.epochSecond < DebugLogRetention.PRUNE_INTERVAL_SECONDS) return
        try {
            dataStore.pruneDebugLogEntries(
                cutoff = now.minusSeconds(DebugLogRetention.WINDOW_SECONDS),
                keepCount = DebugLogRetention.MAX_ENTRIES,
            )
            lastPrune = now
        } catch (error: Exception) {
            Log.e(TAG, "Failed to prune debug logs", error)
        }
    }

    /**
     * Persists a summary of entries dropped since the last successful save. Left uncounted on
     * failure so the loss carries forward to the next attempt instead of being silently reset.
     * Snapshots the count before the save because [flushBuffer] is reentrant across that suspend
     * point: drops recorded while the save is suspended must survive, so only the reported amount
     * is subtracted on success.
     */
    private suspend fun persistDropSummaryIfNeeded() {
        if (droppedEntryCount <= 0 || isPersistingDropSummary) return
        isPersistingDropSummary = true
        try {
            val reported = droppedEntryCount
            val summary = DebugLogEntryDto(
                level = DebugLogLevel.WARNING,
                subsystem = LOG_SUBSYSTEM,
                category = LOG_CATEGORY,
                message = "Lost $reported log entries due to prior save failures",
            )
            try {
                dataStore.saveDebugLogEntries(listOf(summary))
                droppedEntryCount -= reported
            } catch (error: Exception) {
                Log.e(TAG, "Failed to persist dropped-entry summary", error)
            }
        } finally {
            isPersistingDropSummary = false
        }
    }

    companion object {
        const val MAX_PENDING_ENTRIES = 500
        const val MAX_BUFFER_SIZE = 50
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val TAG = "DebugLogBuffer"
        internal const val LOG_SUBSYSTEM = "com.meshcoretwo"
        internal const val LOG_CATEGORY = "DebugLogBuffer"

        private val lock = Any()
        private var sharedBuffer: DebugLogBuffer? = null
        private var pending: MutableList<DebugLogEntryDto> = mutableListOf()

        /**
         * Shared buffer instance for app-wide logging. Reassigned on every connection from
         * [com.meshcoretwo.services.ServiceContainer]'s `init` while [PersistentLogger] records
         * from arbitrary threads. Assigning a buffer atomically takes every entry recorded while
         * none existed (early launch, between connections) and delivers them to the new buffer in
         * record order.
         */
        var shared: DebugLogBuffer?
            get() = synchronized(lock) { sharedBuffer }
            set(value) {
                val drained = synchronized(lock) {
                    sharedBuffer = value
                    if (value == null) return@synchronized emptyList()
                    val current = pending
                    pending = mutableListOf()
                    current
                }
                if (value == null || drained.isEmpty()) return
                value.scope.launch {
                    for (entry in drained) value.appendImpl(entry)
                }
            }

        /**
         * Single entry point for app-wide log delivery: hands [entry] to the current shared
         * buffer, or queues it (bounded, oldest dropped first) until one is assigned.
         */
        fun record(entry: DebugLogEntryDto) {
            val buffer = synchronized(lock) {
                val current = sharedBuffer
                if (current != null) return@synchronized current
                pending.add(entry)
                if (pending.size > MAX_PENDING_ENTRIES) {
                    pending = pending.subList(pending.size - MAX_PENDING_ENTRIES, pending.size).toMutableList()
                }
                null
            } ?: return
            buffer.scope.launch { buffer.appendImpl(entry) }
        }

        /** Empties the pending queue so tests can exercise the no-buffer window from a known state. */
        fun resetPendingStateForTesting() {
            synchronized(lock) { pending = mutableListOf() }
        }
    }
}
