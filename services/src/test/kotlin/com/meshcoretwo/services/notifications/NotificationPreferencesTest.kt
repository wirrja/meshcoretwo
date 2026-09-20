// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/** New coverage — `NotificationPreferences.swift`'s `init()` has no test file of its own either. */
@RunWith(RobolectricTestRunner::class)
class NotificationPreferencesTest {
    private fun freshPrefs() =
        RuntimeEnvironment.getApplication().getSharedPreferences("notif-prefs-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    @Test
    fun `every toggle defaults to enabled`() {
        val prefs = NotificationPreferences.load(freshPrefs())
        assertTrue(prefs.contactMessagesEnabled)
        assertTrue(prefs.channelMessagesEnabled)
        assertTrue(prefs.roomMessagesEnabled)
        assertTrue(prefs.newContactDiscoveredEnabled)
        assertTrue(prefs.discoveryContactEnabled)
        assertTrue(prefs.discoveryRepeaterEnabled)
        assertTrue(prefs.discoveryRoomEnabled)
        assertTrue(prefs.reactionNotificationsEnabled)
        assertTrue(prefs.soundEnabled)
        assertTrue(prefs.lowBatteryEnabled)
    }

    @Test
    fun `an explicitly stored false is honored`() {
        val sharedPrefs = freshPrefs()
        sharedPrefs.edit().putBoolean(NotificationPreferences.Keys.CHANNEL_MESSAGES, false).apply()

        val prefs = NotificationPreferences.load(sharedPrefs)

        assertFalse(prefs.channelMessagesEnabled)
        assertTrue(prefs.contactMessagesEnabled)
    }
}
