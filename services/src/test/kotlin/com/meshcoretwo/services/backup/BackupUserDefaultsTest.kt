// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class BackupUserDefaultsTest {
    private fun freshPrefs() =
        RuntimeEnvironment.getApplication().getSharedPreferences("backup-user-defaults-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    @Test
    fun `snapshot of an empty prefs file is all null`() {
        val snapshot = BackupUserDefaults.snapshot(freshPrefs())

        assertNull(snapshot.selectedThemeID)
        assertNull(snapshot.notifyContactMessages)
        assertNull(snapshot.autoDeleteStaleNodesDays)
    }

    @Test
    fun `snapshot reads every key that's set, including an explicit false`() {
        val prefs = freshPrefs()
        prefs.edit()
            .putString("selectedThemeID", "midnight")
            .putString("appColorSchemePreference", "dark")
            .putBoolean("notifyChannelMessages", false)
            .putInt("autoDeleteStaleNodesDays", 30)
            .apply()

        val snapshot = BackupUserDefaults.snapshot(prefs)

        assertEquals("midnight", snapshot.selectedThemeID)
        assertEquals("dark", snapshot.appColorSchemePreference)
        assertEquals(false, snapshot.notifyChannelMessages)
        assertEquals(30, snapshot.autoDeleteStaleNodesDays)
        assertNull(snapshot.notifyContactMessages)
    }

    @Test
    fun `restore fills every missing key and reports it wrote something`() {
        val snapshot = BackupUserDefaults(
            selectedThemeID = "midnight",
            notifyContactMessages = false,
            autoDeleteStaleNodesDays = 14,
        )
        val prefs = freshPrefs()

        val wroteAny = snapshot.restore(prefs)

        assertTrue(wroteAny)
        assertEquals("midnight", prefs.getString("selectedThemeID", null))
        assertFalse(prefs.getBoolean("notifyContactMessages", true))
        assertEquals(14, prefs.getInt("autoDeleteStaleNodesDays", 0))
    }

    @Test
    fun `restore never overwrites a key this install already set`() {
        val prefs = freshPrefs()
        prefs.edit().putString("selectedThemeID", "sunrise").apply()
        val snapshot = BackupUserDefaults(selectedThemeID = "midnight")

        val wroteAny = snapshot.restore(prefs)

        assertFalse(wroteAny)
        assertEquals("sunrise", prefs.getString("selectedThemeID", null))
    }

    @Test
    fun `restore of an all-null snapshot writes nothing`() {
        val wroteAny = BackupUserDefaults().restore(freshPrefs())

        assertFalse(wroteAny)
    }

    @Test
    fun `snapshot then restore into a fresh file round-trips every value`() {
        val sourcePrefs = freshPrefs()
        sourcePrefs.edit()
            .putString("selectedThemeID", "midnight")
            .putString("appColorSchemePreference", "dark")
            .putBoolean("notifyContactMessages", false)
            .putBoolean("notifyChannelMessages", true)
            .putBoolean("notifyRoomMessages", false)
            .putBoolean("notifyNewContacts", true)
            .putBoolean("notifyNewContactsContact", false)
            .putBoolean("notifyNewContactsRepeater", true)
            .putBoolean("notifyNewContactsRoom", false)
            .putBoolean("notifyReactions", true)
            .putBoolean("notificationSoundEnabled", false)
            .putBoolean("notifyLowBattery", true)
            .putInt("autoDeleteStaleNodesDays", 90)
            .apply()
        val snapshot = BackupUserDefaults.snapshot(sourcePrefs)

        val targetPrefs = freshPrefs()
        snapshot.restore(targetPrefs)

        assertEquals(snapshot, BackupUserDefaults.snapshot(targetPrefs))
    }
}
