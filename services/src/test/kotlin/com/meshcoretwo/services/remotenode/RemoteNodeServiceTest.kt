// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import android.content.Context
import androidx.room.Room
import com.meshcoretwo.protocol.ConnectionState
import com.meshcoretwo.protocol.ContactFetchResult
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactMessage
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.ErrorCode
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.LoginInfo
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageResult
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.NeighboursResponse
import com.meshcoretwo.protocol.OwnerInfoResponse
import com.meshcoretwo.protocol.RemoteNodeSessionOps
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.protocol.TelemetryResponse
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.persistence.RoomPermissionLevel
import com.meshcoretwo.services.security.KeychainService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
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
 * Exercises [RemoteNodeService] against real in-memory [RemoteNodeSessionStore]/[ContactStore]
 * (Room, via Robolectric), a real [KeychainService] (test constructor with plain
 * `SharedPreferences`, per [com.meshcoretwo.services.security.KeychainServiceTest]), and
 * [FakeRemoteNodeSessionOps], a hand-written test double for the narrow [RemoteNodeSessionOps]
 * role interface — the same pattern [com.meshcoretwo.services.contacts.ContactServiceTest] and
 * [com.meshcoretwo.services.diagnostics.BinaryProtocolServiceTest] established.
 *
 * Login-resolution tests lean on [FakeRemoteNodeSessionOps]'s "auto-complete" knob (emitting the
 * matching `loginSuccess`/`loginFailed` event as a side effect of `sendLogin`) rather than manually
 * racing a separate coroutine against the event stream: [RemoteNodeService.login]'s event listener
 * and its own retransmit loop both run on the service's real `Dispatchers.Default` scope (not
 * `runTest`'s virtual scheduler), so a `CompletableDeferred` resolved by a real background thread
 * is safe to `await()` directly from the test body — no `Thread.sleep`/polling needed there. Only
 * [service.startEventMonitoring] itself needs [awaitEventSubscriber] (subscribing to a zero-replay
 * `SharedFlow` takes a moment on a real dispatcher, and an event emitted before that is lost), and
 * the parked-login teardown test still needs [awaitUntil] because nothing ever resolves that
 * particular login.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteNodeServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeRemoteNodeSessionOps
    private lateinit var sessionStore: RemoteNodeSessionStore
    private lateinit var contactStore: ContactStore
    private lateinit var keychainService: KeychainService
    private lateinit var service: RemoteNodeService
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
        keychainService = KeychainService(
            RuntimeEnvironment.getApplication().getSharedPreferences("remotenode-test-${UUID.randomUUID()}", Context.MODE_PRIVATE),
        )
        service = RemoteNodeService(session, sessionStore, contactStore, keychainService)
    }

    @After
    fun tearDown() = runTest {
        service.stopEventMonitoring()
        service.stopAllKeepAlives()
        database.close()
    }

    private fun meshContact(
        publicKey: ByteArray = this.publicKey,
        type: ContactType = ContactType.REPEATER,
        typeRawValue: UByte? = null,
        outPathLength: UByte = 0x00u,
    ) = MeshContact(
        id = publicKey.joinToString("") { "%02x".format(it) },
        publicKey = publicKey,
        type = type,
        typeRawValue = typeRawValue ?: type.value,
        flags = ContactFlags.NONE,
        outPathLength = outPathLength,
        outPath = ByteArray(0),
        advertisedName = "Node",
        lastAdvertisement = Instant.ofEpochSecond(900),
        latitude = 0.0,
        longitude = 0.0,
        lastModified = Instant.ofEpochSecond(1000),
    )

    private suspend fun saveTestContact(
        publicKey: ByteArray = this.publicKey,
        type: ContactType = ContactType.REPEATER,
        outPathLength: UByte = 0x00u,
    ): ContactDto {
        contactStore.saveContact(radioID, meshContact(publicKey = publicKey, type = type, outPathLength = outPathLength))
        return contactStore.fetchContact(radioID, publicKey)!!
    }

    private fun sentInfo(suggestedTimeoutMs: UInt = 500u) =
        MessageSentInfo(route = 0u, expectedAck = byteArrayOf(0x01), suggestedTimeoutMs = suggestedTimeoutMs)

    /**
     * Waits until the monitor is actually collecting: the fake's event flow has no replay, so an event
     * emitted before the subscription exists is lost. A fixed sleep was enough on a fast machine but not
     * on a slow CI runner.
     */
    private suspend fun awaitEventSubscriber() = awaitUntil(timeoutMs = 10_000) { session.hasEventSubscriber }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, intervalMs: Long = 10, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    // MARK: - createSession

    @Test
    fun `createSession creates a new session for a repeater contact`() = runTest {
        val contact = saveTestContact(type = ContactType.REPEATER)
        val created = service.createSession(radioID, contact)
        assertEquals(RemoteNodeRole.REPEATER, created.role)
        assertEquals(radioID, created.radioID)
        assertFalse(created.isConnected)
    }

    @Test
    fun `createSession creates a new session for a room contact`() = runTest {
        val contact = saveTestContact(type = ContactType.ROOM)
        val created = service.createSession(radioID, contact)
        assertEquals(RemoteNodeRole.ROOM_SERVER, created.role)
    }

    @Test
    fun `createSession reuses an existing session for the same public key`() = runTest {
        val contact = saveTestContact()
        val first = service.createSession(radioID, contact)
        sessionStore.updateConnection(first.id, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)

        val second = service.createSession(radioID, contact)

        assertEquals(first.id, second.id)
        assertEquals(RoomPermissionLevel.ADMIN, second.permissionLevel)
    }

    @Test
    fun `createSession does not reuse another radio's session for the same public key`() = runTest {
        val otherRadioID = UUID.randomUUID()
        val otherRadiosSession = RemoteNodeSessionDto(
            id = UUID.randomUUID(), radioID = otherRadioID, publicKey = publicKey, name = "Other Radio's Node",
            role = RemoteNodeRole.REPEATER, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN,
        )
        sessionStore.saveSession(otherRadiosSession)
        val contact = saveTestContact()

        val created = service.createSession(radioID, contact)

        // A fresh session for this radio, not a hijack of the other radio's row: different id,
        // guest permission, disconnected — none of the other radio's live state carried over.
        assertFalse(created.id == otherRadiosSession.id)
        assertEquals(radioID, created.radioID)
        assertFalse(created.isConnected)
        assertEquals(RoomPermissionLevel.GUEST, created.permissionLevel)
        // And the other radio's session is untouched.
        assertTrue(sessionStore.fetchSession(otherRadiosSession.id)?.isConnected == true)
    }

    @Test
    fun `createSession throws InvalidResponse for a chat contact`() = runTest {
        val contact = saveTestContact(type = ContactType.CHAT)
        try {
            service.createSession(radioID, contact)
            fail("expected RemoteNodeError.InvalidResponse")
        } catch (error: RemoteNodeError.InvalidResponse) {
            // expected
        }
    }

    @Test
    fun `createSession throws LoginFailed for a malformed public key`() = runTest {
        val badContact = saveTestContact(publicKey = ByteArray(4))
        try {
            service.createSession(radioID, badContact)
            fail("expected RemoteNodeError.LoginFailed")
        } catch (error: RemoteNodeError.LoginFailed) {
            // expected
        }
    }

    // MARK: - Session / password management

    @Test
    fun `removeSession deletes the stored password and the session row`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        keychainService.storePassword("secret", publicKey)

        service.removeSession(created.id, publicKey)

        assertNull(sessionStore.fetchSession(created.id))
        assertFalse(keychainService.hasPassword(publicKey))
    }

    @Test
    fun `password round-trips through the keychain`() = runTest {
        val contact = saveTestContact()
        assertFalse(service.hasPassword(contact))

        service.storePassword("hunter2", publicKey)

        assertTrue(service.hasPassword(contact))
        assertEquals("hunter2", service.retrievePassword(contact))

        service.deletePassword(contact)

        assertFalse(service.hasPassword(contact))
        assertNull(service.retrievePassword(contact))
    }

    @Test
    fun `disconnect marks the session disconnected and emits a state-changed event`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.GUEST)

        val events = mutableListOf<RemoteNodeEvent>()
        val collectJob = launch(Dispatchers.Unconfined) { service.events().collect { events.add(it) } }

        service.disconnect(created.id)

        assertFalse(sessionStore.fetchSession(created.id)?.isConnected == true)
        assertTrue(events.any { it == RemoteNodeEvent.SessionStateChanged(created.id, false) })
        collectJob.cancel()
    }

    // MARK: - Login healing (sendLoginHealingIfNeeded)

    @Test
    fun `notFound on login pushes the local contact to the radio and retries`() = runTest {
        saveTestContact(outPathLength = 2u)
        session.setSendLoginResults(listOf(Result.failure(MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value)), Result.success(sentInfo())))

        service.sendLoginHealingIfNeeded(publicKey, radioID, "pw")

        assertEquals(1, session.addContactInvocations.size)
        assertEquals(2, session.sendLoginInvocations.size)
        val pushed = session.addContactInvocations.first()
        assertEquals(true, pushed.publicKey.contentEquals(publicKey))
        assertEquals(0xFFu.toUByte(), pushed.outPathLength)

        val healed = contactStore.fetchContact(radioID, publicKey)
        assertTrue(healed?.isFloodRouted == true)
    }

    @Test
    fun `healing preserves a contact type byte not modeled by ContactType`() = runTest {
        contactStore.saveContact(radioID, meshContact(typeRawValue = 0x7Fu, outPathLength = 2u))
        session.setSendLoginResults(listOf(Result.failure(MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value)), Result.success(sentInfo())))

        service.sendLoginHealingIfNeeded(publicKey, radioID, "pw")

        assertEquals(0x7Fu.toUByte(), session.addContactInvocations.first().typeRawValue)
    }

    @Test
    fun `a non-notFound login error skips healing and is not retried`() = runTest {
        saveTestContact(outPathLength = 2u)
        session.setSendLoginResults(listOf(Result.failure(MeshCoreError.DeviceError(ErrorCode.TABLE_FULL.value))))

        try {
            service.sendLoginHealingIfNeeded(publicKey, radioID, "pw")
            fail("expected MeshCoreError.DeviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(ErrorCode.TABLE_FULL.value, error.code)
        }
        assertTrue(session.addContactInvocations.isEmpty())
        assertEquals(1, session.sendLoginInvocations.size)
    }

    @Test
    fun `a second notFound after re-adding the contact surfaces the device error unhealed`() = runTest {
        saveTestContact(outPathLength = 2u)
        session.setSendLoginResults(
            listOf(
                Result.failure(MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value)),
                Result.failure(MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value)),
            ),
        )

        try {
            service.sendLoginHealingIfNeeded(publicKey, radioID, "pw")
            fail("expected MeshCoreError.DeviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(ErrorCode.NOT_FOUND.value, error.code)
        }
        assertEquals(1, session.addContactInvocations.size)
        assertEquals(2, session.sendLoginInvocations.size)
    }

    @Test
    fun `a full radio contact table surfaces radioContactsFull`() = runTest {
        saveTestContact(outPathLength = 2u)
        session.setSendLoginResults(listOf(Result.failure(MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value))))
        session.addContactError = MeshCoreError.DeviceError(ErrorCode.TABLE_FULL.value)

        try {
            service.sendLoginHealingIfNeeded(publicKey, radioID, "pw")
            fail("expected RemoteNodeError.RadioContactsFull")
        } catch (error: RemoteNodeError.RadioContactsFull) {
            // expected
        }
        assertEquals(1, session.addContactInvocations.size)
        assertEquals(1, session.sendLoginInvocations.size)
    }

    @Test
    fun `a contact absent from the local store cannot be healed`() = runTest {
        session.setSendLoginResults(listOf(Result.failure(MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value))))

        try {
            service.sendLoginHealingIfNeeded(publicKey, radioID, "pw")
            fail("expected RemoteNodeError.ContactNotFound")
        } catch (error: RemoteNodeError.ContactNotFound) {
            // expected
        }
        assertTrue(session.addContactInvocations.isEmpty())
    }

    @Test
    fun `login surfaces radioContactsFull unwrapped, not a generic session error`() = runTest {
        val contact = saveTestContact(outPathLength = 2u)
        val created = service.createSession(radioID, contact)
        session.setSendLoginResults(listOf(Result.failure(MeshCoreError.DeviceError(ErrorCode.NOT_FOUND.value))))
        session.addContactError = MeshCoreError.DeviceError(ErrorCode.TABLE_FULL.value)

        try {
            service.login(created.id, password = "pw")
            fail("expected RemoteNodeError.RadioContactsFull")
        } catch (error: RemoteNodeError.RadioContactsFull) {
            // expected
        }
        assertEquals(1, session.sendLoginInvocations.size)
        assertEquals(1, session.addContactInvocations.size)
    }

    // MARK: - Login via event

    @Test
    fun `login without an explicit password and none stored throws PasswordNotFound`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)

        try {
            service.login(created.id)
            fail("expected RemoteNodeError.PasswordNotFound")
        } catch (error: RemoteNodeError.PasswordNotFound) {
            // expected
        }
    }

    @Test
    fun `login resolves and updates session state when a matching loginSuccess event arrives`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        keychainService.storePassword("secret", publicKey)
        session.sentInfoToReturn = sentInfo()
        session.autoCompleteLoginPrefix = publicKey.copyOfRange(0, 6)
        session.autoCompleteLoginIsAdmin = true
        session.autoCompleteLoginPermissions = 0x02u

        service.startEventMonitoring()
        awaitEventSubscriber()

        val result = service.login(created.id)

        assertTrue(result.success)
        assertTrue(result.isAdmin)
        val updated = sessionStore.fetchSession(created.id)
        assertTrue(updated?.isConnected == true)
        assertEquals(RoomPermissionLevel.ADMIN, updated?.permissionLevel)
    }

    @Test
    fun `login result updates this radio's session, not another radio's stale session with the same key prefix`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        keychainService.storePassword("secret", publicKey)
        session.sentInfoToReturn = sentInfo()
        session.autoCompleteLoginPrefix = publicKey.copyOfRange(0, 6)
        session.autoCompleteLoginIsAdmin = true
        session.autoCompleteLoginPermissions = 0x02u

        // A stale session for the same public key, left over on another radio — e.g. after a
        // radio swap. The login result push carries only a 6-byte prefix, which cannot
        // disambiguate between the two; only the session id parked by login() can.
        val otherRadioID = UUID.randomUUID()
        val staleSession = RemoteNodeSessionDto(
            id = UUID.randomUUID(), radioID = otherRadioID, publicKey = publicKey, name = "Stale", role = RemoteNodeRole.REPEATER,
        )
        sessionStore.saveSession(staleSession)

        service.startEventMonitoring()
        awaitEventSubscriber()

        val result = service.login(created.id)

        assertTrue(result.success)
        assertTrue(sessionStore.fetchSession(created.id)?.isConnected == true)
        assertFalse(sessionStore.fetchSession(staleSession.id)?.isConnected == true)
    }

    @Test
    fun `login throws LoginFailed when a matching loginFailed event arrives`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        keychainService.storePassword("secret", publicKey)
        session.sentInfoToReturn = sentInfo()
        session.autoCompleteLoginPrefix = publicKey.copyOfRange(0, 6)
        session.autoCompleteLoginFail = true

        service.startEventMonitoring()
        awaitEventSubscriber()

        try {
            service.login(created.id)
            fail("expected RemoteNodeError.LoginFailed")
        } catch (error: RemoteNodeError.LoginFailed) {
            // expected
        }
        assertFalse(sessionStore.fetchSession(created.id)?.isConnected == true)
    }

    @Test
    fun `stopAllKeepAlives resumes a parked login`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        keychainService.storePassword("secret", publicKey)
        session.sendLoginHangs = true

        var thrown: Throwable? = null
        val job = launch(Dispatchers.Unconfined) {
            try {
                service.login(created.id)
            } catch (error: Throwable) {
                thrown = error
            }
        }

        awaitUntil { service.pendingLoginCount() == 1 }

        service.stopAllKeepAlives()

        awaitUntil { thrown != null }
        assertTrue(thrown is RemoteNodeError.Cancelled)
        assertEquals(0, service.pendingLoginCount())
        job.cancel()
    }

    // MARK: - Keep-alive

    @Test
    fun `sendKeepAlive throws FloodRouted for a flood-routed contact`() = runTest {
        val contact = saveTestContact(outPathLength = 0xFFu)
        val created = service.createSession(radioID, contact)

        try {
            service.sendKeepAlive(created.id)
            fail("expected RemoteNodeError.FloodRouted")
        } catch (error: RemoteNodeError.FloodRouted) {
            // expected
        }
        assertTrue(session.sendKeepAliveInvocations.isEmpty())
    }

    @Test
    fun `sendKeepAlive sends on a direct-routed contact`() = runTest {
        val contact = saveTestContact(outPathLength = 2u)
        val created = service.createSession(radioID, contact)

        service.sendKeepAlive(created.id)

        assertEquals(1, session.sendKeepAliveInvocations.size)
    }

    @Test
    fun `startSessionKeepAlive sends an immediate keep-alive`() = runTest {
        val contact = saveTestContact(outPathLength = 2u)
        val created = service.createSession(radioID, contact)

        service.startSessionKeepAlive(created.id, publicKey)

        awaitUntil { session.sendKeepAliveInvocations.isNotEmpty() }
        service.stopSessionKeepAlive(created.id)
    }

    @Test
    fun `a terminal keep-alive error disconnects on the very first tick`() = runTest {
        val contact = saveTestContact(outPathLength = 2u)
        val created = service.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.GUEST)

        val events = mutableListOf<RemoteNodeEvent>()
        val collectJob = launch(Dispatchers.Unconfined) { service.events().collect { events.add(it) } }

        // No contact exists for this public key, so the very first tick's `fetchContact` misses and
        // throws ContactNotFound — a terminal (DISCONNECT_NOW) error per KeepAliveRetryPolicy, so
        // this doesn't need to wait out the (real, 90s-default) inter-tick interval for a second
        // transient failure the way a MeshCoreError.Timeout scenario would.
        service.startSessionKeepAlive(created.id, ByteArray(32) { 0x77 })

        awaitUntil { sessionStore.fetchSession(created.id)?.isConnected == false }
        assertTrue(events.any { it == RemoteNodeEvent.SessionStateChanged(created.id, false) })

        service.stopSessionKeepAlive(created.id)
        collectJob.cancel()
    }

    // MARK: - History sync / logout

    @Test
    fun `requestHistorySync throws InvalidResponse for a repeater session`() = runTest {
        val contact = saveTestContact(type = ContactType.REPEATER, outPathLength = 2u)
        val created = service.createSession(radioID, contact)

        try {
            service.requestHistorySync(created.id)
            fail("expected RemoteNodeError.InvalidResponse")
        } catch (error: RemoteNodeError.InvalidResponse) {
            // expected
        }
    }

    @Test
    fun `requestHistorySync requests status for a direct-routed room session`() = runTest {
        val contact = saveTestContact(type = ContactType.ROOM, outPathLength = 2u)
        val created = service.createSession(radioID, contact)

        service.requestHistorySync(created.id)

        assertEquals(ContactType.ROOM, session.lastRequestStatusType)
    }

    @Test
    fun `logout sends the logout command and marks the session disconnected`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)

        service.logout(created.id)

        assertTrue(session.sendLogoutCalled)
        val updated = sessionStore.fetchSession(created.id)
        assertFalse(updated?.isConnected == true)
        assertEquals(RoomPermissionLevel.GUEST, updated?.permissionLevel)
    }

    // MARK: - BLE disconnection / reconnection

    @Test
    fun `handleBLEDisconnection marks every connected session disconnected`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.GUEST)

        val result = service.handleBLEDisconnection()

        assertEquals(setOf(created.id), result)
        assertFalse(sessionStore.fetchSession(created.id)?.isConnected == true)
    }

    @Test
    fun `handleBLEDisconnection returns empty when nothing is connected`() = runTest {
        assertTrue(service.handleBLEDisconnection().isEmpty())
    }

    @Test
    fun `handleBLEReconnection is a no-op for an empty set`() = runTest {
        service.handleBLEReconnection(emptySet())
        // No exception; nothing to assert beyond "didn't crash".
    }

    @Test
    fun `handleBLEReconnection re-authenticates a previously connected session`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.GUEST)
        keychainService.storePassword("secret", publicKey)
        session.sentInfoToReturn = sentInfo()
        session.autoCompleteLoginPrefix = publicKey.copyOfRange(0, 6)
        session.autoCompleteLoginIsAdmin = false
        session.autoCompleteLoginPermissions = RoomPermissionLevel.GUEST.rawValue

        service.startEventMonitoring()
        awaitEventSubscriber()

        service.handleBLEReconnection(setOf(created.id))

        assertTrue(sessionStore.fetchSession(created.id)?.isConnected == true)
    }

    @Test
    fun `handleBLEReconnection disconnects a session whose re-auth returned degraded permission`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        keychainService.storePassword("secret", publicKey)
        session.sentInfoToReturn = sentInfo()
        session.autoCompleteLoginPrefix = publicKey.copyOfRange(0, 6)
        session.autoCompleteLoginIsAdmin = false
        session.autoCompleteLoginPermissions = RoomPermissionLevel.GUEST.rawValue

        service.startEventMonitoring()
        awaitEventSubscriber()

        service.handleBLEReconnection(setOf(created.id))

        assertFalse(sessionStore.fetchSession(created.id)?.isConnected == true)
    }

    @Test
    fun `handleBLEReconnection disconnects a session whose re-auth login fails`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.GUEST)
        keychainService.storePassword("secret", publicKey)
        session.setSendLoginResults(listOf(Result.failure(MeshCoreError.Timeout)))

        service.handleBLEReconnection(setOf(created.id))

        assertFalse(sessionStore.fetchSession(created.id)?.isConnected == true)
    }

    // MARK: - Status / telemetry / owner info

    @Test
    fun `requestStatus returns the session's status response`() = runTest {
        val contact = saveTestContact(type = ContactType.ROOM)
        val created = service.createSession(radioID, contact)
        session.statusResponse = StatusResponse(
            publicKeyPrefix = publicKey.copyOf(6),
            battery = 4100,
            txQueueLength = 0,
            noiseFloor = -100,
            lastRSSI = -80,
            packetsReceived = 1u,
            packetsSent = 2u,
            airtime = 3u,
            uptime = 4u,
            sentFlood = 5u,
            sentDirect = 6u,
            receivedFlood = 7u,
            receivedDirect = 8u,
            fullEvents = 0,
            lastSNR = 5.0,
            directDuplicates = 0,
            floodDuplicates = 0,
            rxAirtime = 9u,
        )

        val result = service.requestStatus(created.id)

        assertEquals(4100, result.battery)
        assertEquals(ContactType.ROOM, session.lastRequestStatusType)
    }

    @Test
    fun `requestStatus maps a mesh timeout to RemoteNodeError-Timeout`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        session.requestStatusError = MeshCoreError.Timeout

        try {
            service.requestStatus(created.id)
            fail("expected RemoteNodeError.Timeout")
        } catch (error: RemoteNodeError.Timeout) {
            // expected
        }
    }

    @Test
    fun `requestStatus wraps a non-timeout session error`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        session.requestStatusError = MeshCoreError.NotConnected

        try {
            service.requestStatus(created.id)
            fail("expected RemoteNodeError.SessionError")
        } catch (error: RemoteNodeError.SessionError) {
            assertTrue(error.error is MeshCoreError.NotConnected)
        }
    }

    @Test
    fun `requestTelemetry returns the session's telemetry response`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        session.telemetryResponse = TelemetryResponse(publicKeyPrefix = publicKey.copyOf(6), tag = null, rawData = byteArrayOf(1))

        val result = service.requestTelemetry(created.id)

        assertTrue(result.rawData.contentEquals(byteArrayOf(1)))
    }

    @Test
    fun `requestOwnerInfo returns the session's owner-info response`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        session.ownerInfoResponse = OwnerInfoResponse(firmwareVersion = "1.11.0", nodeName = "Node", ownerInfo = "Owner")

        val result = service.requestOwnerInfo(created.id)

        assertEquals("Node", result.nodeName)
    }

    // MARK: - CLI commands

    /** Creates a session and promotes it to admin — `sendCLICommand`/`sendRawCLICommand` are admin-only. */
    private suspend fun makeAdminSession(): RemoteNodeSessionDto {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        return sessionStore.fetchSession(created.id)!!
    }

    private suspend fun yieldCLIReply(text: String) {
        session.emit(
            MeshEvent.ContactMessageReceived(
                ContactMessage(
                    senderPublicKeyPrefix = publicKey.copyOfRange(0, 6),
                    pathLength = 0u,
                    textType = TextType.CLI_DATA.value,
                    senderTimestamp = Instant.now(),
                    signature = null,
                    text = text,
                    snr = null,
                ),
            ),
        )
    }

    @Test
    fun `sendCLICommand throws PermissionDenied for a non-admin session`() = runTest {
        val contact = saveTestContact()
        val created = service.createSession(radioID, contact) // defaults to GUEST

        try {
            service.sendCLICommand(created.id, "get tx")
            fail("expected RemoteNodeError.PermissionDenied")
        } catch (error: RemoteNodeError.PermissionDenied) {
            // expected
        }
        assertTrue(session.sendCommandInvocations.isEmpty())
    }

    @Test
    fun `matching reply resolves the pending command`() = runTest {
        val created = makeAdminSession()
        service.startEventMonitoring()
        awaitEventSubscriber()

        var result: String? = null
        val job = launch(Dispatchers.Unconfined) { result = service.sendCLICommand(created.id, "get tx") }
        awaitUntil { session.sendCommandInvocations.isNotEmpty() }

        yieldCLIReply("> 22")

        awaitUntil { result != null }
        assertEquals("> 22", result)
        job.cancel()
    }

    @Test
    fun `a reply of the wrong shape is dropped and a later matching reply resolves it`() = runTest {
        val created = makeAdminSession()
        service.startEventMonitoring()
        awaitEventSubscriber()

        var result: String? = null
        val job = launch(Dispatchers.Unconfined) { result = service.sendCLICommand(created.id, "get tx") }
        awaitUntil { session.sendCommandInvocations.isNotEmpty() }

        // A stale "get radio" reply must be dropped, not adopted as 910 dBm.
        yieldCLIReply("> 910.525,62.500,7,7")
        yieldCLIReply("> 22")

        awaitUntil { result != null }
        assertEquals("> 22", result)
        job.cancel()
    }

    @Test
    fun `a mismatched reply is dropped and the command times out`() = runTest {
        val created = makeAdminSession()
        service.startEventMonitoring()
        awaitEventSubscriber()
        // A small suggestedTimeoutMs keeps cliTimeoutMs close to the requested 200ms per attempt —
        // no local contact exists, so a mesh timeout also triggers one path-reset retry (see
        // performWithDirectPathFloodRecovery's "missing contact counts as direct" rule); this must
        // fit within awaitUntil's default budget across both attempts.
        session.sentInfoToReturn = sentInfo(suggestedTimeoutMs = 50u)

        var thrown: Throwable? = null
        val job = launch(Dispatchers.Unconfined) {
            try {
                service.sendCLICommand(created.id, "get tx", timeoutMs = 200)
            } catch (error: Throwable) {
                thrown = error
            }
        }
        awaitUntil { session.sendCommandInvocations.isNotEmpty() }

        yieldCLIReply("> 910.525,62.500,7,7")

        awaitUntil { thrown != null }
        assertTrue(thrown is RemoteNodeError.Timeout)
        assertTrue(session.resetPathCalled)
        job.cancel()
    }

    @Test
    fun `a second command waits for the slot until the first resolves`() = runTest {
        val created = makeAdminSession()
        service.startEventMonitoring()
        awaitEventSubscriber()

        var firstResult: String? = null
        val firstJob = launch(Dispatchers.Unconfined) { firstResult = service.sendCLICommand(created.id, "get tx") }
        awaitUntil { session.sendCommandInvocations.size == 1 }

        var secondResult: String? = null
        val secondJob = launch(Dispatchers.Unconfined) { secondResult = service.sendCLICommand(created.id, "get radio") }

        // The second command must not reach the radio while the first is pending.
        Thread.sleep(100)
        assertEquals(1, session.sendCommandInvocations.size)

        yieldCLIReply("> 22")
        awaitUntil { firstResult != null }
        assertEquals("> 22", firstResult)

        awaitUntil { session.sendCommandInvocations.size == 2 }
        yieldCLIReply("> 915.000,250.0,10,5")
        awaitUntil { secondResult != null }
        assertEquals("> 915.000,250.0,10,5", secondResult)

        firstJob.cancel()
        secondJob.cancel()
    }

    @Test
    fun `sendRawCLICommand accepts a free-form reply`() = runTest {
        val created = makeAdminSession()
        service.startEventMonitoring()
        awaitEventSubscriber()

        var result: String? = null
        val job = launch(Dispatchers.Unconfined) { result = service.sendRawCLICommand(created.id, "region") }
        awaitUntil { session.sendCommandInvocations.isNotEmpty() }

        yieldCLIReply("US/CA^\n  local F")

        awaitUntil { result != null }
        assertEquals("US/CA^\n  local F", result)
        job.cancel()
    }

    @Test
    fun `the command is sent with a hex wire prefix ahead of the command text`() = runTest {
        val created = makeAdminSession()
        service.startEventMonitoring()
        awaitEventSubscriber()

        val job = launch(Dispatchers.Unconfined) {
            try {
                service.sendCLICommand(created.id, "get tx", timeoutMs = 300)
            } catch (error: Throwable) {
                // Timeout expected — this test only checks the outgoing wire format.
            }
        }
        awaitUntil { session.sendCommandInvocations.isNotEmpty() }

        val sent = session.sendCommandInvocations.first()
        val split = CLIResponse.splitEchoedPrefix(sent)
        assertEquals("get tx", split?.second)

        job.cancel()
    }

    @Test
    fun `a reply echoing the wire prefix resolves and is delivered stripped`() = runTest {
        val created = makeAdminSession()
        service.startEventMonitoring()
        awaitEventSubscriber()

        var result: String? = null
        val job = launch(Dispatchers.Unconfined) { result = service.sendCLICommand(created.id, "get tx") }
        awaitUntil { session.sendCommandInvocations.isNotEmpty() }

        val prefix = CLIResponse.splitEchoedPrefix(session.sendCommandInvocations.first())!!.first
        yieldCLIReply(prefix + "> 22")

        awaitUntil { result != null }
        assertEquals("> 22", result)
        job.cancel()
    }

    @Test
    fun `a prefixed echo is authoritative even when the reply shape looks wrong`() = runTest {
        val created = makeAdminSession()
        service.startEventMonitoring()
        awaitEventSubscriber()

        var result: String? = null
        val job = launch(Dispatchers.Unconfined) { result = service.sendCLICommand(created.id, "get tx") }
        awaitUntil { session.sendCommandInvocations.isNotEmpty() }

        // A CSV would fail get-tx shape validation, but the echoed prefix proves the reply answers
        // this command, so it must be delivered.
        val prefix = CLIResponse.splitEchoedPrefix(session.sendCommandInvocations.first())!!.first
        yieldCLIReply(prefix + "> 910.525,62.500,7,7")

        awaitUntil { result != null }
        assertEquals("> 910.525,62.500,7,7", result)
        job.cancel()
    }

    @Test
    fun `a reply echoing a foreign prefix is dropped even for raw commands`() = runTest {
        val created = makeAdminSession()
        service.startEventMonitoring()
        awaitEventSubscriber()

        var result: String? = null
        val job = launch(Dispatchers.Unconfined) { result = service.sendRawCLICommand(created.id, "region") }
        awaitUntil { session.sendCommandInvocations.isNotEmpty() }

        val prefix = CLIResponse.splitEchoedPrefix(session.sendCommandInvocations.first())!!.first
        val foreign = if (prefix == "A7|") "B8|" else "A7|"
        yieldCLIReply(foreign + "stale reply to an earlier command")
        yieldCLIReply(prefix + "US/CA^")

        awaitUntil { result != null }
        assertEquals("US/CA^", result)
        job.cancel()
    }

    @Test
    fun `clock sync is rewritten to time with the host epoch on the wire`() = runTest {
        val created = makeAdminSession()
        service.startEventMonitoring()
        awaitEventSubscriber()

        val before = Instant.now().epochSecond
        val job = launch(Dispatchers.Unconfined) { service.sendRawCLICommand(created.id, "clock sync") }
        awaitUntil { session.sendCommandInvocations.isNotEmpty() }
        val after = Instant.now().epochSecond

        val split = CLIResponse.splitEchoedPrefix(session.sendCommandInvocations.first())!!
        assertTrue(split.second.startsWith(RemoteCLICommandRewriter.TIME_COMMAND_PREFIX))
        val epoch = split.second.removePrefix(RemoteCLICommandRewriter.TIME_COMMAND_PREFIX).toLong()
        assertTrue(epoch in before..after)

        job.cancel()
    }

    @Test
    fun `reboot is fire-and-forget and does not reset the path on timeout`() = runTest {
        val created = makeAdminSession()
        service.startEventMonitoring()
        awaitEventSubscriber()
        session.sentInfoToReturn = sentInfo(suggestedTimeoutMs = 50u)

        var thrown: Throwable? = null
        val job = launch(Dispatchers.Unconfined) {
            try {
                service.sendCLICommand(created.id, "reboot", timeoutMs = 200)
            } catch (error: Throwable) {
                thrown = error
            }
        }

        awaitUntil { thrown != null }
        assertTrue(thrown is RemoteNodeError.Timeout)
        assertFalse(session.resetPathCalled)
        job.cancel()
    }
}

/** Hand-written [RemoteNodeSessionOps] test double — see the class doc above for the auto-complete design. */
/** Not `private` so [RepeaterAdminServiceTest]/[RoomAdminServiceTest]/[RoomServerServiceTest] can reuse it — a real session wraps one instance for every narrower role interface, and so does this fake. */
internal class FakeRemoteNodeSessionOps : RemoteNodeSessionOps {
    var sentInfoToReturn: MessageSentInfo = MessageSentInfo(route = 0u, expectedAck = byteArrayOf(0x00), suggestedTimeoutMs = 1000u)
    private val sendLoginResults = ArrayDeque<Result<MessageSentInfo>>()
    val sendLoginInvocations = mutableListOf<Pair<ByteArray, String>>()
    var sendLoginHangs = false

    /** When set, the *next* successful (or every, if the queue is empty) `sendLogin` also emits the matching login event. */
    var autoCompleteLoginPrefix: ByteArray? = null
    var autoCompleteLoginIsAdmin = false
    var autoCompleteLoginPermissions: UByte? = null
    var autoCompleteLoginFail = false

    fun setSendLoginResults(results: List<Result<MessageSentInfo>>) {
        sendLoginResults.clear()
        sendLoginResults.addAll(results)
    }

    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo {
        sendLoginInvocations.add(destination to password)
        if (sendLoginHangs) awaitCancellation()

        val result = if (sendLoginResults.isNotEmpty()) sendLoginResults.removeFirst() else Result.success(sentInfoToReturn)
        val value = result.getOrThrow()

        autoCompleteLoginPrefix?.let { prefix ->
            if (autoCompleteLoginFail) {
                eventsFlow.emit(MeshEvent.LoginFailed(prefix))
            } else {
                eventsFlow.emit(
                    MeshEvent.LoginSuccess(
                        LoginInfo(permissions = autoCompleteLoginPermissions ?: 0u, isAdmin = autoCompleteLoginIsAdmin, publicKeyPrefix = prefix),
                    ),
                )
            }
        }
        return value
    }

    var sendLogoutCalled = false
    override suspend fun sendLogout(destination: ByteArray) {
        sendLogoutCalled = true
    }

    val sendCommandInvocations = mutableListOf<String>()
    var sendCommandError: MeshCoreError? = null
    override suspend fun sendCommand(destination: ByteArray, command: String, timestamp: java.time.Instant): MessageSentInfo {
        sendCommandInvocations.add(command)
        sendCommandError?.let { throw it }
        return sentInfoToReturn
    }

    var sendKeepAliveError: MeshCoreError? = null
    val sendKeepAliveInvocations = mutableListOf<Pair<ByteArray, UInt>>()
    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: UInt): MessageSentInfo {
        sendKeepAliveInvocations.add(publicKey to syncSince)
        sendKeepAliveError?.let { throw it }
        return sentInfoToReturn
    }

    lateinit var ownerInfoResponse: OwnerInfoResponse
    var requestOwnerInfoError: MeshCoreError? = null
    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse {
        requestOwnerInfoError?.let { throw it }
        return ownerInfoResponse
    }

    var statusResponse: StatusResponse = StatusResponse(
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
    )
    var requestStatusError: MeshCoreError? = null
    var lastRequestStatusType: ContactType? = null
    override suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse {
        lastRequestStatusType = type
        requestStatusError?.let { throw it }
        return statusResponse
    }

    lateinit var telemetryResponse: TelemetryResponse
    var requestTelemetryError: MeshCoreError? = null
    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse {
        requestTelemetryError?.let { throw it }
        return telemetryResponse
    }

    var neighboursResponse: NeighboursResponse = NeighboursResponse(publicKeyPrefix = ByteArray(6), tag = ByteArray(0), totalCount = 0, neighbours = emptyList())
    var requestNeighboursError: MeshCoreError? = null
    val requestNeighboursInvocations = mutableListOf<UShort>()
    override suspend fun requestNeighbours(publicKey: ByteArray, count: UByte, offset: UShort, orderBy: UByte, pubkeyPrefixLength: UByte): NeighboursResponse {
        requestNeighboursInvocations.add(offset)
        requestNeighboursError?.let { throw it }
        return neighboursResponse
    }

    override suspend fun getMessage(timeout: Double?): MessageResult = MessageResult.NoMoreMessages

    /** `null` element means "all retries exhausted, no ACK" — matches the real method's contract. */
    var sendMessageWithRetryResult: Result<MessageSentInfo?> = Result.success(
        MessageSentInfo(route = 0u, expectedAck = byteArrayOf(1, 2, 3, 4), suggestedTimeoutMs = 1000u),
    )
    val sendMessageWithRetryInvocations = mutableListOf<Pair<ByteArray, String>>()

    /**
     * Holds the send until the test completes it. A caller that posts a message and then inspects the
     * freshly-saved row races its own background send otherwise: on a loaded machine the send finishes
     * first and the row is already past PENDING by the time the assertion reads it.
     */
    var sendMessageWithRetryGate: CompletableDeferred<Unit>? = null
    override suspend fun sendMessageWithRetry(
        destination: ByteArray,
        text: String,
        timestamp: java.time.Instant,
        maxAttempts: Int,
        floodAfter: Int,
        maxFloodAttempts: Int,
        timeout: Double?,
    ): MessageSentInfo? {
        sendMessageWithRetryInvocations.add(destination to text)
        sendMessageWithRetryGate?.await()
        return sendMessageWithRetryResult.getOrThrow()
    }

    var sendPathDiscoveryResult: Result<MessageSentInfo> = Result.success(
        MessageSentInfo(route = 0u, expectedAck = byteArrayOf(1, 2, 3, 4), suggestedTimeoutMs = 1000u),
    )
    var sendPathDiscoveryCalled = false
    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo {
        sendPathDiscoveryCalled = true
        return sendPathDiscoveryResult.getOrThrow()
    }

    // MARK: - ContactSessionOps (only addContact is exercised — login healing)

    override suspend fun getContacts(since: java.time.Instant?): List<MeshContact> = error("not used by this vertical slice")

    override suspend fun getContactsReportingTotal(since: java.time.Instant?): ContactFetchResult = error("not used by this vertical slice")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = error("not used by this vertical slice")

    val addContactInvocations = mutableListOf<MeshContact>()
    var addContactError: MeshCoreError? = null
    override suspend fun addContact(contact: MeshContact) {
        addContactInvocations.add(contact)
        addContactError?.let { throw it }
    }

    override suspend fun removeContact(publicKey: ByteArray) = error("not used by this vertical slice")

    var resetPathCalled = false
    var resetPathError: MeshCoreError? = null
    override suspend fun resetPath(publicKey: ByteArray) {
        resetPathCalled = true
        resetPathError?.let { throw it }
    }

    override suspend fun shareContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun exportContact(publicKey: ByteArray?): String = error("not used by this vertical slice")

    override suspend fun importContact(cardData: ByteArray) = error("not used by this vertical slice")

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = error("not used by this vertical slice")

    // MARK: - SessionEventStreaming

    private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)

    suspend fun emit(event: MeshEvent) = eventsFlow.emit(event)

    val hasEventSubscriber: Boolean get() = eventsFlow.subscriptionCount.value > 0

    override val connectionState: Flow<ConnectionState> get() = error("not used by this vertical slice")

    override suspend fun events(): Flow<MeshEvent> = error("not used by this vertical slice")

    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = eventsFlow.filter { filter.matches(it) }

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = error("not used by this vertical slice")
}
