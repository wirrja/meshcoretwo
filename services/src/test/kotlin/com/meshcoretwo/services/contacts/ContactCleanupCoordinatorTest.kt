// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.contacts

import android.content.Context
import androidx.room.Room
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.notifications.NotificationService
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.remotenode.FakeRemoteNodeSessionOps
import com.meshcoretwo.services.remotenode.RemoteNodeService
import com.meshcoretwo.services.security.KeychainService
import com.meshcoretwo.services.sync.SyncCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

/**
 * Exercises [ContactCleanupCoordinator] against real in-memory Room-backed stores (Robolectric)
 * plus a real [SyncCoordinator]/[NotificationService]/[RemoteNodeService], matching this
 * codebase's "real service, fake wire" pattern — only [RemoteNodeService]'s session boundary
 * ([FakeRemoteNodeSessionOps], reused from `RemoteNodeServiceTest`) is faked.
 */
@RunWith(RobolectricTestRunner::class)
class ContactCleanupCoordinatorTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var contactStore: ContactStore
    private lateinit var messageStore: MessageStore
    private lateinit var remoteNodeSessionStore: RemoteNodeSessionStore
    private lateinit var syncCoordinator: SyncCoordinator
    private lateinit var notificationService: NotificationService
    private lateinit var remoteNodeService: RemoteNodeService
    private lateinit var coordinator: ContactCleanupCoordinator
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        contactStore = ContactStore(database)
        messageStore = MessageStore(database)
        remoteNodeSessionStore = RemoteNodeSessionStore(database)
        syncCoordinator = SyncCoordinator()
        notificationService = NotificationService(RuntimeEnvironment.getApplication())
        remoteNodeService = RemoteNodeService(
            FakeRemoteNodeSessionOps(),
            remoteNodeSessionStore,
            contactStore,
            KeychainService(RuntimeEnvironment.getApplication().getSharedPreferences("cleanup-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)),
        )
        coordinator = ContactCleanupCoordinator(
            contactStore, messageStore, remoteNodeSessionStore, syncCoordinator, notificationService, remoteNodeService, radioID,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun saveContact(publicKey: ByteArray, name: String = "Alice") {
        val meshContact = MeshContact(
            id = publicKey.joinToString("") { "%02x".format(it) }, publicKey = publicKey, type = ContactType.CHAT,
            flags = ContactFlags.NONE, outPathLength = 0xFFu, outPath = ByteArray(0), advertisedName = name,
            lastAdvertisement = Instant.ofEpochSecond(900), latitude = 0.0, longitude = 0.0, lastModified = Instant.ofEpochSecond(1000),
        )
        contactStore.saveContact(radioID, meshContact)
    }

    private fun channelMessage(senderNodeName: String, channelIndex: UByte = 0u) = MessageDto(
        id = UUID.randomUUID(), radioID = radioID, contactID = null, channelIndex = channelIndex, text = "hi",
        timestamp = 0u, createdAt = Instant.now(), sortDate = Instant.now(), direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED, textType = TextType.PLAIN_TEXT, ackCode = null,
        pathLength = 0u, snr = null, pathNodes = null, senderKeyPrefix = null, senderNodeName = senderNodeName,
        isRead = false, replyToID = null, roundTripTime = null, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0,
        deduplicationKey = null, reactionSummary = null, senderTimestamp = null, routeType = null, heardRepeats = 0,
    )

    @Test
    fun `blocked reason deletes the sender's channel messages`() = runTest {
        val publicKey = ByteArray(32) { 1 }
        saveContact(publicKey, name = "Alice")
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        messageStore.saveMessage(channelMessage(senderNodeName = "Alice"))

        coordinator.handleCleanup(contact.id, ContactCleanupReason.BLOCKED, publicKey)

        assertEquals(0, messageStore.fetchMessages(radioID, 0u).size)
    }

    @Test
    fun `unblocked reason does not delete channel messages`() = runTest {
        val publicKey = ByteArray(32) { 3 }
        saveContact(publicKey, name = "Alice")
        val contact = contactStore.fetchContact(radioID, publicKey)!!
        messageStore.saveMessage(channelMessage(senderNodeName = "Alice"))

        coordinator.handleCleanup(contact.id, ContactCleanupReason.UNBLOCKED, publicKey)

        assertEquals(1, messageStore.fetchMessages(radioID, 0u).size)
    }

    @Test
    fun `deleted reason removes the associated remote node session`() = runTest {
        val publicKey = ByteArray(32) { 2 }
        remoteNodeSessionStore.saveSession(
            RemoteNodeSessionDto(
                id = UUID.randomUUID(), radioID = radioID, publicKey = publicKey, name = "Bob's Repeater",
                role = RemoteNodeRole.REPEATER,
            ),
        )

        coordinator.handleCleanup(UUID.randomUUID(), ContactCleanupReason.DELETED, publicKey)

        assertNull(remoteNodeSessionStore.fetchSession(radioID, publicKey))
    }

    @Test
    fun `deleted reason does not touch another radio's session for the same public key`() = runTest {
        val publicKey = ByteArray(32) { 4 }
        val otherRadioID = UUID.randomUUID()
        remoteNodeSessionStore.saveSession(
            RemoteNodeSessionDto(
                id = UUID.randomUUID(), radioID = otherRadioID, publicKey = publicKey, name = "Other Radio's Repeater",
                role = RemoteNodeRole.REPEATER,
            ),
        )

        coordinator.handleCleanup(UUID.randomUUID(), ContactCleanupReason.DELETED, publicKey)

        assertEquals(1, remoteNodeSessionStore.fetchSessions(otherRadioID).size)
    }

    @Test
    fun `blocked reason removes delivered notifications for the contact without throwing`() = runTest {
        // No notification permission under Robolectric by default, so this mainly verifies the
        // call doesn't throw; NotificationServiceTest covers the cancellation behavior itself.
        coordinator.handleCleanup(UUID.randomUUID(), ContactCleanupReason.BLOCKED, ByteArray(32))
    }

    @Test
    fun `handleCleanup for an unknown contact id does not throw`() = runTest {
        coordinator.handleCleanup(UUID.randomUUID(), ContactCleanupReason.DELETED, ByteArray(32))
    }
}
