// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NotificationPreferencesStoreTest {
    private fun freshStore(): NotificationPreferencesStore {
        // Isolate each test from NotificationPreferences.PREFS_NAME's real file, which every test
        // would otherwise share via RuntimeEnvironment's single application context.
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(NotificationPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        return NotificationPreferencesStore(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `starts from the on-disk defaults`() {
        val store = freshStore()
        assertTrue(store.preferences.value.contactMessagesEnabled)
        assertTrue(store.preferences.value.soundEnabled)
    }

    @Test
    fun `a setter updates both the in-memory state and the on-disk value`() {
        val store = freshStore()

        store.setChannelMessagesEnabled(false)

        assertFalse(store.preferences.value.channelMessagesEnabled)
        assertFalse(NotificationPreferences.load(RuntimeEnvironment.getApplication()).channelMessagesEnabled)
    }

    @Test
    fun `a setter for one toggle leaves the others untouched`() {
        val store = freshStore()

        store.setLowBatteryEnabled(false)

        assertFalse(store.preferences.value.lowBatteryEnabled)
        assertTrue(store.preferences.value.contactMessagesEnabled)
        assertTrue(store.preferences.value.reactionNotificationsEnabled)
    }
}
