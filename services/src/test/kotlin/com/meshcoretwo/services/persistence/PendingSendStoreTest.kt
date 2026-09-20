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
import java.util.UUID

/**
 * Exercises [PendingSendStore.purgeOrphanPendingSends] against a real in-memory Room database via
 * Robolectric — ported from Swift's `PersistenceStoreTests.testPurgeOrphanPendingSends`-style
 * coverage of `purgeOrphanPendingSends`.
 */
@RunWith(RobolectricTestRunner::class)
class PendingSendStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var pendingSendStore: PendingSendStore
    private lateinit var deviceStore: DeviceStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        pendingSendStore = PendingSendStore(database)
        deviceStore = DeviceStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun deviceDto(radioID: UUID) = DeviceDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        publicKey = ByteArray(32) { it.toByte() },
        nodeName = "Radio",
        firmwareVersion = 10u,
        firmwareVersionString = "v1.16.0",
        manufacturerName = "Acme",
        buildDate = "2026-01-01",
        maxContacts = 100u,
        maxChannels = 8u,
        frequency = 915_000u,
        bandwidth = 250_000u,
        spreadingFactor = 10u,
        codingRate = 5u,
        txPower = 20,
        maxTxPower = 20,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0u,
        lastConnected = Instant.ofEpochSecond(1000),
        lastContactSync = 0u,
        isActive = true,
        ocvPreset = null,
        customOCVArrayString = null,
    )

    private fun pendingSendDto(radioID: UUID) = PendingSendDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        messageID = UUID.randomUUID(),
        kind = PendingSendKind.DM,
        contactID = UUID.randomUUID(),
        channelIndex = null,
        isResend = false,
        messageText = "hello",
        messageTimestamp = 1000u,
        localNodeName = null,
        sequence = 1,
        enqueuedAt = Instant.ofEpochSecond(1000),
    )

    @Test
    fun `purgeOrphanPendingSends deletes rows whose radio has no Device row`() = runTest {
        val knownRadioID = UUID.randomUUID()
        val orphanRadioID = UUID.randomUUID()
        deviceStore.saveDevice(deviceDto(knownRadioID))

        val kept = pendingSendStore.insertPendingSendAssigningSequence(pendingSendDto(knownRadioID))
        pendingSendStore.insertPendingSendAssigningSequence(pendingSendDto(orphanRadioID))

        val deleted = pendingSendStore.purgeOrphanPendingSends()

        assertEquals(1, deleted)
        assertEquals(listOf(kept.messageID), pendingSendStore.fetchPendingSends(knownRadioID).map { it.messageID })
        assertEquals(emptyList<PendingSendDto>(), pendingSendStore.fetchPendingSends(orphanRadioID))
    }

    @Test
    fun `purgeOrphanPendingSends is a no-op when every row has a matching Device`() = runTest {
        val radioID = UUID.randomUUID()
        deviceStore.saveDevice(deviceDto(radioID))
        pendingSendStore.insertPendingSendAssigningSequence(pendingSendDto(radioID))

        val deleted = pendingSendStore.purgeOrphanPendingSends()

        assertEquals(0, deleted)
        assertEquals(1, pendingSendStore.fetchPendingSends(radioID).size)
    }
}
