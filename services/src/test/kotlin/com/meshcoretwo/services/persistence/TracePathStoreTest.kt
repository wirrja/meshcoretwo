// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TracePathStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: TracePathStore
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = TracePathStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun run(
        id: UUID = UUID.randomUUID(),
        date: Instant = Instant.now(),
        success: Boolean = true,
        roundTripMs: Int = 500,
        hopsSNR: List<Double> = listOf(4.5, 3.0),
    ) = TracePathRunDto(id = id, date = date, success = success, roundTripMs = roundTripMs, hopsSNR = hopsSNR)

    @Test
    fun `createSavedTracePath with no initial run persists an empty run history`() = runTest {
        val saved = store.createSavedTracePath(radioID, "Tower", byteArrayOf(1, 2), hashSize = 1, initialRun = null)

        assertTrue(saved.runs.isEmpty())
        assertEquals(0, saved.runCount)
        assertEquals(100, saved.successRate)
        assertNull(saved.averageRoundTripMs)
    }

    @Test
    fun `createSavedTracePath with an initial run persists it atomically`() = runTest {
        val initial = run(roundTripMs = 800)

        val saved = store.createSavedTracePath(radioID, "Tower to Barn", byteArrayOf(1, 2, 3), hashSize = 1, initialRun = initial)

        assertEquals(1, saved.runCount)
        assertEquals(800, saved.averageRoundTripMs)
        val fetched = store.fetchSavedTracePath(saved.id)
        assertEquals(listOf(initial.id), fetched?.runs?.map { it.id })
    }

    @Test
    fun `fetchSavedTracePaths returns every saved path for the device, newest first`() = runTest {
        val first = store.createSavedTracePath(radioID, "First", byteArrayOf(1), hashSize = 1, initialRun = null, createdDate = Instant.ofEpochSecond(1000))
        val second = store.createSavedTracePath(radioID, "Second", byteArrayOf(2), hashSize = 1, initialRun = null, createdDate = Instant.ofEpochSecond(2000))
        store.createSavedTracePath(UUID.randomUUID(), "Other device", byteArrayOf(3), hashSize = 1, initialRun = null)

        val paths = store.fetchSavedTracePaths(radioID)

        assertEquals(listOf(second.id, first.id), paths.map { it.id })
    }

    @Test
    fun `fetchSavedTracePath returns null for an unknown id`() = runTest {
        assertNull(store.fetchSavedTracePath(UUID.randomUUID()))
    }

    @Test
    fun `updateSavedTracePathName renames without touching the path bytes or runs`() = runTest {
        val saved = store.createSavedTracePath(radioID, "Old name", byteArrayOf(9), hashSize = 1, initialRun = run())

        store.updateSavedTracePathName(saved.id, "New name")

        val fetched = store.fetchSavedTracePath(saved.id)
        assertEquals("New name", fetched?.name)
        assertEquals(1, fetched?.runCount)
    }

    @Test
    fun `updateSavedTracePathName is a no-op for an unknown id`() = runTest {
        store.updateSavedTracePathName(UUID.randomUUID(), "New name")
    }

    @Test
    fun `appendTracePathRun adds to the history and updates aggregates`() = runTest {
        val saved = store.createSavedTracePath(radioID, "Path", byteArrayOf(1), hashSize = 1, initialRun = run(success = true, roundTripMs = 400))

        store.appendTracePathRun(saved.id, run(success = false, roundTripMs = 0))
        store.appendTracePathRun(saved.id, run(success = true, roundTripMs = 600))

        val fetched = store.fetchSavedTracePath(saved.id)!!
        assertEquals(3, fetched.runCount)
        assertEquals(500, fetched.averageRoundTripMs) // (400 + 600) / 2, the failed run excluded
        assertEquals(66, fetched.successRate) // 2 of 3 succeeded
    }

    @Test
    fun `recentRTTs returns up to 10 successful runs, oldest of the ten first`() = runTest {
        val saved = store.createSavedTracePath(radioID, "Path", byteArrayOf(1), hashSize = 1, initialRun = null)
        // 12 successful runs at increasing timestamps/RTTs; only the most recent 10 should surface, oldest-first.
        for (i in 1..12) {
            store.appendTracePathRun(saved.id, run(date = Instant.ofEpochSecond(i.toLong()), roundTripMs = i * 100))
        }

        val fetched = store.fetchSavedTracePath(saved.id)!!

        assertEquals((3..12).map { it * 100 }, fetched.recentRTTs)
    }

    @Test
    fun `lastRunDate reports the most recent run's date`() = runTest {
        val saved = store.createSavedTracePath(radioID, "Path", byteArrayOf(1), hashSize = 1, initialRun = run(date = Instant.ofEpochSecond(100)))

        store.appendTracePathRun(saved.id, run(date = Instant.ofEpochSecond(300)))
        store.appendTracePathRun(saved.id, run(date = Instant.ofEpochSecond(200)))

        assertEquals(Instant.ofEpochSecond(300), store.fetchSavedTracePath(saved.id)?.lastRunDate)
    }

    @Test
    fun `deleteSavedTracePath removes the path and every run in its history`() = runTest {
        val saved = store.createSavedTracePath(radioID, "Path", byteArrayOf(1), hashSize = 1, initialRun = run())
        store.appendTracePathRun(saved.id, run())

        store.deleteSavedTracePath(saved.id)

        assertNull(store.fetchSavedTracePath(saved.id))
        assertTrue(store.fetchSavedTracePaths(radioID).isEmpty())
    }

    @Test
    fun `deleteSavedTracePath is a no-op for an unknown id`() = runTest {
        store.deleteSavedTracePath(UUID.randomUUID())
    }

    @Test
    fun `batchInsertSavedTracePaths inserts a new path with its runs, keeping the backup id`() = runTest {
        val dto = TracePathDto(
            id = UUID.randomUUID(), radioID = radioID, name = "Tower", pathBytes = byteArrayOf(1, 2), hashSize = 1,
            createdDate = Instant.ofEpochSecond(1000), runs = listOf(run()),
        )

        val counts = store.batchInsertSavedTracePaths(listOf(dto))

        assertEquals(1, counts.inserted)
        val saved = store.fetchSavedTracePath(dto.id)!!
        assertEquals(1, saved.runCount)
    }

    @Test
    fun `batchInsertSavedTracePaths appends a new run to a matching existing path instead of duplicating it`() = runTest {
        val existing = store.createSavedTracePath(radioID, "Tower", byteArrayOf(1, 2), hashSize = 1, initialRun = null)
        val newRun = run()
        val backup = TracePathDto(
            id = UUID.randomUUID(), radioID = radioID, name = "Tower (backup)", pathBytes = byteArrayOf(1, 2), hashSize = 1,
            createdDate = Instant.ofEpochSecond(2000), runs = listOf(newRun),
        )

        val counts = store.batchInsertSavedTracePaths(listOf(backup))

        assertEquals(0, counts.inserted)
        assertEquals(1, counts.merged)
        assertEquals(1, counts.skipped)
        val paths = store.fetchSavedTracePaths(radioID)
        assertEquals(1, paths.size)
        assertEquals(existing.id, paths.single().id)
        assertEquals(1, paths.single().runCount)
    }

    @Test
    fun `batchInsertSavedTracePaths does not duplicate a run id already known store-wide`() = runTest {
        val sharedRun = run()
        store.createSavedTracePath(radioID, "Tower", byteArrayOf(1, 2), hashSize = 1, initialRun = sharedRun)
        val backup = TracePathDto(
            id = UUID.randomUUID(), radioID = radioID, name = "Tower", pathBytes = byteArrayOf(1, 2), hashSize = 1,
            createdDate = Instant.ofEpochSecond(2000), runs = listOf(sharedRun),
        )

        val counts = store.batchInsertSavedTracePaths(listOf(backup))

        assertEquals(0, counts.merged)
        assertEquals(1, store.fetchSavedTracePaths(radioID).single().runCount)
    }
}
