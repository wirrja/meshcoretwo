// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.logging

import com.meshcoretwo.services.persistence.DebugLogEntryDto
import com.meshcoretwo.services.persistence.DebugLogPersisting
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Ported from Swift's `PersistentLoggerTests` — verifies the `8c4eb521` fix ("skip debug
 * persist"): [PersistentLogger.debug] must not reach [DebugLogBuffer], while every other level
 * still does.
 */
@RunWith(RobolectricTestRunner::class)
class PersistentLoggerTest {
    private val store = FakeDebugLogStore()
    private val buffer = DebugLogBuffer(store)

    @Before
    fun setUp() {
        DebugLogBuffer.shared = buffer
        DebugLogBuffer.resetPendingStateForTesting()
    }

    @After
    fun tearDown() {
        DebugLogBuffer.shared = null
    }

    @Test
    fun `debug does not persist and info does`() = runBlocking {
        val logger = PersistentLogger(subsystem = "test.persist-gate", category = "gate")
        logger.debug("debug-line")
        logger.info("info-line")

        var messages: List<String> = emptyList()
        for (i in 0 until 50) {
            buffer.flush()
            messages = store.savedEntries().map { it.message }
            if (messages.contains("info-line")) break
            delay(20)
        }

        assertFalse(messages.contains("debug-line"))
        assertTrue(messages.contains("info-line"))
    }
}

private class FakeDebugLogStore : DebugLogPersisting {
    private val lock = Any()
    private val entries = mutableListOf<DebugLogEntryDto>()

    override suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>) {
        synchronized(lock) { this.entries.addAll(entries) }
    }

    fun savedEntries(): List<DebugLogEntryDto> = synchronized(lock) { entries.toList() }

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Int): List<DebugLogEntryDto> = emptyList()

    override suspend fun countDebugLogEntries(): Int = synchronized(lock) { entries.size }

    override suspend fun pruneDebugLogEntries(cutoff: Instant, keepCount: Int) = Unit

    override suspend fun clearDebugLogEntries() = Unit
}
