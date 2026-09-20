// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.logging

import com.meshcoretwo.services.persistence.DebugLogEntryDto
import com.meshcoretwo.services.persistence.DebugLogLevel
import com.meshcoretwo.services.persistence.DebugLogPersisting
import com.meshcoretwo.services.persistence.DebugLogRetention
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Ported from Swift's `DebugLogBufferTests`. Swift's suite is `.serialized` because it and
 * `ServiceContainer.init` both reassign the process-global [DebugLogBuffer.shared] and could race
 * across concurrently-running suites; this module's default Gradle test execution runs one test
 * class at a time, so a plain per-test reset (see [setUp]/[tearDown]) is enough here without
 * Swift's bounded-retry read-back dance. Runs under Robolectric (unlike most non-Room logic tests
 * in this module) purely so [android.util.Log] — called by [PersistentLogger] and
 * [DebugLogBuffer]'s failure-path logging — is shadowed instead of throwing "not mocked".
 */
@RunWith(RobolectricTestRunner::class)
class DebugLogBufferTest {
    @Before
    @After
    fun resetSharedBuffer() {
        DebugLogBuffer.shared = null
        DebugLogBuffer.resetPendingStateForTesting()
    }

    private fun entry(category: String, message: String) = DebugLogEntryDto(
        level = DebugLogLevel.INFO,
        subsystem = "test",
        category = category,
        message = message,
    )

    /** Repeatedly flushes [buffer] and re-checks [store] for entries in [category], absorbing the delay before a fire-and-forget append coroutine runs. */
    private suspend fun pollForEntries(
        store: RecordingDebugLogStore,
        buffer: DebugLogBuffer,
        category: String,
        minimumCount: Int,
        attempts: Int = 50,
        delayMs: Long = 20,
    ): List<DebugLogEntryDto> {
        repeat(attempts) {
            buffer.flush()
            val matches = store.savedEntries().filter { it.category == category }
            if (matches.size >= minimumCount) return matches
            delay(delayMs)
        }
        return store.savedEntries().filter { it.category == category }
    }

    @Test
    fun `shared get and set round-trip`() = runBlocking {
        val buffer = DebugLogBuffer(RecordingDebugLogStore())
        DebugLogBuffer.shared = buffer
        assertTrue(DebugLogBuffer.shared === buffer)
    }

    @Test
    fun `entries recorded before shared is assigned are drained in order once a buffer is set`() = runBlocking {
        val store = RecordingDebugLogStore()
        val buffer = DebugLogBuffer(store)
        val category = "pending-order"
        val logger = PersistentLogger(subsystem = "test.pending", category = category)

        logger.info("first")
        logger.info("second")

        DebugLogBuffer.shared = buffer
        val entries = pollForEntries(store, buffer, category, minimumCount = 2)

        assertEquals(listOf("first", "second"), entries.map { it.message })
    }

    @Test
    fun `pending queue drops oldest entries once the bound is exceeded`() = runBlocking {
        val store = RecordingDebugLogStore()
        val buffer = DebugLogBuffer(store)
        val overflow = 3
        val total = DebugLogBuffer.MAX_PENDING_ENTRIES + overflow
        val category = "pending-overflow"

        for (index in 0 until total) {
            DebugLogBuffer.record(entry(category, index.toString()))
        }

        DebugLogBuffer.shared = buffer
        val entries = pollForEntries(store, buffer, category, minimumCount = DebugLogBuffer.MAX_PENDING_ENTRIES)

        assertEquals(DebugLogBuffer.MAX_PENDING_ENTRIES, entries.size)
        assertEquals(overflow.toString(), entries.first().message)
        assertEquals((total - 1).toString(), entries.last().message)
    }

    @Test
    fun `dropped entries during save failures are reported once the store recovers`() = runBlocking {
        val store = GatedDebugLogStore()
        val buffer = DebugLogBuffer(store)

        accumulateDroppedBatch(store, buffer)

        var saved: List<DebugLogEntryDto> = emptyList()
        for (i in 0 until 100) {
            buffer.flush()
            saved = store.savedSnapshot()
            if (saved.any { it.level == DebugLogLevel.WARNING }) break
            delay(20)
        }

        val realEntries = saved.filter { it.level != DebugLogLevel.WARNING }
        assertEquals(DebugLogBuffer.MAX_BUFFER_SIZE, realEntries.size)

        val summary = saved.firstOrNull { it.category == DebugLogBuffer.LOG_CATEGORY && it.level == DebugLogLevel.WARNING }
        assertNotNull(summary)
        assertTrue(summary!!.message.contains(DebugLogBuffer.MAX_BUFFER_SIZE.toString()))
    }

    @Test
    fun `a failed summary save carries the dropped count forward to the next successful save`() = runBlocking {
        val store = GatedDebugLogStore()
        val buffer = DebugLogBuffer(store)

        accumulateDroppedBatch(store, buffer)
        store.setFailNextSummarySave(true)

        var saved: List<DebugLogEntryDto> = emptyList()
        for (i in 0 until 100) {
            buffer.flush()
            saved = store.savedSnapshot()
            if (saved.count { it.level != DebugLogLevel.WARNING } >= DebugLogBuffer.MAX_BUFFER_SIZE) break
            delay(20)
        }
        assertTrue(saved.none { it.level == DebugLogLevel.WARNING })

        buffer.append(entry("post-recovery", "post-recovery"))
        for (i in 0 until 100) {
            buffer.flush()
            saved = store.savedSnapshot()
            if (saved.any { it.level == DebugLogLevel.WARNING }) break
            delay(20)
        }

        val summary = saved.firstOrNull { it.category == DebugLogBuffer.LOG_CATEGORY && it.level == DebugLogLevel.WARNING }
        assertNotNull(summary)
        assertTrue(summary!!.message.contains(DebugLogBuffer.MAX_BUFFER_SIZE.toString()))
    }

    /**
     * Drives two size-triggered flushes into the gated store and releases them while it is still
     * failing, leaving the buffer with [DebugLogBuffer.MAX_BUFFER_SIZE] surviving entries and a
     * dropped count of [DebugLogBuffer.MAX_BUFFER_SIZE] (one batch always loses to the backlog
     * cap). Returns once both saves have been held at the gate.
     */
    private suspend fun accumulateDroppedBatch(store: GatedDebugLogStore, buffer: DebugLogBuffer) {
        for (index in 0 until DebugLogBuffer.MAX_BUFFER_SIZE) {
            buffer.append(entry("a", "a$index"))
        }
        for (index in 0 until DebugLogBuffer.MAX_BUFFER_SIZE) {
            buffer.append(entry("b", "b$index"))
        }

        for (i in 0 until 200) {
            if (store.pendingSaveCount >= 2) break
            delay(20)
        }
        assertEquals(2, store.pendingSaveCount)

        store.releaseWaiters()
        store.setShouldFail(false)
    }

    // MARK: - Hourly prune

    private fun expectWindowBackedPrune(store: RecordingDebugLogStore) {
        assertEquals(DebugLogRetention.MAX_ENTRIES, store.lastPruneKeepCount())
        val cutoff = store.lastPruneCutoff()
        assertNotNull(cutoff)
        val expected = Instant.now().minusSeconds(DebugLogRetention.WINDOW_SECONDS)
        assertTrue(Math.abs(cutoff!!.epochSecond - expected.epochSecond) < 2)
    }

    @Test
    fun `hourly prune is skipped when last prune is within pruneInterval`() = runBlocking {
        val store = RecordingDebugLogStore()
        val buffer = DebugLogBuffer(store)
        buffer.setLastPruneForTesting(Instant.now())

        buffer.append(entry("prune", "recent"))
        buffer.flush()

        assertEquals(0, store.pruneCallCount())
    }

    @Test
    fun `hourly prune runs when last prune is older than pruneInterval`() = runBlocking {
        val store = RecordingDebugLogStore()
        val buffer = DebugLogBuffer(store)
        buffer.setLastPruneForTesting(Instant.now().minusSeconds(DebugLogRetention.PRUNE_INTERVAL_SECONDS))

        buffer.append(entry("prune", "stale"))
        buffer.flush()

        assertEquals(1, store.pruneCallCount())
        expectWindowBackedPrune(store)
    }

    @Test
    fun `hourly prune retries on the next flush after a failed prune`() = runBlocking {
        val store = RecordingDebugLogStore()
        store.setPruneShouldFail(true)
        val buffer = DebugLogBuffer(store)
        buffer.setLastPruneForTesting(Instant.now().minusSeconds(DebugLogRetention.PRUNE_INTERVAL_SECONDS))

        buffer.append(entry("prune", "first"))
        buffer.flush()
        assertEquals(1, store.pruneCallCount())

        buffer.append(entry("prune", "second"))
        buffer.flush()
        assertEquals(2, store.pruneCallCount())
        expectWindowBackedPrune(store)
    }
}

/** A [DebugLogPersisting] fake that just remembers every saved entry, thread-safely. */
private class RecordingDebugLogStore : DebugLogPersisting {
    private val lock = Any()
    private val entries = mutableListOf<DebugLogEntryDto>()
    private var pruneCalls = 0
    private var lastCutoff: Instant? = null
    private var lastKeepCount: Int? = null
    private var pruneShouldFail = false

    override suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>) {
        synchronized(lock) { this.entries.addAll(entries) }
    }

    fun savedEntries(): List<DebugLogEntryDto> = synchronized(lock) { entries.toList() }

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Int): List<DebugLogEntryDto> = emptyList()

    override suspend fun countDebugLogEntries(): Int = synchronized(lock) { entries.size }

    override suspend fun pruneDebugLogEntries(cutoff: Instant, keepCount: Int) {
        val shouldFail = synchronized(lock) {
            pruneCalls++
            lastCutoff = cutoff
            lastKeepCount = keepCount
            pruneShouldFail
        }
        if (shouldFail) throw DebugLogSaveFailure
    }

    override suspend fun clearDebugLogEntries() = Unit

    fun pruneCallCount(): Int = synchronized(lock) { pruneCalls }

    fun lastPruneCutoff(): Instant? = synchronized(lock) { lastCutoff }

    fun lastPruneKeepCount(): Int? = synchronized(lock) { lastKeepCount }

    fun setPruneShouldFail(value: Boolean) = synchronized(lock) { pruneShouldFail = value }
}

/**
 * A [DebugLogPersisting] fake whose saves block on a gate until released. Lets a test hold two
 * size-triggered flushes in flight at once, so both fail together and the second observably hits
 * the backlog cap — ported from Swift's `GatedDebugLogStore`.
 */
private class GatedDebugLogStore : DebugLogPersisting {
    private val lock = Any()
    private val entries = mutableListOf<DebugLogEntryDto>()
    private var shouldFail = true
    private var failNextSummarySave = false
    private val waiters = mutableListOf<CompletableDeferred<Unit>>()

    val pendingSaveCount: Int get() = synchronized(lock) { waiters.size }

    fun savedSnapshot(): List<DebugLogEntryDto> = synchronized(lock) { entries.toList() }

    override suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>) {
        val willFail = synchronized(lock) { shouldFail }
        if (willFail) {
            val waiter = CompletableDeferred<Unit>()
            synchronized(lock) { waiters.add(waiter) }
            waiter.await()
            throw DebugLogSaveFailure
        }
        val failThisSummary = synchronized(lock) {
            if (failNextSummarySave && entries.size == 1) {
                failNextSummarySave = false
                true
            } else {
                false
            }
        }
        if (failThisSummary) throw DebugLogSaveFailure
        synchronized(lock) { this.entries.addAll(entries) }
    }

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Int): List<DebugLogEntryDto> = emptyList()

    override suspend fun countDebugLogEntries(): Int = savedSnapshot().size

    override suspend fun pruneDebugLogEntries(cutoff: Instant, keepCount: Int) = Unit

    override suspend fun clearDebugLogEntries() = Unit

    /** Resumes every save currently blocked on the gate. */
    fun releaseWaiters() {
        val pending = synchronized(lock) {
            val current = waiters.toList()
            waiters.clear()
            current
        }
        pending.forEach { it.complete(Unit) }
    }

    fun setShouldFail(value: Boolean) = synchronized(lock) { shouldFail = value }

    /** Fails the next single-entry save (the drop summary is always saved alone) while letting multi-entry batch saves through. */
    fun setFailNextSummarySave(value: Boolean) = synchronized(lock) { failNextSummarySave = value }
}

private object DebugLogSaveFailure : Exception("save failed")
