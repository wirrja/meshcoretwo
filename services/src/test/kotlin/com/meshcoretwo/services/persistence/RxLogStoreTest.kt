// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import com.meshcoretwo.protocol.PayloadType
import com.meshcoretwo.protocol.RouteType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
class RxLogStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: RxLogStore
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RxLogStore(database)
    }

    @After
    fun tearDown() = runTest {
        // Shut down before closing the database — a scheduled batch flush is a real-time timer
        // outside this test's coroutine scope, and one firing after close() can wedge Room's
        // process-wide lock for every other test's database. See RxLogStore.shutdown's doc.
        store.shutdown()
        database.close()
    }

    private fun entry(
        id: UUID = UUID.randomUUID(),
        receivedAt: Instant = Instant.now(),
        routeType: RouteType = RouteType.FLOOD,
        payloadType: PayloadType = PayloadType.GROUP_TEXT,
        channelIndex: UByte? = null,
        senderTimestamp: UInt? = null,
        packetPayload: ByteArray = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        pathLength: UByte = 2u,
        decryptStatus: DecryptStatus = DecryptStatus.NOT_APPLICABLE,
    ) = RxLogDto(
        id = id,
        radioID = radioID,
        receivedAt = receivedAt,
        snr = 5.0,
        rssi = -80,
        routeType = routeType,
        payloadType = payloadType,
        payloadVersion = 1u,
        pathLength = pathLength,
        pathNodes = byteArrayOf(0x11, 0x22),
        packetPayload = packetPayload,
        rawPayload = packetPayload,
        packetHash = "deadbeef",
        channelIndex = channelIndex,
        channelName = channelIndex?.let { "Channel $it" },
        decryptStatus = decryptStatus,
        senderTimestamp = senderTimestamp,
    )

    @Test
    fun `saveRxLogEntry then findRxLogEntry finds a channel entry by (channelIndex, senderTimestamp)`() = runTest {
        val saved = entry(channelIndex = 0u, senderTimestamp = 1000u)
        store.saveRxLogEntry(saved)

        val found = store.findRxLogEntry(radioID, channelIndex = 0u, senderTimestamp = 1000u)

        assertEquals(saved.id, found?.id)
        assertEquals(2, found?.pathLength?.toInt())
    }

    @Test
    fun `findRxLogEntry misses when channelIndex or senderTimestamp don't match`() = runTest {
        store.saveRxLogEntry(entry(channelIndex = 0u, senderTimestamp = 1000u))

        assertNull(store.findRxLogEntry(radioID, channelIndex = 1u, senderTimestamp = 1000u))
        assertNull(store.findRxLogEntry(radioID, channelIndex = 0u, senderTimestamp = 2000u))
    }

    @Test
    fun `findRxLogEntry for a DM matches by senderTimestamp with no channelIndex, scoped to text-message payloads`() = runTest {
        store.saveRxLogEntry(entry(channelIndex = null, payloadType = PayloadType.TEXT_MESSAGE, senderTimestamp = 500u, routeType = RouteType.DIRECT))
        // A channel entry sharing the same timestamp must not leak into the DM lookup.
        store.saveRxLogEntry(entry(channelIndex = 3u, senderTimestamp = 500u))

        val found = store.findRxLogEntry(radioID, channelIndex = null, senderTimestamp = 500u)

        assertEquals(RouteType.DIRECT, found?.routeType)
    }

    @Test
    fun `findRxLogEntryBySenderPrefix matches the unencrypted prefix byte within the receive window`() = runTest {
        val payload = byteArrayOf(0x00, 0x42.toByte(), 0x00, 0x00)
        val saved = entry(channelIndex = null, payloadType = PayloadType.TEXT_MESSAGE, packetPayload = payload, receivedAt = Instant.now())
        store.saveRxLogEntry(saved)

        val found = store.findRxLogEntryBySenderPrefix(radioID, senderPrefixByte = 0x42u, receivedSince = Instant.now().minusSeconds(30))

        assertEquals(saved.id, found?.id)
    }

    @Test
    fun `findRxLogEntryBySenderPrefix ignores entries outside the receive window or with a different prefix`() = runTest {
        val payload = byteArrayOf(0x00, 0x42.toByte(), 0x00, 0x00)
        store.saveRxLogEntry(entry(channelIndex = null, payloadType = PayloadType.TEXT_MESSAGE, packetPayload = payload, receivedAt = Instant.now().minusSeconds(120)))
        store.saveRxLogEntry(entry(channelIndex = null, payloadType = PayloadType.TEXT_MESSAGE, packetPayload = byteArrayOf(0x00, 0x99.toByte())))

        assertNull(store.findRxLogEntryBySenderPrefix(radioID, senderPrefixByte = 0x42u, receivedSince = Instant.now().minusSeconds(30)))
    }

    @Test
    fun `fetchRecentEntriesByDecryptStatus filters by status and since`() = runTest {
        val recent = entry(decryptStatus = DecryptStatus.NO_MATCHING_KEY, receivedAt = Instant.now())
        val old = entry(decryptStatus = DecryptStatus.NO_MATCHING_KEY, receivedAt = Instant.now().minusSeconds(120))
        val wrongStatus = entry(decryptStatus = DecryptStatus.SUCCESS, receivedAt = Instant.now())
        store.saveRxLogEntry(recent)
        store.saveRxLogEntry(old)
        store.saveRxLogEntry(wrongStatus)

        val found = store.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.NO_MATCHING_KEY, Instant.now().minusSeconds(60))

        assertEquals(listOf(recent.id), found.map { it.id })
    }

    @Test
    fun `batchUpdateRxLogDecryption moves entries to success with the new attribution`() = runTest {
        val saved = entry(decryptStatus = DecryptStatus.NO_MATCHING_KEY, channelIndex = null, senderTimestamp = null)
        store.saveRxLogEntry(saved)

        store.batchUpdateRxLogDecryption(listOf(RxLogDecryptionUpdate(saved.id, channelIndex = 2u, channelName = "General", senderTimestamp = 777u)))

        val found = store.findRxLogEntry(radioID, channelIndex = 2u, senderTimestamp = 777u)
        assertEquals(DecryptStatus.SUCCESS, found?.decryptStatus)
        assertEquals("General", found?.channelName)
    }

    @Test
    fun `pruneRxLogEntries deletes the oldest rows once past keepCount plus pruneThreshold`() = runTest {
        val entries = (1..12).map { i -> entry(receivedAt = Instant.ofEpochSecond(i.toLong())) }
        entries.forEach { store.saveRxLogEntry(it) }

        store.pruneRxLogEntries(radioID, keepCount = 10, pruneThreshold = 1)

        // 12 > 10 + 1, so prune runs, deleting the 2 oldest (receivedAt = 1, 2) and keeping 10.
        val remaining = store.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.NOT_APPLICABLE, Instant.EPOCH)
        assertEquals(10, remaining.size)
        assertTrue(remaining.none { it.id == entries[0].id || it.id == entries[1].id })
    }

    @Test
    fun `pruneRxLogEntries is a no-op below the threshold`() = runTest {
        repeat(5) { store.saveRxLogEntry(entry()) }

        store.pruneRxLogEntries(radioID, keepCount = 10, pruneThreshold = 1)

        val count = store.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.NOT_APPLICABLE, Instant.EPOCH).size
        assertEquals(5, count)
    }

    @Test
    fun `clearRxLogEntries removes every entry for the device`() = runTest {
        store.saveRxLogEntry(entry())
        store.saveRxLogEntry(entry())

        store.clearRxLogEntries(radioID)

        assertTrue(store.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.NOT_APPLICABLE, Instant.EPOCH).isEmpty())
    }

    @Test
    fun `a debounced partial batch is committed to the database after the flush interval`() = runTest {
        store.saveRxLogEntry(entry())

        // Real time: the flush timer lives on the store's own dispatcher, not on runTest's virtual clock.
        withContext(Dispatchers.Default) { delay(RxLogRetention.FLUSH_INTERVAL_MS + 500) }

        assertEquals(1, database.rxLogDao().fetchRecent(radioID, 10).size)
    }
}
