// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import android.content.SharedPreferences
import androidx.core.content.edit
import com.meshcoretwo.protocol.decodeHex
import com.meshcoretwo.protocol.hexString
import java.util.UUID

/**
 * Per-radio LRU of recently added hop public keys, persisted to [SharedPreferences] — the Android
 * analogue of `UserDefaults.standard`, per this port's single-shared-prefs-file convention (see
 * `AppContainer`'s class doc). Most-recent first, capped at [limit]. Shared by the contact path
 * editor (once ported) and the trace path builder so both surfaces present the same "Recent"
 * section. Ported from `RecentHopsStore`.
 *
 * Stored as a single comma-joined hex string rather than [SharedPreferences.getStringSet] —
 * `Set` has no defined iteration order, which would silently scramble the "most-recent-first" list
 * Swift's `UserDefaults.stringArray` preserves.
 *
 * The owning view model holds the observable list; this type owns only load/persist and the LRU
 * transform, matching Swift's own split (SwiftUI observation stays on the view model).
 */
class RecentHopsStore(private val prefs: SharedPreferences) {
    /** Loads the persisted recents for [radioID], newest first. */
    fun load(radioID: UUID): List<ByteArray> {
        val stored = prefs.getString(keyFor(radioID), null) ?: return emptyList()
        return stored.split(",").filter { it.isNotEmpty() }.mapNotNull { it.decodeHex() }
    }

    /**
     * LRU-inserts [publicKey] into [current], persists for [radioID], and returns the new list.
     * Moves an existing key to the front rather than duplicating; trims to [limit].
     */
    fun record(publicKey: ByteArray, current: List<ByteArray>, radioID: UUID): List<ByteArray> {
        val updated = mutableListOf(publicKey)
        updated.addAll(current.filterNot { it.contentEquals(publicKey) })
        val trimmed = updated.take(LIMIT)
        prefs.edit { putString(keyFor(radioID), trimmed.joinToString(",") { it.hexString }) }
        return trimmed
    }

    private fun keyFor(radioID: UUID): String = "$KEY_PREFIX$radioID"

    companion object {
        /**
         * Frozen storage-key prefix, matching Swift's exact key string so recents persisted by
         * a future contact-path-editor port keep loading after the trace side adopts this store.
         */
        private const val KEY_PREFIX = "pathEdit.recentPublicKeys."
        const val LIMIT = 8
    }
}
