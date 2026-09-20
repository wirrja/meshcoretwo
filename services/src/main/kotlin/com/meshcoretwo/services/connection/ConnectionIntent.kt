// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Represents the user's connection intent, replacing three separate flags:
 * `shouldBeConnected`, `userExplicitlyDisconnected`, and `pendingForceFullSync`. Ported from
 * `ConnectionIntent.swift`.
 */
sealed class ConnectionIntent {
    /** No active connection intent (initial state). */
    object None : ConnectionIntent()

    /** User explicitly disconnected — suppress auto-reconnect and disconnected pill. */
    object UserDisconnected : ConnectionIntent()

    /** User wants to be connected. */
    data class WantsConnection(val forceFullSync: Boolean = false) : ConnectionIntent()

    /** Whether the user wants to be connected. */
    val wantsConnection: Boolean get() = this is WantsConnection

    /** Whether the user explicitly disconnected. */
    val isUserDisconnected: Boolean get() = this is UserDisconnected

    /**
     * Persists this state to [prefs]. Only [UserDisconnected] is persisted; other states are
     * transient.
     */
    fun persist(prefs: SharedPreferences) {
        when (this) {
            is UserDisconnected -> prefs.edit { putBoolean(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED, true) }
            is None, is WantsConnection -> prefs.edit { remove(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED) }
        }
    }

    companion object {
        /** Restores intent from [prefs] on launch. Returns [UserDisconnected] if persisted, otherwise [None]. */
        fun restored(prefs: SharedPreferences): ConnectionIntent =
            if (prefs.getBoolean(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED, false)) UserDisconnected else None
    }
}
