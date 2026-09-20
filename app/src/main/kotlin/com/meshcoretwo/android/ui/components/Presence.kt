// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import java.time.Duration
import java.time.Instant

/** Presence tier derived from a contact's last-heard instant, see [presenceLevel]. */
enum class PresenceLevel { ACTIVE, RECENT, NONE }

private val ActiveWindow: Duration = Duration.ofMinutes(5)
private val RecentWindow: Duration = Duration.ofHours(1)

/**
 * Presence dot tier for [lastHeard]: [PresenceLevel.ACTIVE] inside 5 minutes, [PresenceLevel.RECENT]
 * inside 1 hour, otherwise [PresenceLevel.NONE] (hidden). New UI from the "MeshCore Two Redesign"
 * mockup ("presence over timestamps") — a pure function of an existing `ContactDto.lastHeardTimestamp`
 * reading (via [com.meshcoretwo.android.contacts.toInstantOrNull]), not a new backend field, and
 * unit-testable without Compose. See [PresenceAvatar] for the composable that reads it.
 */
fun presenceLevel(lastHeard: Instant?, now: Instant = Instant.now()): PresenceLevel {
    if (lastHeard == null || lastHeard.isAfter(now)) return PresenceLevel.NONE
    val elapsed = Duration.between(lastHeard, now)
    return when {
        elapsed <= ActiveWindow -> PresenceLevel.ACTIVE
        elapsed <= RecentWindow -> PresenceLevel.RECENT
        else -> PresenceLevel.NONE
    }
}
