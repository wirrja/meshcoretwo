// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.services.messages.MessageServiceConfig
import com.meshcoretwo.services.notifications.NotificationService
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.persistence.RoomMessageDto
import com.meshcoretwo.services.persistence.RoomMessageStore
import com.meshcoretwo.services.persistence.RoomPermissionLevel
import com.meshcoretwo.services.security.KeychainService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
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
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.util.UUID

/**
 * Exercises [RoomServerService] against real in-memory [RemoteNodeSessionStore]/[RoomMessageStore]/
 * [ContactStore] (Room, via Robolectric) and [FakeRemoteNodeSessionOps] — see
 * [RemoteNodeServiceTest]'s doc for why the same fake backs both the `RemoteAccessSessionOps` role
 * (passed directly to [RoomServerService]) and the `RemoteNodeSessionOps` role (backing the
 * [RemoteNodeService] it delegates session lifecycle to).
 *
 * No `RoomServerServiceTests.swift` exists to port — written from scratch, the same precedent
 * [RepeaterAdminServiceTest]/[RoomAdminServiceTest] established.
 *
 * [RoomServerService.postMessage]/[RoomServerService.retryMessage] finish their send on a real
 * `Dispatchers.Default` scope (not `runTest`'s virtual scheduler), so tests observe the outcome by
 * polling persisted state via [awaitUntil] rather than racing the background `Job` directly —
 * matching [RemoteNodeServiceTest]'s login-event tests.
 */
@RunWith(RobolectricTestRunner::class)
class RoomServerServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeRemoteNodeSessionOps
    private lateinit var sessionStore: RemoteNodeSessionStore
    private lateinit var messageStore: RoomMessageStore
    private lateinit var contactStore: ContactStore
    private lateinit var keychainService: KeychainService
    private lateinit var remoteNodeService: RemoteNodeService
    private lateinit var service: RoomServerService
    private val radioID = UUID.randomUUID()
    private val publicKey = ByteArray(32) { it.toByte() }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeRemoteNodeSessionOps()
        sessionStore = RemoteNodeSessionStore(database)
        messageStore = RoomMessageStore(database)
        contactStore = ContactStore(database)
        keychainService = KeychainService(
            RuntimeEnvironment.getApplication().getSharedPreferences("roomserver-test-${UUID.randomUUID()}", Context.MODE_PRIVATE),
        )
        remoteNodeService = RemoteNodeService(session, sessionStore, contactStore, keychainService)
        service = RoomServerService(session, remoteNodeService, sessionStore, messageStore, contactStore, radioID, MessageServiceConfig())
    }

    @After
    fun tearDown() = runTest {
        remoteNodeService.stopEventMonitoring()
        remoteNodeService.stopAllKeepAlives()
        database.close()
    }

    private suspend fun saveRoomContact(outPathLength: UByte = 0x00u) {
        contactStore.saveContact(
            radioID,
            MeshContact(
                id = publicKey.joinToString("") { "%02x".format(it) },
                publicKey = publicKey,
                type = ContactType.ROOM,
                flags = ContactFlags.NONE,
                outPathLength = outPathLength,
                outPath = ByteArray(0),
                advertisedName = "Room",
                lastAdvertisement = Instant.ofEpochSecond(900),
                latitude = 0.0,
                longitude = 0.0,
                lastModified = Instant.ofEpochSecond(1000),
            ),
        )
    }

    private fun sentInfo(suggestedTimeoutMs: UInt = 500u) =
        MessageSentInfo(route = 0u, expectedAck = byteArrayOf(0x01, 0x02, 0x03, 0x04), suggestedTimeoutMs = suggestedTimeoutMs)

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, intervalMs: Long = 10, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    // MARK: - joinRoom

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
    fun `joinRoom creates a session, logs in, and returns the updated session`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        session.sentInfoToReturn = sentInfo()
        session.autoCompleteLoginPrefix = publicKey.copyOfRange(0, 6)
        session.autoCompleteLoginPermissions = RoomPermissionLevel.READ_WRITE.rawValue
        remoteNodeService.startEventMonitoring()
        awaitEventSubscriber()

        val result = service.joinRoom(radioID, contact, password = "secret")

        assertTrue(result.isConnected)
        assertEquals(RoomPermissionLevel.READ_WRITE, result.permissionLevel)
        assertEquals(1, session.sendLoginInvocations.size)
    }

    @Test
    fun `joinRoom stores the password when rememberPassword is true`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        session.autoCompleteLoginPrefix = publicKey.copyOfRange(0, 6)
        remoteNodeService.startEventMonitoring()
        awaitEventSubscriber()

        service.joinRoom(radioID, contact, password = "secret", rememberPassword = true)

        assertEquals("secret", keychainService.retrievePassword(publicKey))
    }

    @Test
    fun `joinRoom does not store the password when rememberPassword is false`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        session.autoCompleteLoginPrefix = publicKey.copyOfRange(0, 6)
        remoteNodeService.startEventMonitoring()
        awaitEventSubscriber()

        service.joinRoom(radioID, contact, password = "secret", rememberPassword = false)

        assertNull(keychainService.retrievePassword(publicKey))
    }

    // MARK: - reconnectRoom

    @Test
    fun `reconnectRoom re-authenticates an existing room session`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        keychainService.storePassword("secret", publicKey)
        session.autoCompleteLoginPrefix = publicKey.copyOfRange(0, 6)
        remoteNodeService.startEventMonitoring()
        awaitEventSubscriber()

        val result = service.reconnectRoom(created.id)

        assertTrue(result.isConnected)
    }

    @Test
    fun `reconnectRoom throws SessionNotFound for an unknown session`() = runTest {
        try {
            service.reconnectRoom(UUID.randomUUID())
            fail("expected RemoteNodeError.SessionNotFound")
        } catch (error: RemoteNodeError.SessionNotFound) {
            // expected
        }
    }

    @Test
    fun `reconnectRoom throws InvalidResponse for a non-room session`() = runTest {
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
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)

        try {
            service.reconnectRoom(created.id)
            fail("expected RemoteNodeError.InvalidResponse")
        } catch (error: RemoteNodeError.InvalidResponse) {
            // expected
        }
    }

    // MARK: - leaveRoom

    @Test
    fun `leaveRoom logs out and removes the session`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)

        service.leaveRoom(created.id, publicKey)

        assertTrue(session.sendLogoutCalled)
        assertNull(sessionStore.fetchSession(created.id))
    }

    // MARK: - postMessage

    @Test
    fun `postMessage throws SessionNotFound for an unknown session`() = runTest {
        try {
            service.postMessage(UUID.randomUUID(), "hi")
            fail("expected RoomServerError.SessionNotFound")
        } catch (error: RoomServerError.SessionNotFound) {
            // expected
        }
    }

    @Test
    fun `postMessage throws PermissionDenied when the session cannot post`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.GUEST)

        try {
            service.postMessage(created.id, "hi")
            fail("expected RoomServerError.PermissionDenied")
        } catch (error: RoomServerError.PermissionDenied) {
            // expected
        }
    }

    @Test
    fun `postMessage saves and returns a PENDING message immediately`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
        session.sendMessageWithRetryResult = Result.success(null)
        // Park the background send: this test is about what postMessage returns and persists
        // *before* the send resolves, and an unparked send overtakes the assertions on a loaded machine.
        val gate = CompletableDeferred<Unit>()
        session.sendMessageWithRetryGate = gate

        val result = service.postMessage(created.id, "hello room")

        assertEquals(MessageStatus.PENDING, result.status)
        assertEquals("hello room", result.text)
        assertTrue(result.isFromSelf)
        // Not a full-DTO equality check: createdAt round-trips through the DB at epochSecond
        // precision (see Converters.kt / RoomMessageStoreTest), so the freshly-returned in-memory
        // DTO's Instant.now() won't equal the reloaded row's truncated value.
        val persisted = messageStore.fetchMessage(result.id)!!
        assertEquals(result.id, persisted.id)
        assertEquals(result.status, persisted.status)
        assertEquals(result.text, persisted.text)
        gate.complete(Unit)
    }

    @Test
    fun `postMessage updates status to DELIVERED after a successful background send`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
        session.sendMessageWithRetryResult = Result.success(sentInfo())

        val result = service.postMessage(created.id, "hello room")

        awaitUntil { messageStore.fetchMessage(result.id)?.status == MessageStatus.DELIVERED }
        val updated = messageStore.fetchMessage(result.id)!!
        assertEquals(1, session.sendMessageWithRetryInvocations.size)
        assertTrue(updated.ackCode != null)
    }

    @Test
    fun `postMessage updates status to SENT when retries are exhausted without an ack`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
        session.sendMessageWithRetryResult = Result.success(null)

        val result = service.postMessage(created.id, "hello room")

        awaitUntil { messageStore.fetchMessage(result.id)?.status == MessageStatus.SENT }
    }

    @Test
    fun `postMessage updates status to FAILED when the send throws`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
        session.sendMessageWithRetryResult = Result.failure(MeshCoreError.NotConnected)

        val result = service.postMessage(created.id, "hello room")

        awaitUntil { messageStore.fetchMessage(result.id)?.status == MessageStatus.FAILED }
    }

    // MARK: - retryMessage

    @Test
    fun `retryMessage throws when the message does not exist`() = runTest {
        try {
            service.retryMessage(UUID.randomUUID())
            fail("expected RoomServerError.SendFailed")
        } catch (error: RoomServerError.SendFailed) {
            // expected
        }
    }

    @Test
    fun `retryMessage throws when the message is not in failed state`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        val dto = RoomMessageDto(sessionID = created.id, authorKeyPrefix = ByteArray(4), text = "hi", timestamp = 1u, status = MessageStatus.DELIVERED)
        messageStore.saveMessage(dto)

        try {
            service.retryMessage(dto.id)
            fail("expected RoomServerError.SendFailed")
        } catch (error: RoomServerError.SendFailed) {
            // expected
        }
    }

    @Test
    fun `retryMessage resends and updates to DELIVERED on success`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        val dto = RoomMessageDto(sessionID = created.id, authorKeyPrefix = ByteArray(4), text = "hi", timestamp = 1u, status = MessageStatus.FAILED)
        messageStore.saveMessage(dto)
        session.sendMessageWithRetryResult = Result.success(sentInfo())

        val result = service.retryMessage(dto.id)

        assertEquals(MessageStatus.DELIVERED, result.status)
        assertEquals(1, result.retryAttempt)
        assertEquals(1, session.sendMessageWithRetryInvocations.size)
    }

    // MARK: - handleIncomingMessage

    @Test
    fun `handleIncomingMessage returns null for an unknown sender prefix`() = runTest {
        val result = service.handleIncomingMessage(ByteArray(6) { 0x11 }, timestamp = 100u, authorPrefix = ByteArray(4), text = "hi")

        assertNull(result)
    }

    @Test
    fun `handleIncomingMessage returns null for a non-room session`() = runTest {
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
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        remoteNodeService.createSession(radioID, contact)

        val result = service.handleIncomingMessage(publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = ByteArray(4), text = "hi")

        assertNull(result)
    }

    @Test
    fun `handleIncomingMessage returns null for a room session that belongs to another radio`() = runTest {
        sessionStore.saveSession(
            RemoteNodeSessionDto(
                id = UUID.randomUUID(), radioID = UUID.randomUUID(), publicKey = publicKey,
                name = "Other Radio's Room", role = RemoteNodeRole.ROOM_SERVER, isConnected = true,
            ),
        )

        val result = service.handleIncomingMessage(publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = ByteArray(4), text = "hi")

        assertNull(result)
    }

    @Test
    fun `handleIncomingMessage saves the message and increments unread count`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)

        val result = service.handleIncomingMessage(publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = ByteArray(4) { 0x02 }, text = "hi there")

        assertTrue(result != null)
        assertEquals("hi there", result?.text)
        assertFalse(result!!.isFromSelf)
        assertEquals(1, sessionStore.fetchSession(created.id)?.unreadCount)
    }

    @Test
    fun `handleIncomingMessage returns null for a duplicate message`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
        service.handleIncomingMessage(publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = ByteArray(4), text = "hi")

        val duplicate = service.handleIncomingMessage(publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = ByteArray(4), text = "hi")

        assertNull(duplicate)
        assertEquals(1, sessionStore.fetchSession(created.id)?.unreadCount)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `handleIncomingMessage recovers a disconnected session and emits ConnectionRecovered`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        // Freshly created sessions start disconnected.
        assertFalse(sessionStore.fetchSession(created.id)!!.isConnected)

        val events = mutableListOf<RoomServerEvent>()
        val collector = GlobalScope.launch(Dispatchers.Default) {
            service.events().collect { events.add(it) }
        }
        Thread.sleep(50)

        service.handleIncomingMessage(publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = ByteArray(4), text = "hi")

        awaitUntil { sessionStore.fetchSession(created.id)?.isConnected == true }
        awaitUntil { events.any { it is RoomServerEvent.ConnectionRecovered } }
        collector.cancel()
    }

    @Test
    fun `handleIncomingMessage does not increment unread count for a message from self`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
        val selfPrefix = ByteArray(4) { 0x09 }
        service.setSelfPublicKeyPrefix(selfPrefix)

        val result = service.handleIncomingMessage(publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = selfPrefix, text = "echo")

        assertTrue(result?.isFromSelf == true)
        assertEquals(0, sessionStore.fetchSession(created.id)?.unreadCount)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `handleIncomingMessage emits MessageReceived with the saved message`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)

        val events = mutableListOf<RoomServerEvent>()
        val collector = GlobalScope.launch(Dispatchers.Default) {
            service.events().collect { events.add(it) }
        }
        Thread.sleep(50)

        val saved = service.handleIncomingMessage(publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = ByteArray(4), text = "hi there")

        awaitUntil { events.any { it is RoomServerEvent.MessageReceived && it.message.id == saved?.id } }
        collector.cancel()
    }

    @Test
    fun `handleIncomingMessage posts a notification for a non-self message`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
        val notificationService = NotificationService(RuntimeEnvironment.getApplication()).apply { setup() }
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowManager = shadowOf(manager)
        val serviceWithNotifications = RoomServerService(
            session, remoteNodeService, sessionStore, messageStore, contactStore, radioID, MessageServiceConfig(), notificationService,
        )

        val saved = serviceWithNotifications.handleIncomingMessage(
            publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = ByteArray(4), text = "hi there",
        )

        val notification = shadowManager.getNotification(saved!!.id.toString(), 0)
        assertEquals(created.name, notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `handleIncomingMessage does not post a notification for a message from self`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
        val notificationService = NotificationService(RuntimeEnvironment.getApplication()).apply { setup() }
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowManager = shadowOf(manager)
        val serviceWithNotifications = RoomServerService(
            session, remoteNodeService, sessionStore, messageStore, contactStore, radioID, MessageServiceConfig(), notificationService,
        )
        val selfPrefix = ByteArray(4) { 0x09 }
        serviceWithNotifications.setSelfPublicKeyPrefix(selfPrefix)

        val saved = serviceWithNotifications.handleIncomingMessage(
            publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = selfPrefix, text = "echo",
        )

        assertNull(shadowManager.getNotification(saved!!.id.toString(), 0))
    }

    // MARK: - Message retrieval / session queries

    @Test
    fun `fetchMessages delegates to the message store`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        messageStore.saveMessage(RoomMessageDto(sessionID = created.id, authorKeyPrefix = ByteArray(4), text = "a", timestamp = 1u))
        messageStore.saveMessage(RoomMessageDto(sessionID = created.id, authorKeyPrefix = ByteArray(4), text = "b", timestamp = 2u))

        val result = service.fetchMessages(created.id)

        assertEquals(listOf("a", "b"), result.map { it.text })
    }

    @Test
    fun `markAsRead resets the unread count`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
        service.handleIncomingMessage(publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = ByteArray(4), text = "hi")
        assertEquals(1, sessionStore.fetchSession(created.id)?.unreadCount)

        service.markAsRead(created.id)

        assertEquals(0, sessionStore.fetchSession(created.id)?.unreadCount)
    }

    @Test
    fun `setFavorite persists the favorite flag`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        assertFalse(sessionStore.fetchSession(created.id)!!.isFavorite)

        service.setFavorite(created.id, true)

        assertTrue(sessionStore.fetchSession(created.id)!!.isFavorite)
    }

    @Test
    fun `setNotificationLevel persists the level`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)

        service.setNotificationLevel(created.id, NotificationLevel.MUTED)

        assertEquals(NotificationLevel.MUTED, sessionStore.fetchSession(created.id)!!.notificationLevel)
    }

    @Test
    fun `fetchRoomSessions returns only room sessions for the radio`() = runTest {
        saveRoomContact()
        val roomContact = contactStore.fetchContact(radioID, publicKey)!!
        val roomSession = remoteNodeService.createSession(radioID, roomContact)

        val repeaterKey = ByteArray(32) { (it + 1).toByte() }
        contactStore.saveContact(
            radioID,
            MeshContact(
                id = repeaterKey.joinToString("") { "%02x".format(it) },
                publicKey = repeaterKey,
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
        remoteNodeService.createSession(radioID, contactStore.fetchContact(radioID, repeaterKey)!!)

        assertEquals(listOf(roomSession.id), service.fetchRoomSessions(radioID).map { it.id })
    }

    @Test
    fun `observeRoomSessions reflects unread count changes without a separate fetch`() = runTest {
        saveRoomContact()
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        val created = remoteNodeService.createSession(radioID, contact)
        sessionStore.updateConnection(created.id, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)

        assertEquals(0, service.observeRoomSessions(radioID).first().first { it.id == created.id }.unreadCount)

        service.handleIncomingMessage(publicKey.copyOfRange(0, 6), timestamp = 100u, authorPrefix = ByteArray(4), text = "hi")

        assertEquals(1, service.observeRoomSessions(radioID).first().first { it.id == created.id }.unreadCount)
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
    fun `getConnectedSession does not return a connected session belonging to another radio`() = runTest {
        sessionStore.saveSession(
            RemoteNodeSessionDto(
                id = UUID.randomUUID(), radioID = UUID.randomUUID(), publicKey = publicKey,
                name = "Other Radio's Room", role = RemoteNodeRole.ROOM_SERVER, isConnected = true,
            ),
        )

        assertNull(service.getConnectedSession(publicKey.copyOfRange(0, 6)))
    }
}
