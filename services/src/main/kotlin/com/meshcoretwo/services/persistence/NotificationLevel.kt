// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

/**
 * Notification level for conversations (channels, rooms, contacts). Ported from
 * `NotificationLevel.swift`, dropping `iconName` (SF Symbol name — an iOS UI concern with no
 * Android equivalent; the `app` module will reference its own icon resources when it gets a
 * settings screen) and `displayName`/`accessibilityDescription` (developer-facing English
 * labels only used as a pre-localization fallback in Swift's own UI layer).
 */
enum class NotificationLevel(val rawValue: Int) {
    MUTED(0),
    MENTIONS_ONLY(1),
    ALL(2),
    ;

    companion object {
        fun fromRawValue(value: Int): NotificationLevel? = entries.find { it.rawValue == value }

        /** Levels available for channels (which support mention tracking). */
        val CHANNEL_LEVELS = listOf(MUTED, MENTIONS_ONLY, ALL)

        /** Levels available for rooms (no mention tracking infrastructure). */
        val ROOM_LEVELS = listOf(MUTED, ALL)
    }
}
