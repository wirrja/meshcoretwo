// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.persistence.NotificationLevel
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manages local notifications: message notifications, quick-reply actions, and battery warnings.
 * Ported from `NotificationService.swift`. Local only — no Firebase Cloud Messaging, matching
 * the project's no-GMS constraint (all traffic arrives over BLE/WiFi from the radio, not a cloud
 * push service).
 *
 * **Not ported, with reasons:**
 * - **Badge count** (`badgeCount`/`getBadgeCount`/`updateBadgeCount`) — stock Android has no
 *   `UIApplication.applicationIconBadgeNumber` equivalent; see [NotificationPreferences]'s class
 *   doc.
 * - **`requestAuthorization()`** — unlike `UNUserNotificationCenter.requestAuthorization()`
 *   (callable from anywhere), Android's `POST_NOTIFICATIONS` runtime permission (API 33+) can only
 *   be requested from an `Activity`/`Fragment` via `ActivityResultContracts.RequestPermission` —
 *   that call belongs to the `app` module (Phase 5), not here. [isAuthorized] still reports live
 *   state via [NotificationManagerCompat.areNotificationsEnabled].
 * - **Wiring [NotificationActionHandler] to this class's `onQuickReply`/`onMarkAsRead`/...
 *   callback surface** — [NotificationActionHandler] itself is now ported (the multi-service
 *   transaction layer behind quick reply/mark-as-read), but installing it as the real
 *   implementation behind these nullable callbacks is a composition-root/`app`-layer concern
 *   (`AppState.configureNotificationHandlers` on iOS) that doesn't exist yet on this port.
 * - **`NotificationPreferencesStore`** — the `@Observable` two-way-binding settings-screen
 *   counterpart to [NotificationPreferences]; Phase 5 UI.
 *
 * **Android-specific behavior, not a 1:1 port:**
 * - Swift suppresses only the *presentation* of a notification for the active conversation
 *   (`UNUserNotificationCenterDelegate.willPresent` returns `[]`) while still recording it as
 *   delivered. Android has no equivalent per-notification "deliver silently" hook at post time, so
 *   this class skips posting entirely when the conversation is active — same net effect (no
 *   banner/sound while viewing the chat), simpler mechanism.
 * - iOS wires notification taps/actions back into `NotificationService` because
 *   `UNUserNotificationCenter.current().delegate` is inherently a single system-level pointer.
 *   Android instantiates a fresh [NotificationActionReceiver] per broadcast, so this class mirrors
 *   that singleton-delegate shape explicitly via [Companion.activeInstance] — deliberate, not an
 *   architecture regression from the DI-everywhere pattern used elsewhere in this port.
 */
class NotificationService(
    context: Context,
    private val preferencesProvider: () -> NotificationPreferences = { NotificationPreferences.load(context) },
) {
    // Store the application Context, not whatever Context was passed in (an Activity, e.g.):
    // this class is reachable from a static field (see Companion.activeInstance below), and a
    // static reference to anything holding an Activity Context is a classic Android leak.
    private val context: Context = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(context)
    private val systemNotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var stringProvider: NotificationStringProvider? = defaultStringProvider

    // MARK: - Action Callbacks (installed by the app layer once it exists; see class doc)

    var onQuickReply: (suspend (contactID: UUID, text: String) -> Unit)? = null
    var onChannelQuickReply: (suspend (radioID: UUID, channelIndex: UByte, text: String) -> Unit)? = null
    var onMarkAsRead: (suspend (contactID: UUID, messageID: UUID) -> Unit)? = null
    var onChannelMarkAsRead: (suspend (radioID: UUID, channelIndex: UByte, messageID: UUID) -> Unit)? = null
    var onRoomMarkAsRead: (suspend (sessionID: UUID, messageID: UUID) -> Unit)? = null
    var onNotificationTapped: (suspend (contactID: UUID) -> Unit)? = null
    var onChannelNotificationTapped: (suspend (radioID: UUID, channelIndex: UByte) -> Unit)? = null
    var onRoomNotificationTapped: (suspend (sessionID: UUID) -> Unit)? = null
    var onNewContactNotificationTapped: (suspend (contactID: UUID) -> Unit)? = null
    var onReactionNotificationTapped:
        (suspend (contactID: UUID?, channelIndex: UByte?, radioID: UUID?, messageID: UUID) -> Unit)? = null

    /** Whether message notifications are temporarily suppressed (during a sync window). */
    var isSuppressingNotifications: Boolean = false

    // MARK: - Active Conversation Tracking

    var activeContactID: UUID? = null
        private set
    var activeChannelIndex: UByte? = null
        private set
    var activeChannelRadioID: UUID? = null
        private set
    var activeRoomSessionID: UUID? = null
        private set

    /**
     * Atomically sets the active conversation, clearing every slot the caller does not pass.
     * Opening one conversation therefore clears the others in a single assignment, so a
     * notification is never suppressed for a conversation the user just left.
     */
    fun setActiveConversation(
        contactID: UUID? = null,
        channelIndex: UByte? = null,
        channelRadioID: UUID? = null,
        roomSessionID: UUID? = null,
    ) {
        activeContactID = contactID
        activeChannelIndex = channelIndex
        activeChannelRadioID = channelRadioID
        activeRoomSessionID = roomSessionID
    }

    // MARK: - Draft Message Storage

    private val pendingDrafts = mutableMapOf<String, String>()

    /** Saves a draft message for a contact when quick reply fails. In-memory only — lost on process death. */
    fun saveDraft(contactID: UUID, text: String) {
        pendingDrafts[contactID.toString()] = text
    }

    /** Retrieves and removes (consumes) a draft message for a contact, or `null` if none exists. */
    fun consumeDraft(contactID: UUID): String? = pendingDrafts.remove(contactID.toString())

    // MARK: - Authorization

    /** Whether notifications are currently authorized by the user/system. */
    val isAuthorized: Boolean get() = notificationManager.areNotificationsEnabled()

    // MARK: - Setup

    /** Registers notification channels and marks this instance as the active delegate for [NotificationActionReceiver]. */
    fun setup() {
        registerChannels()
        activeInstanceRef = this
    }

    private fun registerChannels() = registerChannels(context)

    // MARK: - Sending Notifications

    /** Posts a notification for a direct message. */
    fun postDirectMessageNotification(
        contactName: String,
        contactID: UUID,
        messageText: String,
        messageID: UUID,
        isMuted: Boolean = false,
    ) {
        if (isMuted || !isAuthorized) return
        val prefs = preferencesProvider()
        if (!prefs.contactMessagesEnabled || isSuppressingNotifications) return
        if (contactID == activeContactID) return

        val extras = Bundle().apply {
            putString(NotificationExtras.CONTACT_ID, contactID.toString())
            putString(NotificationExtras.MESSAGE_ID, messageID.toString())
            putString(NotificationExtras.TYPE, "directMessage")
        }
        val builder = baseBuilder(NotificationCategory.DIRECT_MESSAGE, prefs)
            .setContentTitle(contactName)
            .setContentText(messageText)
            .setGroup(contactID.toString())
            .addExtras(extras)
            .setContentIntent(tapPendingIntent(NotificationExtras.TYPE_TAP_DIRECT, extras, messageID.hashCode()))
            .addAction(replyAction(extras, messageID.hashCode()))
            .addAction(markReadAction(extras, messageID.hashCode()))

        notify(messageID.toString(), builder)
    }

    /** Posts a notification for a channel message. */
    fun postChannelMessageNotification(
        channelName: String,
        channelIndex: UByte,
        radioID: UUID,
        senderName: String?,
        messageText: String,
        messageID: UUID,
        notificationLevel: NotificationLevel,
        hasSelfMention: Boolean,
    ) {
        if (notificationLevel == NotificationLevel.MUTED) return
        if (notificationLevel == NotificationLevel.MENTIONS_ONLY && !hasSelfMention) return
        if (!isAuthorized) return
        val prefs = preferencesProvider()
        if (!prefs.channelMessagesEnabled || isSuppressingNotifications) return
        if (channelIndex == activeChannelIndex && radioID == activeChannelRadioID) return

        val body = if (senderName != null) "$senderName: $messageText" else messageText
        val extras = Bundle().apply {
            putInt(NotificationExtras.CHANNEL_INDEX, channelIndex.toInt())
            putString(NotificationExtras.RADIO_ID, radioID.toString())
            putString(NotificationExtras.MESSAGE_ID, messageID.toString())
            putString(NotificationExtras.TYPE, "channelMessage")
        }
        val builder = baseBuilder(NotificationCategory.CHANNEL_MESSAGE, prefs)
            .setContentTitle(channelName)
            .setContentText(body)
            .setGroup("channel-$radioID-$channelIndex")
            .addExtras(extras)
            .setContentIntent(tapPendingIntent(NotificationExtras.TYPE_TAP_CHANNEL, extras, messageID.hashCode()))
            .addAction(replyAction(extras, messageID.hashCode()))
            .addAction(markReadAction(extras, messageID.hashCode()))

        notify(messageID.toString(), builder)
    }

    /** Posts a notification for a room message. */
    fun postRoomMessageNotification(
        roomName: String,
        sessionID: UUID,
        senderName: String?,
        messageText: String,
        messageID: UUID,
        notificationLevel: NotificationLevel,
    ) {
        if (notificationLevel == NotificationLevel.MUTED) return
        if (!isAuthorized) return
        val prefs = preferencesProvider()
        if (!prefs.roomMessagesEnabled || isSuppressingNotifications) return
        if (sessionID == activeRoomSessionID) return

        val body = if (senderName != null) "$senderName: $messageText" else messageText
        val extras = Bundle().apply {
            putString(NotificationExtras.SESSION_ID, sessionID.toString())
            putString(NotificationExtras.MESSAGE_ID, messageID.toString())
            putString(NotificationExtras.TYPE, "roomMessage")
        }
        val builder = baseBuilder(NotificationCategory.ROOM_MESSAGE, prefs)
            .setContentTitle(roomName)
            .setContentText(body)
            .setGroup("room-$sessionID")
            .addExtras(extras)
            .setContentIntent(tapPendingIntent(NotificationExtras.TYPE_TAP_ROOM, extras, messageID.hashCode()))
            .addAction(markReadAction(extras, messageID.hashCode()))

        notify(messageID.toString(), builder)
    }

    /** Posts a notification that a new contact was discovered. */
    fun postNewContactNotification(contactName: String, contactID: UUID, contactType: ContactType) {
        if (!isAuthorized) return
        val prefs = preferencesProvider()
        if (!prefs.newContactDiscoveredEnabled) return
        val discoveryEnabled = when (contactType) {
            ContactType.CHAT -> prefs.discoveryContactEnabled
            ContactType.REPEATER -> prefs.discoveryRepeaterEnabled
            ContactType.ROOM -> prefs.discoveryRoomEnabled
        }
        if (!discoveryEnabled) return

        val title = stringProvider?.discoveryNotificationTitle(contactType) ?: defaultDiscoveryTitle(contactType)
        val body = contactName.ifEmpty { stringProvider?.unknownContactName ?: "Unknown Contact" }
        val extras = Bundle().apply {
            putString(NotificationExtras.CONTACT_ID, contactID.toString())
            putString(NotificationExtras.TYPE, "newContact")
        }
        val builder = NotificationCompat.Builder(context, NotificationCategory.DIRECT_MESSAGE.channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setGroup("discovery")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setSilent(!prefs.soundEnabled)
            .addExtras(extras)
            .setContentIntent(tapPendingIntent(NotificationExtras.TYPE_TAP_NEW_CONTACT, extras, contactID.hashCode()))

        notify("new-contact-$contactID", builder)
    }

    private fun defaultDiscoveryTitle(type: ContactType): String = when (type) {
        ContactType.CHAT -> "New Contact Discovered"
        ContactType.REPEATER -> "New Repeater Discovered"
        ContactType.ROOM -> "New Room Discovered"
    }

    /** Posts a notification when someone reacts to the user's message. */
    fun postReactionNotification(
        reactorName: String,
        body: String,
        messageID: UUID,
        contactID: UUID?,
        channelIndex: UByte?,
        radioID: UUID?,
    ) {
        if (!isAuthorized) return
        val prefs = preferencesProvider()
        if (!prefs.reactionNotificationsEnabled || isSuppressingNotifications) return

        val extras = Bundle().apply {
            putString(NotificationExtras.MESSAGE_ID, messageID.toString())
            putString(NotificationExtras.TYPE, "reaction")
            contactID?.let { putString(NotificationExtras.CONTACT_ID, it.toString()) }
            if (channelIndex != null && radioID != null) {
                putInt(NotificationExtras.CHANNEL_INDEX, channelIndex.toInt())
                putString(NotificationExtras.RADIO_ID, radioID.toString())
            }
        }
        val threadId = when {
            contactID != null -> "reaction-contact-$contactID"
            channelIndex != null && radioID != null -> "reaction-channel-$radioID-$channelIndex"
            else -> "reaction-$messageID"
        }
        val builder = baseBuilder(NotificationCategory.REACTION, prefs)
            .setContentTitle(reactorName)
            .setContentText(body)
            .setGroup(threadId)
            .addExtras(extras)
            .setContentIntent(tapPendingIntent(NotificationExtras.TYPE_TAP_REACTION, extras, "$messageID-$reactorName".hashCode()))

        // Unique tag per reaction to avoid replacing previous reaction notifications.
        notify("reaction-$messageID-$reactorName-${System.currentTimeMillis()}", builder)
    }

    /** Posts a low battery warning notification. */
    fun postLowBatteryNotification(deviceName: String, batteryPercentage: Int) {
        if (!isAuthorized) return
        val prefs = preferencesProvider()
        if (!prefs.lowBatteryEnabled) return

        val body = stringProvider?.lowBatteryBody(deviceName, batteryPercentage)
            ?: "$deviceName battery is at $batteryPercentage%"
        val builder = NotificationCompat.Builder(context, NotificationCategory.LOW_BATTERY.channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(stringProvider?.lowBatteryTitle ?: "Low Battery")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setSilent(!prefs.soundEnabled)

        // Device name as the tag to avoid duplicate notifications.
        notify("low-battery-$deviceName", builder)
    }

    /** Posts a notification that a quick reply failed to send. */
    fun postQuickReplyFailedNotification(contactName: String, contactID: UUID) {
        if (!isAuthorized) return
        val builder = NotificationCompat.Builder(context, NotificationCategory.DIRECT_MESSAGE.channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(stringProvider?.quickReplyFailedTitle ?: "Message Not Sent")
            .setContentText(
                stringProvider?.quickReplyFailedBody(contactName)
                    ?: "Your reply to $contactName couldn't be sent.",
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notify("quick-reply-failed-$contactID-${System.currentTimeMillis()}", builder)
    }

    /** Posts a notification that a channel quick reply failed to send. */
    fun postChannelQuickReplyFailedNotification(channelName: String, radioID: UUID, channelIndex: UByte) {
        if (!isAuthorized) return
        val builder = NotificationCompat.Builder(context, NotificationCategory.CHANNEL_MESSAGE.channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(stringProvider?.quickReplyFailedTitle ?: "Message Not Sent")
            .setContentText(
                stringProvider?.quickReplyFailedBody(channelName)
                    ?: "Your reply to $channelName couldn't be sent.",
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notify("channel-reply-failed-$radioID-$channelIndex-${System.currentTimeMillis()}", builder)
    }

    // MARK: - Removing Delivered Notifications

    /** Removes a delivered notification by message ID. */
    fun removeDeliveredNotification(messageID: UUID) {
        notificationManager.cancel(messageID.toString(), NOTIFICATION_ID)
    }

    /**
     * Removes all delivered notifications for a contact/channel/room. Three distinct names, not
     * three Swift-style overloads on `forX:` argument labels: all three would erase to the same
     * JVM signature (`removeDeliveredNotifications(UUID)` for the contact/room pair), which Kotlin
     * rejects as a conflict — argument labels aren't part of a JVM method signature.
     */
    fun removeDeliveredNotificationsForContact(contactID: UUID) {
        cancelActiveNotificationsWhere { extras -> extras.getString(NotificationExtras.CONTACT_ID) == contactID.toString() }
    }

    fun removeDeliveredNotificationsForChannel(channelIndex: UByte, radioID: UUID) {
        cancelActiveNotificationsWhere { extras ->
            extras.getInt(NotificationExtras.CHANNEL_INDEX, -1) == channelIndex.toInt() &&
                extras.getString(NotificationExtras.RADIO_ID) == radioID.toString()
        }
    }

    fun removeDeliveredNotificationsForRoom(sessionID: UUID) {
        cancelActiveNotificationsWhere { extras -> extras.getString(NotificationExtras.SESSION_ID) == sessionID.toString() }
    }

    private fun cancelActiveNotificationsWhere(predicate: (Bundle) -> Boolean) {
        for (active in systemNotificationManager.activeNotifications) {
            if (predicate(active.notification.extras)) {
                notificationManager.cancel(active.tag, active.id)
            }
        }
    }

    // MARK: - Building Blocks

    private fun baseBuilder(category: NotificationCategory, prefs: NotificationPreferences): NotificationCompat.Builder =
        NotificationCompat.Builder(context, category.channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setSilent(!prefs.soundEnabled)

    private fun notify(tag: String, builder: NotificationCompat.Builder) {
        // Every post* method already early-returns on `!isAuthorized`; this guard is
        // functionally redundant but keeps the permission check directly adjacent to the call
        // lint's MissingPermission dataflow analysis is looking for.
        if (!notificationManager.areNotificationsEnabled()) return
        notificationManager.notify(tag, NOTIFICATION_ID, builder.build())
    }

    private fun replyAction(extras: Bundle, requestCode: Int): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationExtras.REPLY_TEXT)
            .setLabel(stringProvider?.messagePlaceholder ?: "Message...")
            .build()
        val intent = actionIntent(NotificationAction.REPLY, extras)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            stringProvider?.replyActionTitle ?: "Reply",
            pendingIntent,
        ).addRemoteInput(remoteInput).build()
    }

    private fun markReadAction(extras: Bundle, requestCode: Int): NotificationCompat.Action {
        val intent = actionIntent(NotificationAction.MARK_READ, extras)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode + 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            stringProvider?.markAsReadActionTitle ?: "Mark as Read",
            pendingIntent,
        ).build()
    }

    /**
     * Tapping opens the app's launcher Activity carrying [extras] (the `MainActivity` turns them
     * into a route) — a broadcast here would leave the app closed, and some OEM shells refuse to
     * open such a notification at all.
     */
    private fun tapPendingIntent(tapType: String, extras: Bundle, requestCode: Int): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtras(extras)
            putExtra(NotificationExtras.TAP, tapType)
        }
        return PendingIntent.getActivity(
            context,
            requestCode + 2,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionIntent(action: NotificationAction, extras: Bundle): Intent =
        Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action.id
            putExtras(extras)
        }

    // MARK: - Action Dispatch (invoked by NotificationActionReceiver)

    /** Fire-and-forget entry point used by [NotificationActionReceiver]; see its class doc. */
    internal fun dispatchAction(intent: Intent, onComplete: () -> Unit = {}) {
        scope.launch {
            try {
                performAction(intent)
            } finally {
                onComplete()
            }
        }
    }

    /** Synchronous entry point for tests — runs the same dispatch without the fire-and-forget [scope]. */
    internal suspend fun performAction(intent: Intent) {
        when (intent.action) {
            NotificationAction.REPLY.id -> handleReply(intent)
            NotificationAction.MARK_READ.id -> handleMarkAsRead(intent)
            NotificationExtras.TYPE_TAP_DIRECT -> handleTapDirect(intent)
            NotificationExtras.TYPE_TAP_CHANNEL -> handleTapChannel(intent)
            NotificationExtras.TYPE_TAP_ROOM -> handleTapRoom(intent)
            NotificationExtras.TYPE_TAP_NEW_CONTACT -> handleTapNewContact(intent)
            NotificationExtras.TYPE_TAP_REACTION -> handleTapReaction(intent)
        }
    }

    private suspend fun handleReply(intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotificationExtras.REPLY_TEXT)?.toString()
            ?: return
        val contactID = intent.getStringExtra(NotificationExtras.CONTACT_ID)?.let(UUID::fromString)
        if (contactID != null) {
            onQuickReply?.invoke(contactID, text)
            return
        }
        val radioID = intent.getStringExtra(NotificationExtras.RADIO_ID)?.let(UUID::fromString)
        val channelIndex = intent.getIntExtra(NotificationExtras.CHANNEL_INDEX, -1)
        if (radioID != null && channelIndex >= 0) {
            onChannelQuickReply?.invoke(radioID, channelIndex.toUByte(), text)
        }
    }

    private suspend fun handleMarkAsRead(intent: Intent) {
        val messageID = intent.getStringExtra(NotificationExtras.MESSAGE_ID)?.let(UUID::fromString) ?: return
        val contactID = intent.getStringExtra(NotificationExtras.CONTACT_ID)?.let(UUID::fromString)
        if (contactID != null) {
            onMarkAsRead?.invoke(contactID, messageID)
            return
        }
        val sessionID = intent.getStringExtra(NotificationExtras.SESSION_ID)?.let(UUID::fromString)
        if (sessionID != null) {
            onRoomMarkAsRead?.invoke(sessionID, messageID)
            return
        }
        val radioID = intent.getStringExtra(NotificationExtras.RADIO_ID)?.let(UUID::fromString)
        val channelIndex = intent.getIntExtra(NotificationExtras.CHANNEL_INDEX, -1)
        if (radioID != null && channelIndex >= 0) {
            onChannelMarkAsRead?.invoke(radioID, channelIndex.toUByte(), messageID)
        }
    }

    private suspend fun handleTapDirect(intent: Intent) {
        val contactID = intent.getStringExtra(NotificationExtras.CONTACT_ID)?.let(UUID::fromString) ?: return
        onNotificationTapped?.invoke(contactID)
    }

    private suspend fun handleTapChannel(intent: Intent) {
        val radioID = intent.getStringExtra(NotificationExtras.RADIO_ID)?.let(UUID::fromString) ?: return
        val channelIndex = intent.getIntExtra(NotificationExtras.CHANNEL_INDEX, -1)
        if (channelIndex < 0) return
        onChannelNotificationTapped?.invoke(radioID, channelIndex.toUByte())
    }

    private suspend fun handleTapRoom(intent: Intent) {
        val sessionID = intent.getStringExtra(NotificationExtras.SESSION_ID)?.let(UUID::fromString) ?: return
        onRoomNotificationTapped?.invoke(sessionID)
    }

    private suspend fun handleTapNewContact(intent: Intent) {
        val contactID = intent.getStringExtra(NotificationExtras.CONTACT_ID)?.let(UUID::fromString) ?: return
        onNewContactNotificationTapped?.invoke(contactID)
    }

    private suspend fun handleTapReaction(intent: Intent) {
        val messageID = intent.getStringExtra(NotificationExtras.MESSAGE_ID)?.let(UUID::fromString) ?: return
        val contactID = intent.getStringExtra(NotificationExtras.CONTACT_ID)?.let(UUID::fromString)
        val radioID = intent.getStringExtra(NotificationExtras.RADIO_ID)?.let(UUID::fromString)
        val channelIndex = intent.getIntExtra(NotificationExtras.CHANNEL_INDEX, -1).takeIf { it >= 0 }?.toUByte()
        onReactionNotificationTapped?.invoke(contactID, channelIndex, radioID, messageID)
    }

    /**
     * Keys of the extras carried by every notification tap intent, public so the app's
     * `MainActivity` can turn a tap into a navigation route.
     */
    object NotificationExtras {
        const val TYPE = "type"
        const val CONTACT_ID = "contactID"
        const val CHANNEL_INDEX = "channelIndex"
        const val RADIO_ID = "radioID"
        const val MESSAGE_ID = "messageID"
        const val SESSION_ID = "sessionID"
        const val REPLY_TEXT = "reply_text"
        const val TAP = "notificationTap"

        const val TYPE_TAP_DIRECT = "com.meshcoretwo.android.notifications.TAP_DIRECT"
        const val TYPE_TAP_CHANNEL = "com.meshcoretwo.android.notifications.TAP_CHANNEL"
        const val TYPE_TAP_ROOM = "com.meshcoretwo.android.notifications.TAP_ROOM"
        const val TYPE_TAP_NEW_CONTACT = "com.meshcoretwo.android.notifications.TAP_NEW_CONTACT"
        const val TYPE_TAP_REACTION = "com.meshcoretwo.android.notifications.TAP_REACTION"
    }

    companion object {
        private const val NOTIFICATION_ID = 0

        /**
         * Provider handed to every new instance and used for channel names; set by the `app` layer
         * before [registerChannels] so `services` needs no Android string resources.
         */
        @Volatile
        var defaultStringProvider: NotificationStringProvider? = null

        /**
         * Creates (idempotently) the notification channels. Also called from `Application.onCreate`
         * so the channels exist before the first radio connection builds a [NotificationService].
         */
        fun registerChannels(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channels = listOf(
                NotificationCategory.DIRECT_MESSAGE to NotificationManager.IMPORTANCE_HIGH,
                NotificationCategory.CHANNEL_MESSAGE to NotificationManager.IMPORTANCE_HIGH,
                NotificationCategory.ROOM_MESSAGE to NotificationManager.IMPORTANCE_HIGH,
                NotificationCategory.REACTION to NotificationManager.IMPORTANCE_DEFAULT,
                NotificationCategory.LOW_BATTERY to NotificationManager.IMPORTANCE_DEFAULT,
            )
            NotificationCategory.LEGACY_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
            for ((category, importance) in channels) {
                val channel = NotificationChannel(
                    category.channelId,
                    defaultStringProvider?.channelName(category) ?: category.displayName,
                    importance,
                ).apply {
                    enableVibration(true)
                }
                manager.createNotificationChannel(channel)
            }
        }

        /**
         * The most recently [setup]-called instance. [NotificationActionReceiver] is instantiated
         * fresh per broadcast by the OS and has no other way to reach a specific service instance;
         * see this class's doc for why that mirrors iOS's own single-delegate shape.
         *
         * Lint's `StaticFieldLeak` check flags this on principle (a static field of a class with a
         * `Context` field), but [context] is always the application [Context] (see the primary
         * constructor), which is itself already static-lifetime — nothing here can outlive it.
         */
        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var activeInstanceRef: NotificationService? = null

        internal val activeInstance: NotificationService? get() = activeInstanceRef
    }
}
