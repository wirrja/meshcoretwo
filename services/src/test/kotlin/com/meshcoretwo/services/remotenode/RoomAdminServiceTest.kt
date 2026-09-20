// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import android.content.Context
import androidx.room.Room
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.TelemetryResponse
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.persistence.RoomPermissionLevel
import com.meshcoretwo.services.security.KeychainService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Exercises [RoomAdminService] against a real in-memory [RemoteNodeSessionStore]/[ContactStore]
 * (Room, via Robolectric) and [FakeRemoteNodeSessionOps] — see [RepeaterAdminServiceTest]'s doc
 * for why the same fake backs both.
 */
@RunWith(RobolectricTestRunner::class)
class RoomAdminServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeRemoteNodeSessionOps
    private lateinit var sessionStore: RemoteNodeSessionStore
    private lateinit var contactStore: ContactStore
    private lateinit var remoteNodeService: RemoteNodeService
    private lateinit var service: RoomAdminService
    private val radioID = UUID.randomUUID()
    private val publicKey = ByteArray(32) { it.toByte() }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeRemoteNodeSessionOps()
        sessionStore = RemoteNodeSessionStore(database)
        contactStore = ContactStore(database)
        val keychainService = KeychainService(
            RuntimeEnvironment.getApplication().getSharedPreferences("roomadmin-test-${UUID.randomUUID()}", Context.MODE_PRIVATE),
        )
        remoteNodeService = RemoteNodeService(session, sessionStore, contactStore, keychainService)
        service = RoomAdminService(remoteNodeService, sessionStore)
    }

    @After
    fun tearDown() = runTest {
        remoteNodeService.stopEventMonitoring()
        remoteNodeService.stopAllKeepAlives()
        database.close()
    }

    private suspend fun saveRoomContact() {
        contactStore.saveContact(
            radioID,
            MeshContact(
                id = publicKey.joinToString("") { "%02x".format(it) },
                publicKey = publicKey,
                type = ContactType.ROOM,
                flags = ContactFlags.NONE,
                outPathLength = 0u,
                outPath = ByteArray(0),
                advertisedName = "Room",
                lastAdvertisement = Instant.ofEpochSecond(900),
                latitude = 0.0,
                longitude = 0.0,
                lastModified = Instant.ofEpochSecond(1000),
            ),
        )
    }

    @Test
    fun `fetchRoomAdminSessions returns only room sessions`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val roomSession = remoteNodeService.createSession(radioID, contact)

        assertEquals(listOf(roomSession.id), service.fetchRoomAdminSessions(radioID).map { it.id })
    }

    @Test
    fun `getConnectedSession returns null until connected, then the session`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)

        assertNull(service.getConnectedSession(publicKey.copyOfRange(0, 6)))

        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)

        assertEquals(created.id, service.getConnectedSession(publicKey.copyOfRange(0, 6))?.id)
    }

    @Test
    fun `requestTelemetry delegates to RemoteNodeService`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        session.telemetryResponse = TelemetryResponse(publicKeyPrefix = publicKey.copyOf(6), tag = null, rawData = byteArrayOf(7))

        val result = service.requestTelemetry(created.id)

        assertTrue(result.rawData.contentEquals(byteArrayOf(7)))
    }

    @Test
    fun `handlers are invoked only when registered`() = runTest {
        var invoked = false
        service.setTelemetryHandler { invoked = true }
        service.invokeTelemetryHandler(TelemetryResponse(publicKeyPrefix = ByteArray(0), tag = null, rawData = ByteArray(0)))
        assertTrue(invoked)

        service.clearStatusHandlers()
        invoked = false
        service.invokeTelemetryHandler(TelemetryResponse(publicKeyPrefix = ByteArray(0), tag = null, rawData = ByteArray(0)))
        assertFalse(invoked)
    }
}
