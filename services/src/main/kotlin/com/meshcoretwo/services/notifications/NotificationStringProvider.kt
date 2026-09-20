// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import com.meshcoretwo.protocol.ContactType

/**
 * Provides localized strings for notifications. Ported from `NotificationStringProvider.swift`.
 *
 * Lets the `app` layer inject localized strings into `services` without this module depending on
 * Android string resources directly. `NotificationStringProviderImpl.swift`'s concrete
 * localization-table implementation is `app`-layer/Phase 5 and not ported here; every
 * [com.meshcoretwo.services.notifications.NotificationService] call site falls back to a literal
 * English string when no provider is set, exactly mirroring Swift's `stringProvider?.x ?? "..."`
 * pattern.
 */
interface NotificationStringProvider {
    /** Notification title for a discovered contact of the given type (e.g. "New Repeater Discovered"). */
    fun discoveryNotificationTitle(type: ContactType): String

    /** Localized title for the "Reply" notification action. */
    val replyActionTitle: String

    /** Localized title for the "Send" button in the notification quick-reply text input. */
    val sendButtonTitle: String

    /** Localized placeholder for the notification quick-reply text input. */
    val messagePlaceholder: String

    /** Localized title for the "Mark as Read" notification action. */
    val markAsReadActionTitle: String

    /** Localized title for a low battery warning notification. */
    val lowBatteryTitle: String

    /** Localized body for a low battery warning notification. */
    fun lowBatteryBody(deviceName: String, percentage: Int): String

    /** Localized title for a failed quick-reply notification. */
    val quickReplyFailedTitle: String

    /** Localized body for a failed quick-reply notification. */
    fun quickReplyFailedBody(conversationName: String): String

    /** Localized fallback display name for a discovered contact with no advertised name. */
    val unknownContactName: String

    /** Localized fallback display name for a channel with no stored name. */
    fun defaultChannelName(index: Int): String

    /** Localized body for a reaction notification. */
    fun reactionNotificationBody(emoji: String, messagePreview: String): String

    /** Localized name of a notification channel (shown in system settings). */
    fun channelName(category: NotificationCategory): String = category.displayName
}
