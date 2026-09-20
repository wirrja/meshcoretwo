// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.notifications

import android.content.Context
import androidx.annotation.StringRes
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.AppLanguageManager
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.notifications.NotificationCategory
import com.meshcoretwo.services.notifications.NotificationStringProvider

/**
 * Android-resource-backed [NotificationStringProvider]. Ported from `NotificationStringProviderImpl.swift`.
 * Resolves each string at notify-time against the currently selected app language.
 */
class AppNotificationStrings(private val appContext: Context) : NotificationStringProvider {
    private fun str(@StringRes id: Int, vararg args: Any): String =
        AppLanguageManager.wrap(appContext).getString(id, *args)

    override fun discoveryNotificationTitle(type: ContactType): String = str(
        when (type) {
            ContactType.CHAT -> R.string.notif_discovered_contact
            ContactType.REPEATER -> R.string.notif_discovered_repeater
            ContactType.ROOM -> R.string.notif_discovered_room
        },
    )

    override val replyActionTitle: String get() = str(R.string.notif_reply)
    override val sendButtonTitle: String get() = str(R.string.notif_send)
    override val messagePlaceholder: String get() = str(R.string.notif_message_placeholder)
    override val markAsReadActionTitle: String get() = str(R.string.notif_mark_read)
    override val lowBatteryTitle: String get() = str(R.string.notif_low_battery_title)
    override fun lowBatteryBody(deviceName: String, percentage: Int): String =
        str(R.string.notif_low_battery_body, deviceName, percentage)
    override val quickReplyFailedTitle: String get() = str(R.string.notif_reply_failed_title)
    override fun quickReplyFailedBody(conversationName: String): String =
        str(R.string.notif_reply_failed_body, conversationName)
    override val unknownContactName: String get() = str(R.string.notif_unknown_contact)
    override fun defaultChannelName(index: Int): String = str(R.string.notif_default_channel, index)
    override fun reactionNotificationBody(emoji: String, messagePreview: String): String =
        str(R.string.notif_reaction_body, emoji, messagePreview)

    override fun channelName(category: NotificationCategory): String = str(
        when (category) {
            NotificationCategory.DIRECT_MESSAGE -> R.string.notif_channel_direct
            NotificationCategory.CHANNEL_MESSAGE -> R.string.notif_channel_channel
            NotificationCategory.ROOM_MESSAGE -> R.string.notif_channel_room
            NotificationCategory.REACTION -> R.string.notif_channel_reaction
            NotificationCategory.LOW_BATTERY -> R.string.notif_channel_low_battery
        },
    )
}
