// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager
import java.time.Instant
import java.util.UUID

/**
 * Exercises [NotificationCoordinator] — the [com.meshcoretwo.services.messages.IncomingMessageNotifying]
 * implementation bridging [com.meshcoretwo.services.messages.IncomingMessageService] to real
 * unread counters ([ContactStore]/[ChannelStore]) and [NotificationService] — against a real
 * in-memory Room database plus a real [NotificationService] (Robolectric-backed, same approach as
 * [NotificationServiceTest]).
 */
@RunWith(RobolectricTestRunner::class)
class NotificationCoordinatorTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: MeshCoreDatabase
    private lateinit var contactStore: ContactStore
    private lateinit var channelStore: ChannelStore
    private lateinit var notificationService: NotificationService
    private lateinit var shadowManager: ShadowNotificationManager
    private lateinit var coordinator: NotificationCoordinator
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        contactStore = ContactStore(database)
        channelStore = ChannelStore(database)
        notificationService = NotificationService(context, preferencesProvider = { allEnabledPreferences() }).apply { setup() }
        shadowManager = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        coordinator = NotificationCoordinator(notificationService, contactStore, channelStore)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun allEnabledPreferences() = NotificationPreferences(
        contactMessagesEnabled = true,
        channelMessagesEnabled = true,
        roomMessagesEnabled = true,
        newContactDiscoveredEnabled = true,
        discoveryContactEnabled = true,
        discoveryRepeaterEnabled = true,
        discoveryRoomEnabled = true,
        reactionNotificationsEnabled = true,
        soundEnabled = true,
        lowBatteryEnabled = true,
    )

    private suspend fun addContact(name: String = "Alice", isBlocked: Boolean = false): UUID {
        val contact = MeshContact(
            id = "id",
            publicKey = ByteArray(32) { it.toByte() },
            type = ContactType.CHAT,
            flags = ContactFlags.NONE,
            outPathLength = 0xFFu,
            outPath = ByteArray(0),
            advertisedName = name,
            lastAdvertisement = Instant.now(),
            latitude = 0.0,
            longitude = 0.0,
            lastModified = Instant.now(),
        )
        val (id, _) = contactStore.saveContact(radioID, contact)
        if (isBlocked) contactStore.updateContactPreferences(id, nickname = null, isBlocked = true, isFavorite = false, unreadCount = 0)
        return id
    }

    private fun message(text: String = "hi") = com.meshcoretwo.services.persistence.MessageDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        contactID = null,
        channelIndex = null,
        text = text,
        timestamp = 0u,
        createdAt = Instant.now(),
        sortDate = Instant.now(),
        direction = com.meshcoretwo.services.persistence.MessageDirection.INCOMING,
        status = com.meshcoretwo.services.persistence.MessageStatus.DELIVERED,
        textType = com.meshcoretwo.protocol.TextType.PLAIN_TEXT,
        ackCode = null,
        pathLength = 0u,
        snr = null,
        pathNodes = null,
        senderKeyPrefix = null,
        senderNodeName = null,
        isRead = false,
        replyToID = null,
        roundTripTime = null,
        sendCount = 1,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        deduplicationKey = null,
        reactionSummary = null,
        senderTimestamp = null,
        routeType = null,
        heardRepeats = 0,
    )

    @Test
    fun `notifyDirectMessage increments unread and mention counters and posts a notification`() = runTest {
        val contactID = addContact()

        coordinator.notifyDirectMessage(message("hello"), contactStore.fetchContact(contactID), hasSelfMention = true)

        val after = contactStore.fetchContact(contactID)!!
        assertEquals(1, after.unreadCount)
        assertEquals(1, after.unreadMentionCount)
        assertTrue(shadowManager.allNotifications.isNotEmpty())
    }

    @Test
    fun `notifyDirectMessage does not increment the mention counter without a self-mention`() = runTest {
        val contactID = addContact()

        coordinator.notifyDirectMessage(message(), contactStore.fetchContact(contactID), hasSelfMention = false)

        assertEquals(0, contactStore.fetchContact(contactID)!!.unreadMentionCount)
    }

    @Test
    fun `notifyDirectMessage is a no-op for a blocked contact`() = runTest {
        val contactID = addContact(isBlocked = true)

        coordinator.notifyDirectMessage(message(), contactStore.fetchContact(contactID), hasSelfMention = true)

        assertEquals(0, contactStore.fetchContact(contactID)!!.unreadCount)
        assertTrue(shadowManager.allNotifications.isEmpty())
    }

    @Test
    fun `notifyDirectMessage is a no-op for an unresolved (null) contact`() = runTest {
        coordinator.notifyDirectMessage(message(), null, hasSelfMention = true)

        assertTrue(shadowManager.allNotifications.isEmpty())
    }

    @Test
    fun `notifyDirectMessage skips the unread increment but still notifies for the active conversation`() = runTest {
        val contactID = addContact()
        notificationService.setActiveConversation(contactID = contactID)

        coordinator.notifyDirectMessage(message(), contactStore.fetchContact(contactID), hasSelfMention = false)

        assertEquals("viewing the conversation must not bump unread", 0, contactStore.fetchContact(contactID)!!.unreadCount)
    }

    @Test
    fun `notifyChannelMessage increments unread and mention counters and posts a notification`() = runTest {
        val channelID = channelStore.saveChannel(radioID, ChannelInfo(1u, "General", ByteArray(16) { 0x01 }))
        val channel = channelStore.fetchChannelById(channelID)!!

        coordinator.notifyChannelMessage(message("hi all"), channel, 1u, senderNodeName = "Bob", hasSelfMention = true, radioID = radioID)

        val after = channelStore.fetchChannelById(channelID)!!
        assertEquals(1, after.unreadCount)
        assertEquals(1, after.unreadMentionCount)
        assertTrue(shadowManager.allNotifications.isNotEmpty())
    }

    @Test
    fun `notifyChannelMessage is a no-op for an unresolved (null) channel`() = runTest {
        coordinator.notifyChannelMessage(message(), null, 9u, senderNodeName = "Bob", hasSelfMention = true, radioID = radioID)

        assertTrue(shadowManager.allNotifications.isEmpty())
    }

    @Test
    fun `notifyChannelMessage skips the unread increment while the channel is the active conversation`() = runTest {
        val channelID = channelStore.saveChannel(radioID, ChannelInfo(2u, "Ops", ByteArray(16) { 0x02 }))
        val channel = channelStore.fetchChannelById(channelID)!!
        notificationService.setActiveConversation(channelIndex = 2u, channelRadioID = radioID)

        coordinator.notifyChannelMessage(message(), channel, 2u, senderNodeName = "Bob", hasSelfMention = false, radioID = radioID)

        assertEquals(0, channelStore.fetchChannelById(channelID)!!.unreadCount)
    }
}
