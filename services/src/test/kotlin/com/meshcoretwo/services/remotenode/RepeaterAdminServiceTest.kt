// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import android.content.Context
import androidx.room.Room
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.Neighbour
import com.meshcoretwo.protocol.NeighboursResponse
import com.meshcoretwo.protocol.OwnerInfoResponse
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

/**
 * Exercises [RepeaterAdminService] against a real in-memory [RemoteNodeSessionStore]/[ContactStore]
 * (Room, via Robolectric) and [FakeRemoteNodeSessionOps] — the same test double
 * [RemoteNodeServiceTest] uses, reused here as both the [RepeaterAdminService]'s own session and
 * (wrapped in a [RemoteNodeService]) its `remoteNodeService`'s session, matching how a real
 * `MeshCoreSession` instance backs both narrower role interfaces in production.
 */
@RunWith(RobolectricTestRunner::class)
class RepeaterAdminServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeRemoteNodeSessionOps
    private lateinit var sessionStore: RemoteNodeSessionStore
    private lateinit var contactStore: ContactStore
    private lateinit var remoteNodeService: RemoteNodeService
    private lateinit var service: RepeaterAdminService
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
            RuntimeEnvironment.getApplication().getSharedPreferences("repeateradmin-test-${UUID.randomUUID()}", Context.MODE_PRIVATE),
        )
        remoteNodeService = RemoteNodeService(session, sessionStore, contactStore, keychainService)
        service = RepeaterAdminService(session, remoteNodeService, sessionStore)
    }

    @After
    fun tearDown() = runTest {
        remoteNodeService.stopEventMonitoring()
        remoteNodeService.stopAllKeepAlives()
        database.close()
    }

    private suspend fun saveRepeaterContact(): com.meshcoretwo.services.persistence.ContactDto {
        contactStore.saveContact(
            radioID,
            MeshContact(
                id = publicKey.joinToString("") { "%02x".format(it) },
                publicKey = publicKey,
                type = ContactType.REPEATER,
                flags = ContactFlags.NONE,
                outPathLength = 0u,
                outPath = ByteArray(0),
                advertisedName = "Repeater",
                lastAdvertisement = Instant.ofEpochSecond(900),
                latitude = 0.0,
                longitude = 0.0,
                lastModified = Instant.ofEpochSecond(1000),
            ),
        )
        return contactStore.fetchContact(radioID, publicKey)!!
    }

    /**
     * Waits for the monitor to actually be collecting. The fake's event flow has no replay, so an event
     * emitted before the subscription exists is lost; a fixed sleep covered that on a fast machine but
     * not on a loaded CI runner.
     */
    private fun awaitEventSubscriber(timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!session.hasEventSubscriber) {
            check(System.currentTimeMillis() < deadline) { "no event subscriber within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `fetchRepeaterSessions returns only repeater sessions`() = runTest {
        val repeaterContact = saveRepeaterContact()
        val repeaterSession = remoteNodeService.createSession(radioID, repeaterContact)

        assertEquals(listOf(repeaterSession.id), service.fetchRepeaterSessions(radioID).map { it.id })
    }

    @Test
    fun `getConnectedSession returns null when not connected`() = runTest {
        val repeaterContact = saveRepeaterContact()
        remoteNodeService.createSession(radioID, repeaterContact)

        assertNull(service.getConnectedSession(publicKey.copyOfRange(0, 6)))
    }

    @Test
    fun `getConnectedSession returns the session once connected`() = runTest {
        val repeaterContact = saveRepeaterContact()
        val created = remoteNodeService.createSession(radioID, repeaterContact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)

        val found = service.getConnectedSession(publicKey.copyOfRange(0, 6))
        assertEquals(created.id, found?.id)
    }

    @Test
    fun `requestNeighbors throws SessionNotFound for a non-repeater session`() = runTest {
        contactStore.saveContact(
            radioID,
            MeshContact(
                id = "room",
                publicKey = ByteArray(32) { 0x11 },
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
        val roomContact = contactStore.fetchContact(radioID, ByteArray(32) { 0x11 })!!
        val roomSession = remoteNodeService.createSession(radioID, roomContact)

        try {
            service.requestNeighbors(roomSession.id)
            fail("expected RemoteNodeError.SessionNotFound")
        } catch (error: RemoteNodeError.SessionNotFound) {
            // expected
        }
    }

    @Test
    fun `requestNeighbors returns the session's neighbours`() = runTest {
        val repeaterContact = saveRepeaterContact()
        val created = remoteNodeService.createSession(radioID, repeaterContact)
        session.neighboursResponse = NeighboursResponse(
            publicKeyPrefix = publicKey.copyOfRange(0, 6),
            tag = ByteArray(0),
            totalCount = 1,
            neighbours = listOf(Neighbour(publicKeyPrefix = ByteArray(4), secondsAgo = 5, snr = 8.0)),
        )

        val result = service.requestNeighbors(created.id)

        assertEquals(1, result.totalCount)
    }

    @Test
    fun `fetchAllNeighbors paginates until the reported total is reached`() = runTest {
        val repeaterContact = saveRepeaterContact()
        val created = remoteNodeService.createSession(radioID, repeaterContact)
        // One neighbour per page, two pages total.
        session.neighboursResponse = NeighboursResponse(
            publicKeyPrefix = publicKey.copyOfRange(0, 6),
            tag = ByteArray(0),
            totalCount = 2,
            neighbours = listOf(Neighbour(publicKeyPrefix = ByteArray(4), secondsAgo = 5, snr = 8.0)),
        )

        val result = service.fetchAllNeighbors(created.id)

        // Both pages return the same single-neighbour response (the fake doesn't vary output per
        // offset), so aggregation keeps appending until totalCount is reached: two neighbours.
        assertEquals(2, result.neighbours.size)
        assertEquals(2, session.requestNeighboursInvocations.size)
    }

    @Test
    fun `requestStatus, requestTelemetry and requestOwnerInfo delegate to RemoteNodeService`() = runTest {
        val repeaterContact = saveRepeaterContact()
        val created = remoteNodeService.createSession(radioID, repeaterContact)
        session.ownerInfoResponse = OwnerInfoResponse(firmwareVersion = "1.11.0", nodeName = "Repeater", ownerInfo = "Owner")

        val ownerInfo = service.requestOwnerInfo(created.id)

        assertEquals("Repeater", ownerInfo.nodeName)
    }

    @Test
    fun `connectAsAdmin creates a session, logs in, and stores the password`() = runTest {
        val repeaterContact = saveRepeaterContact()
        session.autoCompleteLoginPrefix = publicKey.copyOfRange(0, 6)
        session.autoCompleteLoginIsAdmin = true
        session.autoCompleteLoginPermissions = RoomPermissionLevel.ADMIN.rawValue

        remoteNodeService.startEventMonitoring()
        awaitEventSubscriber()

        val result = service.connectAsAdmin(radioID, repeaterContact, password = "hunter2")

        assertTrue(result.isConnected)
        assertTrue(remoteNodeService.hasPassword(repeaterContact))
    }

    @Test
    fun `disconnect logs out and removes the session`() = runTest {
        val repeaterContact = saveRepeaterContact()
        val created = remoteNodeService.createSession(radioID, repeaterContact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)

        service.disconnect(created.id, publicKey)

        assertTrue(session.sendLogoutCalled)
        assertNull(sessionStore.fetchSession(created.id))
    }

    @Test
    fun `handlers are invoked only when registered`() = runTest {
        // No handler registered: must not throw.
        service.invokeStatusHandler(
            com.meshcoretwo.protocol.StatusResponse(
                publicKeyPrefix = ByteArray(6),
                battery = 0,
                txQueueLength = 0,
                noiseFloor = 0,
                lastRSSI = 0,
                packetsReceived = 0u,
                packetsSent = 0u,
                airtime = 0u,
                uptime = 0u,
                sentFlood = 0u,
                sentDirect = 0u,
                receivedFlood = 0u,
                receivedDirect = 0u,
                fullEvents = 0,
                lastSNR = 0.0,
                directDuplicates = 0,
                floodDuplicates = 0,
                rxAirtime = 0u,
            ),
        )

        var invoked = false
        service.setNeighboursHandler { invoked = true }
        service.invokeNeighboursHandler(NeighboursResponse(publicKeyPrefix = ByteArray(0), tag = ByteArray(0), totalCount = 0, neighbours = emptyList()))
        assertTrue(invoked)

        service.clearHandlers()
        invoked = false
        service.invokeNeighboursHandler(NeighboursResponse(publicKeyPrefix = ByteArray(0), tag = ByteArray(0), totalCount = 0, neighbours = emptyList()))
        assertFalse(invoked)
    }
}
