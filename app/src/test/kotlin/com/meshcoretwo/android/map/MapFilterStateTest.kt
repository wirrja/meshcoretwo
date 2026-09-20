// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import com.meshcoretwo.protocol.ContactType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `MapFilterStateTests.swift`'s pin-algebra coverage, trimmed to the `mainMap`-only shape ported here (see [MapFilterState]'s class doc). */
class MapFilterStateTest {
    @Test
    fun `default state shows all contact types but no discovered nodes`() {
        val state = MapFilterState()
        assertFalse(state.favoritesOnly)
        assertFalse(state.effectiveShowDiscovered)
        assertTrue(state.allowsContactType(ContactType.CHAT))
        assertTrue(state.allowsContactType(ContactType.REPEATER))
        assertTrue(state.allowsContactType(ContactType.ROOM))
        assertFalse(state.isActive)
    }

    @Test
    fun `favoritesOnly bypasses type filtering`() {
        val state = MapFilterState(favoritesOnly = true, showChat = false, showRepeater = false, showRoom = false)
        assertTrue(state.allowsContactType(ContactType.CHAT))
        assertTrue(state.allowsContactType(ContactType.REPEATER))
        assertTrue(state.allowsContactType(ContactType.ROOM))
    }

    @Test
    fun `withShowDiscovered is a no-op while favoritesOnly is on`() {
        val state = MapFilterState(favoritesOnly = true, showDiscovered = false)
        val next = state.withShowDiscovered(true)
        assertEquals(state, next)
        assertFalse(next.effectiveShowDiscovered)
    }

    @Test
    fun `effectiveShowDiscovered requires both the flag and favoritesOnly off`() {
        assertTrue(MapFilterState(showDiscovered = true).effectiveShowDiscovered)
        assertFalse(MapFilterState(showDiscovered = true, favoritesOnly = true).effectiveShowDiscovered)
        assertFalse(MapFilterState(showDiscovered = false).effectiveShowDiscovered)
    }

    @Test
    fun `type toggles refuse to disable the last enabled type`() {
        val allButChat = MapFilterState(showChat = false, showRepeater = false, showRoom = true)
        val result = allButChat.withShowRoom(false)
        assertEquals(allButChat, result)
    }

    @Test
    fun `type toggles apply normally when another type stays enabled`() {
        val state = MapFilterState()
        val result = state.withShowChat(false)
        assertFalse(result.showChat)
        assertTrue(result.showRepeater)
        assertTrue(result.showRoom)
    }

    @Test
    fun `type toggles are no-ops while favoritesOnly is on`() {
        val state = MapFilterState(favoritesOnly = true)
        val result = state.withShowChat(false)
        assertEquals(state, result)
    }

    @Test
    fun `isActive reflects any deviation from the default`() {
        assertFalse(MapFilterState().isActive)
        assertTrue(MapFilterState(favoritesOnly = true).isActive)
        assertTrue(MapFilterState(showDiscovered = true).isActive)
        assertTrue(MapFilterState(showChat = false).isActive)
    }
}
