// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.persistence.NotificationLevel
import kotlinx.coroutines.test.runTest
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager
import java.util.UUID

/**
 * No `NotificationServiceTests.swift`-style 1:1 equivalent — Swift's tests exercise real
 * `UNUserNotificationCenter` semantics that don't map onto `NotificationCompat`/
 * `ShadowNotificationManager`; this is new coverage written against the port's own API surface,
 * same approach as `KeychainServiceTest`.
 *
 * A fresh preferences file per test isolates cases (mirrors `KeychainServiceTest`'s
 * per-instance prefs file); [NotificationPreferences] is injected directly via the
 * `preferencesProvider` constructor param rather than routed through `SharedPreferences`, since
 * only [NotificationPreferences]'s own load logic needs disk-backed coverage (see
 * [NotificationPreferencesTest]).
 */
@RunWith(RobolectricTestRunner::class)
class NotificationServiceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var shadowManager: ShadowNotificationManager
    private var prefs = allEnabledPreferences()

    @Before
    fun setUp() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowManager = shadowOf(manager)
        prefs = allEnabledPreferences()
    }

    private fun newService(): NotificationService =
        NotificationService(context, preferencesProvider = { prefs }).apply { setup() }

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

    // MARK: - Setup

    @Test
    fun `setup registers a channel per category`() {
        newService()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ids = manager.notificationChannels.map { it.id }.toSet()
        assertEquals(
            setOf("DIRECT_MESSAGE_V2", "CHANNEL_MESSAGE_V2", "ROOM_MESSAGE_V2", "REACTION_V2", "LOW_BATTERY_V2"),
            ids,
        )
    }

    // MARK: - Direct Message

    @Test
    fun `postDirectMessageNotification posts a notification with contact name and text`() {
        val service = newService()
        val contactID = UUID.randomUUID()
        val messageID = UUID.randomUUID()

        service.postDirectMessageNotification("Alice", contactID, "hello there", messageID)

        val notification = shadowManager.getNotification(messageID.toString(), 0)
        assertNotNull(notification)
        assertEquals("Alice", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("hello there", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(contactID.toString(), notification.extras.getString("contactID"))
    }

    @Test
    fun `postDirectMessageNotification is suppressed when muted`() {
        val service = newService()
        val messageID = UUID.randomUUID()
        service.postDirectMessageNotification("Alice", UUID.randomUUID(), "hi", messageID, isMuted = true)
        assertNull(shadowManager.getNotification(messageID.toString(), 0))
    }

    @Test
    fun `postDirectMessageNotification is suppressed when preference disabled`() {
        prefs = allEnabledPreferences().copy(contactMessagesEnabled = false)
        val service = newService()
        val messageID = UUID.randomUUID()
        service.postDirectMessageNotification("Alice", UUID.randomUUID(), "hi", messageID)
        assertNull(shadowManager.getNotification(messageID.toString(), 0))
    }

    @Test
    fun `postDirectMessageNotification is suppressed during a sync window`() {
        val service = newService()
        service.isSuppressingNotifications = true
        val messageID = UUID.randomUUID()
        service.postDirectMessageNotification("Alice", UUID.randomUUID(), "hi", messageID)
        assertNull(shadowManager.getNotification(messageID.toString(), 0))
    }

    @Test
    fun `postDirectMessageNotification is suppressed for the active conversation`() {
        val service = newService()
        val contactID = UUID.randomUUID()
        service.setActiveConversation(contactID = contactID)
        val messageID = UUID.randomUUID()
        service.postDirectMessageNotification("Alice", contactID, "hi", messageID)
        assertNull(shadowManager.getNotification(messageID.toString(), 0))
    }

    @Test
    fun `setActiveConversation clears the previous conversation type`() {
        val service = newService()
        val contactID = UUID.randomUUID()
        service.setActiveConversation(contactID = contactID)
        service.setActiveConversation(channelIndex = 3u, channelRadioID = UUID.randomUUID())

        val messageID = UUID.randomUUID()
        service.postDirectMessageNotification("Alice", contactID, "hi", messageID)
        assertNotNull(shadowManager.getNotification(messageID.toString(), 0))
    }

    // MARK: - Channel Message

    @Test
    fun `postChannelMessageNotification prefixes body with sender name`() {
        val service = newService()
        val radioID = UUID.randomUUID()
        val messageID = UUID.randomUUID()

        service.postChannelMessageNotification(
            channelName = "General",
            channelIndex = 2u,
            radioID = radioID,
            senderName = "Bob",
            messageText = "hey",
            messageID = messageID,
            notificationLevel = NotificationLevel.ALL,
            hasSelfMention = false,
        )

        val notification = shadowManager.getNotification(messageID.toString(), 0)
        assertEquals("Bob: hey", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `postChannelMessageNotification is suppressed when muted`() {
        val service = newService()
        val messageID = UUID.randomUUID()
        service.postChannelMessageNotification(
            "General", 2u, UUID.randomUUID(), "Bob", "hey", messageID,
            NotificationLevel.MUTED, hasSelfMention = false,
        )
        assertNull(shadowManager.getNotification(messageID.toString(), 0))
    }

    @Test
    fun `postChannelMessageNotification honors mentions-only without a mention`() {
        val service = newService()
        val messageID = UUID.randomUUID()
        service.postChannelMessageNotification(
            "General", 2u, UUID.randomUUID(), "Bob", "hey", messageID,
            NotificationLevel.MENTIONS_ONLY, hasSelfMention = false,
        )
        assertNull(shadowManager.getNotification(messageID.toString(), 0))
    }

    @Test
    fun `postChannelMessageNotification honors mentions-only with a mention`() {
        val service = newService()
        val messageID = UUID.randomUUID()
        service.postChannelMessageNotification(
            "General", 2u, UUID.randomUUID(), "Bob", "hey", messageID,
            NotificationLevel.MENTIONS_ONLY, hasSelfMention = true,
        )
        assertNotNull(shadowManager.getNotification(messageID.toString(), 0))
    }

    // MARK: - Room Message

    @Test
    fun `postRoomMessageNotification posts under the room name`() {
        val service = newService()
        val sessionID = UUID.randomUUID()
        val messageID = UUID.randomUUID()
        service.postRoomMessageNotification("Room X", sessionID, "Carl", "sup", messageID, NotificationLevel.ALL)

        val notification = shadowManager.getNotification(messageID.toString(), 0)
        assertEquals("Room X", notification.extras.getString(Notification.EXTRA_TITLE))
    }

    // MARK: - New Contact Discovery

    @Test
    fun `postNewContactNotification is suppressed for a disabled per-type preference`() {
        prefs = allEnabledPreferences().copy(discoveryRepeaterEnabled = false)
        val service = newService()
        val contactID = UUID.randomUUID()
        service.postNewContactNotification("Repeater One", contactID, ContactType.REPEATER)
        assertNull(shadowManager.getNotification("new-contact-$contactID", 0))
    }

    @Test
    fun `postNewContactNotification posts for an enabled type`() {
        val service = newService()
        val contactID = UUID.randomUUID()
        service.postNewContactNotification("Repeater One", contactID, ContactType.REPEATER)
        assertNotNull(shadowManager.getNotification("new-contact-$contactID", 0))
    }

    // MARK: - Reaction

    @Test
    fun `postReactionNotification is suppressed when preference disabled`() {
        prefs = allEnabledPreferences().copy(reactionNotificationsEnabled = false)
        val service = newService()
        service.postReactionNotification("Bob", "reacted", UUID.randomUUID(), UUID.randomUUID(), null, null)
        assertTrue(shadowManager.allNotifications.isEmpty())
    }

    // MARK: - Low Battery

    @Test
    fun `postLowBatteryNotification uses the device name as tag`() {
        val service = newService()
        service.postLowBatteryNotification("Node 1", 12)
        assertNotNull(shadowManager.getNotification("low-battery-Node 1", 0))
    }

    // MARK: - Removing Delivered Notifications

    @Test
    fun `removeDeliveredNotifications for contact cancels only that contact's notifications`() {
        val service = newService()
        val contactA = UUID.randomUUID()
        val contactB = UUID.randomUUID()
        val messageA = UUID.randomUUID()
        val messageB = UUID.randomUUID()
        service.postDirectMessageNotification("Alice", contactA, "hi", messageA)
        service.postDirectMessageNotification("Bob", contactB, "yo", messageB)

        service.removeDeliveredNotificationsForContact(contactA)

        assertNull(shadowManager.getNotification(messageA.toString(), 0))
        assertNotNull(shadowManager.getNotification(messageB.toString(), 0))
    }

    @Test
    fun `removeDeliveredNotification cancels by message id`() {
        val service = newService()
        val messageID = UUID.randomUUID()
        service.postDirectMessageNotification("Alice", UUID.randomUUID(), "hi", messageID)
        service.removeDeliveredNotification(messageID)
        assertNull(shadowManager.getNotification(messageID.toString(), 0))
    }

    // MARK: - Draft Storage

    @Test
    fun `saveDraft then consumeDraft round-trips and clears`() {
        val service = newService()
        val contactID = UUID.randomUUID()
        assertNull(service.consumeDraft(contactID))

        service.saveDraft(contactID, "unsent text")
        assertEquals("unsent text", service.consumeDraft(contactID))
        assertNull(service.consumeDraft(contactID))
    }

    // MARK: - Action Dispatch

    @Test
    fun `reply action invokes onQuickReply with the typed text`() = runTest {
        val service = newService()
        var received: Pair<UUID, String>? = null
        service.onQuickReply = { contactID, text -> received = contactID to text }

        val contactID = UUID.randomUUID()
        val messageID = UUID.randomUUID()
        service.postDirectMessageNotification("Alice", contactID, "hi", messageID)
        val notification = shadowManager.getNotification(messageID.toString(), 0)
        val replyAction = notification.actions.first { it.remoteInputs != null }

        val intent = Intent(NotificationAction.REPLY.id).putExtra("contactID", contactID.toString())
        // The framework overload: `replyAction.remoteInputs` (built by NotificationCompat, then
        // round-tripped through the real framework Notification) comes back as
        // `android.app.RemoteInput[]`, not the androidx.core compat type.
        android.app.RemoteInput.addResultsToIntent(replyAction.remoteInputs, intent, bundleWithReply("reply_text", "on my way"))

        service.performAction(intent)

        assertEquals(contactID, received?.first)
        assertEquals("on my way", received?.second)
    }

    @Test
    fun `mark-as-read action invokes onMarkAsRead`() = runTest {
        val service = newService()
        var received: Pair<UUID, UUID>? = null
        service.onMarkAsRead = { contactID, messageID -> received = contactID to messageID }

        val contactID = UUID.randomUUID()
        val messageID = UUID.randomUUID()
        val intent = Intent(NotificationAction.MARK_READ.id).apply {
            putExtra("contactID", contactID.toString())
            putExtra("messageID", messageID.toString())
        }
        service.performAction(intent)

        assertEquals(contactID, received?.first)
        assertEquals(messageID, received?.second)
    }

    @Test
    fun `tapping a channel notification invokes onChannelNotificationTapped`() = runTest {
        val service = newService()
        var received: Pair<UUID, UByte>? = null
        service.onChannelNotificationTapped = { radioID, channelIndex -> received = radioID to channelIndex }

        val radioID = UUID.randomUUID()
        val intent = Intent("com.meshcoretwo.android.notifications.TAP_CHANNEL").apply {
            putExtra("radioID", radioID.toString())
            putExtra("channelIndex", 5)
        }
        service.performAction(intent)

        assertEquals(radioID, received?.first)
        assertEquals(5.toUByte(), received?.second)
    }

    private fun bundleWithReply(key: String, text: String): android.os.Bundle =
        android.os.Bundle().apply { putCharSequence(key, text) }
}
