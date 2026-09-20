// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * LRU of recently-used reaction emoji, persisted to [SharedPreferences] (this port's single shared
 * `prefs` file, see `AppContainer`'s doc) — most-recent first, capped at [LIMIT]. Same shape as
 * [com.meshcoretwo.android.pathediting.RecentHopsStore], its closest existing precedent.
 *
 * Deliberately merges the two independent "recent emoji" lists `RecentEmojisStore.swift` (the
 * quick-react row, 6 items, plain `UserDefaults.set([String])`) and `EmojiProvider.swift`'s
 * `AppStorage(.frequentEmojis)` (the full picker's "Frequently Used" section, 20 items,
 * JSON-encoded) keep separately — one merged list backs both this port's quick-react row and the
 * full [com.meshcoretwo.android.chat.emoji.EmojiCatalog.sections] "Frequently Used" section. A
 * conscious simplification, not an oversight: two lists tracking the same underlying behavior
 * (which emoji this person reaches for) with different caps/encodings isn't worth reproducing.
 */
class RecentEmojiStore(private val prefs: SharedPreferences) {
    /** Loads the persisted recents, newest first — [DEFAULTS] when nothing has been recorded yet. */
    fun load(): List<String> {
        val stored = prefs.getString(KEY, null) ?: return DEFAULTS
        val list = stored.split(",").filter { it.isNotEmpty() }
        return list.ifEmpty { DEFAULTS }
    }

    /**
     * LRU-inserts [emoji] into [current], persists, and returns the new list. Moves an existing
     * emoji to the front rather than duplicating; trims to [LIMIT].
     */
    fun recordUsage(emoji: String, current: List<String>): List<String> {
        val updated = mutableListOf(emoji)
        updated.addAll(current.filterNot { it == emoji })
        val trimmed = updated.take(LIMIT)
        prefs.edit { putString(KEY, trimmed.joinToString(",")) }
        return trimmed
    }

    companion object {
        private const val KEY = "chat.recentReactionEmojis"
        const val LIMIT = 6

        /** Matches `RecentEmojisStore.swift`'s default list. */
        val DEFAULTS = listOf("👍", "👎", "❤️", "😂", "😮", "😢")
    }
}
