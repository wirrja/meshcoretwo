// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

/**
 * Exercises [DebugLogStore] (and, through it, [DebugLogEntity]/[DebugLogEntryDto]) against a real
 * in-memory Room database via Robolectric — time-based retention with a hard row ceiling, ported
 * from Swift's `DebugLogRetentionPruneTests`.
 */
@RunWith(RobolectricTestRunner::class)
class DebugLogStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: DebugLogStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = DebugLogStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entry(ageSeconds: Long) = DebugLogEntryDto(
        timestamp = Instant.now().minusSeconds(ageSeconds),
        level = DebugLogLevel.INFO,
        subsystem = "test",
        category = "retention",
        message = "entry aged ${ageSeconds}s",
    )

    @Test
    fun `prune keeps a recent entry and drops one older than the window`() = runTest {
        val recent = entry(ageSeconds = 60)
        val stale = entry(ageSeconds = DebugLogRetention.WINDOW_SECONDS + 3600)
        store.saveDebugLogEntries(listOf(recent, stale))

        store.pruneDebugLogEntries(
            cutoff = Instant.now().minusSeconds(DebugLogRetention.WINDOW_SECONDS),
            keepCount = DebugLogRetention.MAX_ENTRIES,
        )

        val remaining = store.fetchDebugLogEntries(since = Instant.EPOCH, limit = 10)
        assertEquals(listOf(recent.id), remaining.map { it.id })
    }

    @Test
    fun `prune enforces the ceiling when the window alone exceeds it`() = runTest {
        // All entries are inside the window; only the ceiling can drop any.
        val entries = (0 until 10).map { entry(ageSeconds = it * 60L) }
        store.saveDebugLogEntries(entries)

        store.pruneDebugLogEntries(
            cutoff = Instant.now().minusSeconds(DebugLogRetention.WINDOW_SECONDS),
            keepCount = 4,
        )

        val remaining = store.fetchDebugLogEntries(since = Instant.EPOCH, limit = 20)
        assertEquals(4, remaining.size)
        // The newest entries survive; the oldest are the ones deleted.
        assertEquals(entries.take(4).map { it.id }.toSet(), remaining.map { it.id }.toSet())
    }

    @Test
    fun `clearDebugLogEntries removes everything`() = runTest {
        store.saveDebugLogEntries(listOf(entry(0), entry(60)))
        assertEquals(2, store.countDebugLogEntries())

        store.clearDebugLogEntries()

        assertEquals(0, store.countDebugLogEntries())
    }
}
