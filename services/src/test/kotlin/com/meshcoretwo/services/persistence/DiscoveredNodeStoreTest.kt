// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.services.backup.BackupDedupKeys
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

/**
 * Exercises [DiscoveredNodeStore] (and, through it, [DiscoveredNodeEntity]/[DiscoveredNodeDto])
 * against a real in-memory Room database via Robolectric, same setup as [ContactStoreTest].
 */
@RunWith(RobolectricTestRunner::class)
class DiscoveredNodeStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: DiscoveredNodeStore
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = DiscoveredNodeStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun meshContact(
        publicKey: ByteArray = ByteArray(32) { it.toByte() },
        name: String = "Repeater 1",
        type: ContactType = ContactType.REPEATER,
        lastAdvertisement: Instant = Instant.ofEpochSecond(900),
        latitude: Double = 12.0,
        longitude: Double = 34.0,
    ) = MeshContact(
        id = publicKey.joinToString("") { "%02x".format(it) },
        publicKey = publicKey,
        type = type,
        flags = ContactFlags.NONE,
        outPathLength = 0xFFu,
        outPath = ByteArray(0),
        advertisedName = name,
        lastAdvertisement = lastAdvertisement,
        latitude = latitude,
        longitude = longitude,
        lastModified = lastAdvertisement,
    )

    @Test
    fun `fetchDiscoveredNodes is empty for an unknown device`() = runTest {
        assertTrue(store.fetchDiscoveredNodes(radioID).isEmpty())
    }

    @Test
    fun `upsertDiscoveredNode inserts a new node`() = runTest {
        val (node, isNew) = store.upsertDiscoveredNode(radioID, meshContact())

        assertTrue(isNew)
        assertEquals("Repeater 1", node.name)
        assertEquals(radioID, node.radioID)
        assertEquals(ContactType.REPEATER, node.nodeType)
        assertTrue(node.hasLocation)
        assertTrue(node.isFloodRouted)
    }

    @Test
    fun `upsertDiscoveredNode updates an existing node matched by radioID and publicKey`() = runTest {
        val key = ByteArray(32) { it.toByte() }
        val (first, _) = store.upsertDiscoveredNode(radioID, meshContact(publicKey = key, name = "Old Name"))

        val (second, isNew) = store.upsertDiscoveredNode(radioID, meshContact(publicKey = key, name = "New Name"))

        assertFalse(isNew)
        assertEquals(first.id, second.id)
        assertEquals("New Name", second.name)
        assertEquals(1, store.fetchDiscoveredNodes(radioID).size)
    }

    @Test
    fun `upsertDiscoveredNode re-stamps lastHeard on update`() = runTest {
        val key = ByteArray(32) { it.toByte() }
        val earlier = Instant.ofEpochSecond(1_000)
        val later = Instant.ofEpochSecond(2_000)
        store.upsertDiscoveredNode(radioID, meshContact(publicKey = key), now = earlier)

        val (updated, _) = store.upsertDiscoveredNode(radioID, meshContact(publicKey = key), now = later)

        assertEquals(later, updated.lastHeard)
    }

    /** Distinct 32-byte keys for `i` in `0..<65536` — the low two bytes carry `i`, big-endian. */
    private fun keyForIndex(i: Int): ByteArray = ByteArray(32).also {
        it[30] = (i shr 8).toByte()
        it[31] = i.toByte()
    }

    @Test
    fun `upsertDiscoveredNode evicts the oldest node once the cap is exceeded`() = runTest {
        val cap = DiscoveredNodeStore.MAX_DISCOVERED_NODES
        for (i in 0 until cap) {
            store.upsertDiscoveredNode(radioID, meshContact(publicKey = keyForIndex(i), name = "Node $i"), now = Instant.ofEpochSecond(i.toLong()))
        }
        assertEquals(cap, store.fetchDiscoveredNodes(radioID).size)

        store.upsertDiscoveredNode(radioID, meshContact(publicKey = keyForIndex(cap), name = "Newest"), now = Instant.ofEpochSecond(cap.toLong()))

        val remaining = store.fetchDiscoveredNodes(radioID)
        assertEquals(cap, remaining.size)
        assertTrue(remaining.any { it.name == "Newest" })
        assertFalse(remaining.any { it.name == "Node 0" })
    }

    @Test
    fun `setInboundHopCount stamps an existing node`() = runTest {
        val key = ByteArray(32) { it.toByte() }
        store.upsertDiscoveredNode(radioID, meshContact(publicKey = key))

        store.setInboundHopCount(radioID, key, hopCount = 2, advertTimestamp = 500u)

        val node = store.fetchDiscoveredNodes(radioID).single()
        assertEquals(2, node.inboundHopCount)
        assertEquals(500u, node.inboundHopAdvertTimestamp)
    }

    @Test
    fun `setInboundHopCount before the node exists is buffered and applied on the next upsert`() = runTest {
        val key = ByteArray(32) { it.toByte() }

        store.setInboundHopCount(radioID, key, hopCount = 3, advertTimestamp = 500u)
        val (node, _) = store.upsertDiscoveredNode(radioID, meshContact(publicKey = key))

        assertEquals(3, node.inboundHopCount)
        assertEquals(500u, node.inboundHopAdvertTimestamp)
    }

    @Test
    fun `setInboundHopCount ignores a farther copy of the same broadcast`() = runTest {
        val key = ByteArray(32) { it.toByte() }
        store.upsertDiscoveredNode(radioID, meshContact(publicKey = key))
        store.setInboundHopCount(radioID, key, hopCount = 1, advertTimestamp = 500u)

        store.setInboundHopCount(radioID, key, hopCount = 3, advertTimestamp = 500u)

        val node = store.fetchDiscoveredNodes(radioID).single()
        assertEquals(1, node.inboundHopCount)
    }

    @Test
    fun `deleteDiscoveredNode removes a single node`() = runTest {
        val (node, _) = store.upsertDiscoveredNode(radioID, meshContact())

        store.deleteDiscoveredNode(node.id)

        assertTrue(store.fetchDiscoveredNodes(radioID).isEmpty())
    }

    @Test
    fun `clearDiscoveredNodes removes every node for a device`() = runTest {
        store.upsertDiscoveredNode(radioID, meshContact(publicKey = ByteArray(32) { 1 }))
        store.upsertDiscoveredNode(radioID, meshContact(publicKey = ByteArray(32) { 2 }))

        store.clearDiscoveredNodes(radioID)

        assertTrue(store.fetchDiscoveredNodes(radioID).isEmpty())
    }

    @Test
    fun `clearDiscoveredNodes does not affect other devices`() = runTest {
        val otherRadioID = UUID.randomUUID()
        store.upsertDiscoveredNode(radioID, meshContact(publicKey = ByteArray(32) { 1 }))
        store.upsertDiscoveredNode(otherRadioID, meshContact(publicKey = ByteArray(32) { 2 }))

        store.clearDiscoveredNodes(radioID)

        assertTrue(store.fetchDiscoveredNodes(radioID).isEmpty())
        assertEquals(1, store.fetchDiscoveredNodes(otherRadioID).size)
    }

    @Test
    fun `a node with no location reports hasLocation false`() = runTest {
        val (node, _) = store.upsertDiscoveredNode(radioID, meshContact(latitude = 0.0, longitude = 0.0))

        assertFalse(node.hasLocation)
        assertNull(node.displayedHopCount)
    }

    private fun discoveredNodeDto(radioID: UUID = this.radioID, publicKey: ByteArray, lastHeard: Instant = Instant.now()) = DiscoveredNodeDto(
        id = UUID.randomUUID(), radioID = radioID, publicKey = publicKey, name = "Node", typeRawValue = 1u,
        lastHeard = lastHeard, lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0,
        outPathLength = 0xFFu, outPath = ByteArray(0), inboundHopCount = null, inboundHopAdvertTimestamp = null,
    )

    @Test
    fun `existingDiscoveredNodeKeys and batchInsertDiscoveredNodes dedup by (radioID, publicKey)`() = runTest {
        store.upsertDiscoveredNode(radioID, meshContact(publicKey = byteArrayOf(1, 1, 1)))
        val existingKeys = store.existingDiscoveredNodeKeys(setOf(radioID))
        assertEquals(setOf(BackupDedupKeys.discoveredNodeKey(radioID, byteArrayOf(1, 1, 1))), existingKeys)

        val duplicate = discoveredNodeDto(publicKey = byteArrayOf(1, 1, 1))
        val fresh = discoveredNodeDto(publicKey = byteArrayOf(2, 2, 2))

        val counts = store.batchInsertDiscoveredNodes(listOf(duplicate, fresh), existingKeys)

        assertEquals(1, counts.inserted)
        assertEquals(1, counts.skipped)
        assertEquals(2, store.fetchDiscoveredNodes(radioID).size)
    }

    @Test
    fun `batchInsertDiscoveredNodes re-mints the surrogate id on insert`() = runTest {
        val dto = discoveredNodeDto(publicKey = byteArrayOf(3, 3, 3))

        store.batchInsertDiscoveredNodes(listOf(dto), emptySet())

        val stored = store.fetchDiscoveredNodes(radioID).single()
        assertNotEquals(dto.id, stored.id)
    }

    @Test
    fun `batchInsertDiscoveredNodes drops the oldest-by-lastHeard new nodes past the per-radio cap`() = runTest {
        // Fill the radio to one below the cap with committed local rows (bypassing DiscoveredNodeStore's
        // own upsert/cap path, which isn't what's under test here).
        val dao = database.discoveredNodeDao()
        for (i in 0 until DiscoveredNodeStore.MAX_DISCOVERED_NODES - 1) {
            dao.insert(
                DiscoveredNodeEntity(
                    id = UUID.randomUUID(), radioID = radioID,
                    publicKey = byteArrayOf((i and 0xFF).toByte(), ((i shr 8) and 0xFF).toByte(), 0x7F),
                    name = "Filler", typeRawValue = 1, lastHeard = Instant.ofEpochSecond(i.toLong()), lastAdvertTimestamp = 0,
                    latitude = 0.0, longitude = 0.0, outPathLength = 0, outPath = ByteArray(0),
                    inboundHopCount = null, inboundHopAdvertTimestamp = null,
                ),
            )
        }
        // Only one slot of room remains: the newer of these two incoming nodes should survive.
        val older = discoveredNodeDto(publicKey = byteArrayOf(9, 1, 1), lastHeard = Instant.ofEpochSecond(1000))
        val newer = discoveredNodeDto(publicKey = byteArrayOf(9, 2, 2), lastHeard = Instant.ofEpochSecond(2000))

        val counts = store.batchInsertDiscoveredNodes(listOf(older, newer), emptySet())

        assertEquals(1, counts.inserted)
        assertEquals(1, counts.dropped)
        val stored = store.fetchDiscoveredNodes(radioID)
        assertEquals(DiscoveredNodeStore.MAX_DISCOVERED_NODES, stored.size)
        assertTrue(stored.any { it.publicKey.contentEquals(newer.publicKey) })
        assertTrue(stored.none { it.publicKey.contentEquals(older.publicKey) })
    }
}
