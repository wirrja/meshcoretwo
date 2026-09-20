// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

/** Notification action identifiers. Ported from `NotificationAction` (`NotificationService.swift`). */
enum class NotificationAction(val id: String) {
    REPLY("REPLY_ACTION"),
    MARK_READ("MARK_READ_ACTION"),
    DISMISS("DISMISS_ACTION"),
}
