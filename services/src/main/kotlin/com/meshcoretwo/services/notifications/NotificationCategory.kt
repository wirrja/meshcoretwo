// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

/**
 * Notification category identifiers. Ported from `NotificationCategory` (`NotificationService.swift`).
 * On Android these double as [android.app.NotificationChannel] IDs — iOS categories only bundle a
 * set of actions, but Android channels also carry importance/sound and are the natural 1:1 target.
 *
 * IDs carry a `_V2` suffix: a channel's vibration/sound settings are frozen at first creation, and
 * the original channels (plain names, see [LEGACY_CHANNEL_IDS]) were created without vibration.
 */
enum class NotificationCategory(val channelId: String, val displayName: String) {
    DIRECT_MESSAGE("DIRECT_MESSAGE_V2", "Direct messages"),
    CHANNEL_MESSAGE("CHANNEL_MESSAGE_V2", "Channel messages"),
    ROOM_MESSAGE("ROOM_MESSAGE_V2", "Room messages"),
    REACTION("REACTION_V2", "Reactions"),
    LOW_BATTERY("LOW_BATTERY_V2", "Low battery"),
    ;

    companion object {
        /** Channels created before vibration was enabled; deleted on [NotificationService.setup]. */
        val LEGACY_CHANNEL_IDS = listOf("DIRECT_MESSAGE", "CHANNEL_MESSAGE", "ROOM_MESSAGE", "REACTION", "LOW_BATTERY")
    }
}
