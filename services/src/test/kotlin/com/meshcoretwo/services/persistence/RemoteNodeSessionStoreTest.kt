// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Room
import com.meshcoretwo.services.backup.BackupDedupKeys
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Exercises [RemoteNodeSessionStore] (and, through it, [RemoteNodeSessionEntity]/[RemoteNodeSessionDto])
 * against a real in-memory Room database via Robolectric — see [ContactStoreTest]'s doc for why
 * Room needs no `AndroidKeyStore` workaround the way `EncryptedSharedPreferences` does.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteNodeSessionStoreTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var store: RemoteNodeSessionStore
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RemoteNodeSessionStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun testSession(
        publicKey: ByteArray = ByteArray(32) { it.toByte() },
        role: RemoteNodeRole = RemoteNodeRole.REPEATER,
        isConnected: Boolean = false,
    ) = RemoteNodeSessionDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        publicKey = publicKey,
        name = "Node",
        role = role,
        isConnected = isConnected,
    )

    @Test
    fun `fetchSession by id is null for an unknown id`() = runTest {
        assertNull(store.fetchSession(UUID.randomUUID()))
    }

    @Test
    fun `saveSession then fetchSession by id round-trips`() = runTest {
        val dto = testSession()
        store.saveSession(dto)
        assertEquals(dto, store.fetchSession(dto.id))
    }

    @Test
    fun `fetchSession by radioID and public key finds a session on that radio`() = runTest {
        val publicKey = ByteArray(32) { 0x42 }
        val dto = testSession(publicKey = publicKey)
        store.saveSession(dto)

        assertEquals(dto.id, store.fetchSession(radioID, publicKey)?.id)
    }

    @Test
    fun `fetchSession by radioID and public key does not find another radio's session`() = runTest {
        val publicKey = ByteArray(32) { 0x42 }
        val otherRadioID = UUID.randomUUID()
        store.saveSession(testSession(publicKey = publicKey).copy(radioID = otherRadioID))

        // A session's public key is a mesh-wide identity, but the session row is scoped to one
        // radio's partition — see RemoteNodeSessionEntity's class doc.
        assertNull(store.fetchSession(radioID, publicKey))
    }

    @Test
    fun `fetchSessionByPrefix matches on the first 6 bytes`() = runTest {
        val publicKey = ByteArray(32) { it.toByte() }
        val dto = testSession(publicKey = publicKey)
        store.saveSession(dto)

        val found = store.fetchSessionByPrefix(publicKey.copyOfRange(0, 6))
        assertEquals(dto.id, found?.id)
    }

    @Test
    fun `fetchSessionByPrefix with radioID does not find another radio's session`() = runTest {
        val publicKey = ByteArray(32) { it.toByte() }
        val otherRadioID = UUID.randomUUID()
        store.saveSession(testSession(publicKey = publicKey).copy(radioID = otherRadioID))

        assertNull(store.fetchSessionByPrefix(radioID, publicKey.copyOfRange(0, 6)))
    }

    @Test
    fun `saveSession updates an existing row matched by id`() = runTest {
        val dto = testSession()
        store.saveSession(dto)

        store.saveSession(dto.copy(name = "Renamed"))

        assertEquals("Renamed", store.fetchSession(dto.id)?.name)
    }

    @Test
    fun `updateConnection stamps lastConnectedDate when connecting`() = runTest {
        val dto = testSession()
        store.saveSession(dto)

        store.updateConnection(dto.id, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)

        val updated = store.fetchSession(dto.id)
        assertTrue(updated?.isConnected == true)
        assertEquals(RoomPermissionLevel.ADMIN, updated?.permissionLevel)
        assertNotNull(updated?.lastConnectedDate)
    }

    @Test
    fun `updateConnection leaves lastConnectedDate untouched when disconnecting`() = runTest {
        val dto = testSession()
        store.saveSession(dto)
        store.updateConnection(dto.id, isConnected = true, permissionLevel = RoomPermissionLevel.GUEST)
        val connectedAt = store.fetchSession(dto.id)?.lastConnectedDate

        store.updateConnection(dto.id, isConnected = false, permissionLevel = RoomPermissionLevel.GUEST)

        val disconnected = store.fetchSession(dto.id)
        assertFalse(disconnected?.isConnected == true)
        assertEquals(connectedAt, disconnected?.lastConnectedDate)
    }

    @Test
    fun `updateConnection is a no-op for an unknown session`() = runTest {
        store.updateConnection(UUID.randomUUID(), isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        // No exception; nothing to assert beyond "didn't crash".
    }

    @Test
    fun `markDisconnected clears isConnected without touching permission`() = runTest {
        val dto = testSession()
        store.saveSession(dto)
        store.updateConnection(dto.id, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)

        store.markDisconnected(dto.id)

        val updated = store.fetchSession(dto.id)
        assertFalse(updated?.isConnected == true)
        assertEquals(RoomPermissionLevel.ADMIN, updated?.permissionLevel)
    }

    @Test
    fun `fetchConnectedSessions returns only connected rows`() = runTest {
        val connected = testSession(publicKey = ByteArray(32) { 0x01 })
        val disconnected = testSession(publicKey = ByteArray(32) { 0x02 })
        store.saveSession(connected)
        store.saveSession(disconnected)
        store.updateConnection(connected.id, isConnected = true, permissionLevel = RoomPermissionLevel.GUEST)

        val result = store.fetchConnectedSessions()

        assertEquals(1, result.size)
        assertEquals(connected.id, result.first().id)
    }

    @Test
    fun `fetchSessions returns every session for the radio`() = runTest {
        val first = testSession(publicKey = ByteArray(32) { 0x03 })
        val second = testSession(publicKey = ByteArray(32) { 0x04 })
        store.saveSession(first)
        store.saveSession(second)

        val result = store.fetchSessions(radioID)

        assertEquals(setOf(first.id, second.id), result.map { it.id }.toSet())
    }

    @Test
    fun `cleanupDuplicateSessions deletes every other row sharing the public key`() = runTest {
        val publicKey = ByteArray(32) { 0x07 }
        val keep = testSession(publicKey = publicKey)
        val duplicate = testSession(publicKey = publicKey)
        val unrelated = testSession(publicKey = ByteArray(32) { 0x09 })
        store.saveSession(keep)
        store.saveSession(duplicate)
        store.saveSession(unrelated)

        store.cleanupDuplicateSessions(publicKey, keepID = keep.id)

        assertNotNull(store.fetchSession(keep.id))
        assertNull(store.fetchSession(duplicate.id))
        assertNotNull(store.fetchSession(unrelated.id))
    }

    @Test
    fun `cleanupDuplicateSessions does not delete a same-public-key session on another radio`() = runTest {
        val publicKey = ByteArray(32) { 0x07 }
        val keep = testSession(publicKey = publicKey)
        val otherRadiosSession = testSession(publicKey = publicKey).copy(radioID = UUID.randomUUID())
        store.saveSession(keep)
        store.saveSession(otherRadiosSession)

        store.cleanupDuplicateSessions(publicKey, keepID = keep.id)

        assertNotNull(store.fetchSession(keep.id))
        assertNotNull(store.fetchSession(otherRadiosSession.id))
    }

    @Test
    fun `deleteSession removes the row`() = runTest {
        val dto = testSession()
        store.saveSession(dto)

        store.deleteSession(dto.id)

        assertNull(store.fetchSession(dto.id))
    }

    @Test
    fun `markRoomSessionConnected connects a disconnected session and reports the change`() = runTest {
        val dto = testSession(isConnected = false)
        store.saveSession(dto)

        val changed = store.markRoomSessionConnected(dto.id)

        assertTrue(changed)
        assertTrue(store.fetchSession(dto.id)?.isConnected == true)
    }

    @Test
    fun `markRoomSessionConnected is a no-op for an already-connected session`() = runTest {
        val dto = testSession(isConnected = true)
        store.saveSession(dto)

        val changed = store.markRoomSessionConnected(dto.id)

        assertFalse(changed)
    }

    @Test
    fun `markRoomSessionConnected returns false for an unknown session`() = runTest {
        assertFalse(store.markRoomSessionConnected(UUID.randomUUID()))
    }

    @Test
    fun `updateRoomActivity advances lastSyncTimestamp only forward`() = runTest {
        val dto = testSession()
        store.saveSession(dto)

        store.updateRoomActivity(dto.id, syncTimestamp = 500u)
        assertEquals(500L, store.fetchSession(dto.id)?.lastSyncTimestamp)

        store.updateRoomActivity(dto.id, syncTimestamp = 200u)
        assertEquals(500L, store.fetchSession(dto.id)?.lastSyncTimestamp)

        store.updateRoomActivity(dto.id, syncTimestamp = 900u)
        assertEquals(900L, store.fetchSession(dto.id)?.lastSyncTimestamp)
    }

    @Test
    fun `updateRoomActivity always stamps lastMessageDate even without a syncTimestamp`() = runTest {
        val dto = testSession()
        store.saveSession(dto)

        store.updateRoomActivity(dto.id)

        assertNotNull(store.fetchSession(dto.id)?.lastMessageDate)
        assertEquals(0L, store.fetchSession(dto.id)?.lastSyncTimestamp)
    }

    @Test
    fun `incrementUnreadCount and resetUnreadCount round-trip`() = runTest {
        val dto = testSession()
        store.saveSession(dto)

        store.incrementUnreadCount(dto.id)
        store.incrementUnreadCount(dto.id)
        assertEquals(2, store.fetchSession(dto.id)?.unreadCount)

        store.resetUnreadCount(dto.id)
        assertEquals(0, store.fetchSession(dto.id)?.unreadCount)
    }

    @Test
    fun `deleteSession cascades to room messages`() = runTest {
        val dto = testSession()
        store.saveSession(dto)
        val messageStore = RoomMessageStore(database)
        messageStore.saveMessage(RoomMessageDto(sessionID = dto.id, authorKeyPrefix = ByteArray(4), text = "hi", timestamp = 1u))

        store.deleteSession(dto.id)

        assertTrue(messageStore.fetchMessages(dto.id).isEmpty())
    }

    @Test
    fun `cleanupDuplicateSessions cascades to the deleted duplicate's room messages`() = runTest {
        val publicKey = ByteArray(32) { 0x07 }
        val keep = testSession(publicKey = publicKey)
        val duplicate = testSession(publicKey = publicKey)
        store.saveSession(keep)
        store.saveSession(duplicate)
        val messageStore = RoomMessageStore(database)
        messageStore.saveMessage(RoomMessageDto(sessionID = duplicate.id, authorKeyPrefix = ByteArray(4), text = "hi", timestamp = 1u))

        store.cleanupDuplicateSessions(publicKey, keepID = keep.id)

        assertTrue(messageStore.fetchMessages(duplicate.id).isEmpty())
    }

    @Test
    fun `batchInsertRemoteNodeSessions inserts a new session as disconnected`() = runTest {
        val backup = testSession(publicKey = byteArrayOf(1, 1, 1), isConnected = true)

        val result = store.batchInsertRemoteNodeSessions(listOf(backup), setOf(radioID))

        assertEquals(1, result.counts.inserted)
        assertEquals(backup.id, result.sessionIdsByKey[BackupDedupKeys.remoteNodeSessionKey(radioID, backup.publicKey)])
        assertFalse(store.fetchSession(backup.id)!!.isConnected)
    }

    @Test
    fun `batchInsertRemoteNodeSessions merges into an existing session by (radioID, publicKey)`() = runTest {
        val publicKey = byteArrayOf(2, 2, 2)
        store.saveSession(testSession(publicKey = publicKey))
        val local = store.fetchSession(radioID, publicKey)!!
        val backup = testSession(publicKey = publicKey).copy(unreadCount = 9)

        val result = store.batchInsertRemoteNodeSessions(listOf(backup), setOf(radioID))

        assertEquals(0, result.counts.inserted)
        assertEquals(1, result.counts.merged)
        assertEquals(local.id, result.sessionIdsByKey[BackupDedupKeys.remoteNodeSessionKey(radioID, publicKey)])
        assertEquals(9, store.fetchSession(local.id)?.unreadCount)
    }

    @Test
    fun `applyLastMessageDatesToRemoteNodeSessions advances lastMessageDate only when newer`() = runTest {
        val session = testSession(publicKey = byteArrayOf(8, 8, 8))
        store.saveSession(session)
        val newer = Instant.ofEpochSecond(2000)
        database.remoteNodeSessionDao().fetchById(session.id)?.let { database.remoteNodeSessionDao().update(it.copy(lastMessageDate = newer)) }

        store.applyLastMessageDatesToRemoteNodeSessions(mapOf(session.id to Instant.ofEpochSecond(1000)))
        assertEquals("an already-newer local date must not regress", newer, store.fetchSession(session.id)?.lastMessageDate)

        store.applyLastMessageDatesToRemoteNodeSessions(mapOf(session.id to Instant.ofEpochSecond(3000)))
        assertEquals(Instant.ofEpochSecond(3000), store.fetchSession(session.id)?.lastMessageDate)
    }
}
