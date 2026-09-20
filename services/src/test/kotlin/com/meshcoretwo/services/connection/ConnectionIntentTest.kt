// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/** Ported from the `ConnectionIntent` persistence behavior in `ConnectionManager.swift`'s test suite. */
@RunWith(RobolectricTestRunner::class)
class ConnectionIntentTest {
    private fun newPrefs() =
        RuntimeEnvironment.getApplication().getSharedPreferences("intent-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    @Test
    fun `wantsConnection is true only for the WantsConnection case`() {
        assertTrue(ConnectionIntent.WantsConnection().wantsConnection)
        assertFalse(ConnectionIntent.None.wantsConnection)
        assertFalse(ConnectionIntent.UserDisconnected.wantsConnection)
    }

    @Test
    fun `isUserDisconnected is true only for UserDisconnected`() {
        assertTrue(ConnectionIntent.UserDisconnected.isUserDisconnected)
        assertFalse(ConnectionIntent.None.isUserDisconnected)
        assertFalse(ConnectionIntent.WantsConnection().isUserDisconnected)
    }

    @Test
    fun `restored is None when nothing persisted`() {
        assertEquals(ConnectionIntent.None, ConnectionIntent.restored(newPrefs()))
    }

    @Test
    fun `persisting UserDisconnected then restoring returns UserDisconnected`() {
        val prefs = newPrefs()
        ConnectionIntent.UserDisconnected.persist(prefs)
        assertEquals(ConnectionIntent.UserDisconnected, ConnectionIntent.restored(prefs))
    }

    @Test
    fun `persisting None clears a previously persisted UserDisconnected`() {
        val prefs = newPrefs()
        ConnectionIntent.UserDisconnected.persist(prefs)

        ConnectionIntent.None.persist(prefs)

        assertEquals(ConnectionIntent.None, ConnectionIntent.restored(prefs))
    }

    @Test
    fun `persisting WantsConnection clears a previously persisted UserDisconnected`() {
        val prefs = newPrefs()
        ConnectionIntent.UserDisconnected.persist(prefs)

        ConnectionIntent.WantsConnection(forceFullSync = true).persist(prefs)

        assertEquals(ConnectionIntent.None, ConnectionIntent.restored(prefs))
    }
}
