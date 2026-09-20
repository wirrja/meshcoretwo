// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [RepeaterSettingsViewModel]'s pure region-parsing helpers, ported from `RepeaterSettingsViewModelRegionTests.swift`. */
class RepeaterSettingsViewModelCompanionTest {
    // MARK: - parseRegionTree

    @Test
    fun `parseRegionTree keeps indent and parents`() {
        val dump = "* F\n can F\n  on F\n   gta F\n   ottawa F\n   hamilton F\n"
        val parsed = RepeaterSettingsViewModel.parseRegionTree(dump)

        assertEquals(listOf("*", "can", "on", "gta", "ottawa", "hamilton"), parsed.map { it.name })
        assertEquals(listOf(null, "*", "can", "on", "on", "on"), parsed.map { it.parentName })
        assertEquals(listOf(0, 1, 2, 3, 3, 3), parsed.map { it.depth })
        assertTrue(parsed.all { it.floodAllowed })
    }

    @Test
    fun `parseRegionTree strips caret and reads deny-flood`() {
        val parsed = RepeaterSettingsViewModel.parseRegionTree("* F\n on^ F\n  gta\n")

        assertEquals(listOf("*", "on", "gta"), parsed.map { it.name })
        assertTrue(parsed[1].floodAllowed)
        assertTrue(!parsed[2].floodAllowed)
        assertEquals("on", parsed[2].parentName)
    }

    @Test
    fun `parseRegionTree strips factory Unscoped caret`() {
        val parsed = RepeaterSettingsViewModel.parseRegionTree("*^ F\n")

        assertEquals(listOf("*"), parsed.map { it.name })
        assertTrue(parsed[0].floodAllowed)
        assertNull(parsed[0].parentName)
    }

    @Test
    fun `parseRegionTree rejects skipped indent`() {
        val dump = "* F\n   gta F\n"

        assertTrue(RepeaterSettingsViewModel.parseRegionTree(dump).isEmpty())
    }

    @Test
    fun `parseRegionTree rejects a space in the name`() {
        assertTrue(RepeaterSettingsViewModel.parseRegionTree("* F\n foo bar F\n").isEmpty())
    }

    @Test
    fun `parseRegionTree skips blank lines`() {
        val entries = RepeaterSettingsViewModel.parseRegionTree("* F\n\n duckburg F\n")

        assertEquals(listOf("*", "duckburg"), entries.map { it.name })
    }

    @Test
    fun `parseRegionTree CRLF dump matches LF`() {
        val lf = "* F\n on F\n"
        val crlf = "* F\r\n on F\r\n"

        assertEquals(RepeaterSettingsViewModel.parseRegionTree(lf), RepeaterSettingsViewModel.parseRegionTree(crlf))
    }

    @Test
    fun `parseRegionTree single-line CRLF dump matches LF`() {
        assertEquals(
            RepeaterSettingsViewModel.parseRegionTree("* F\n"),
            RepeaterSettingsViewModel.parseRegionTree("* F\r\n"),
        )
    }

    @Test
    fun `parseRegionTree rejects a dump that does not end with a newline`() {
        assertTrue(RepeaterSettingsViewModel.parseRegionTree("* F\n on F").isEmpty())
    }

    /** One flood-allowed row: `<name> F\n`. [utf8Count] is the full dump size. */
    private fun lfTerminatedFloodDump(utf8Count: Int): String {
        val suffix = " F\n"
        val nameLen = utf8Count - suffix.toByteArray(Charsets.UTF_8).size
        val dump = "a".repeat(nameLen) + suffix
        check(dump.toByteArray(Charsets.UTF_8).size == utf8Count)
        return dump
    }

    @Test
    fun `parseRegionTree rejects a saturated newline-terminated dump`() {
        val dump = lfTerminatedFloodDump(RepeaterSettingsViewModel.FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES)

        assertTrue(RepeaterSettingsViewModel.parseRegionTree(dump).isEmpty())
    }

    @Test
    fun `parseRegionTree accepts a dump one byte under the firmware cap`() {
        val dump = lfTerminatedFloodDump(RepeaterSettingsViewModel.FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES - 1)
        val parsed = RepeaterSettingsViewModel.parseRegionTree(dump)

        assertEquals(1, parsed.size)
        assertTrue(parsed[0].floodAllowed)
    }

    // MARK: - parseDefaultScopeReply

    @Test
    fun `parseDefaultScopeReply reads get and set lines`() {
        assertEquals(
            RepeaterSettingsViewModel.ParsedDefaultScope.Cleared,
            RepeaterSettingsViewModel.parseDefaultScopeReply(" default scope is <null>"),
        )
        assertEquals(
            RepeaterSettingsViewModel.ParsedDefaultScope.Named("duckburg"),
            RepeaterSettingsViewModel.parseDefaultScopeReply(" default scope is duckburg"),
        )
        assertEquals(
            RepeaterSettingsViewModel.ParsedDefaultScope.Named("duckburg"),
            RepeaterSettingsViewModel.parseDefaultScopeReply(">  default scope is now duckburg"),
        )
        assertEquals(
            RepeaterSettingsViewModel.ParsedDefaultScope.Cleared,
            RepeaterSettingsViewModel.parseDefaultScopeReply(" default scope is now <null>"),
        )
        assertNull(RepeaterSettingsViewModel.parseDefaultScopeReply("OK"))
        assertEquals(
            RepeaterSettingsViewModel.ParsedDefaultScope.Cleared,
            RepeaterSettingsViewModel.parseDefaultScopeReply(" default scope is *"),
        )
    }

    @Test
    fun `parseDefaultScopeReply returns null for a reply with no default-scope marker`() {
        assertNull(RepeaterSettingsViewModel.parseDefaultScopeReply("some unrelated CLI output"))
        assertNull(RepeaterSettingsViewModel.parseDefaultScopeReply(""))
    }

    // MARK: - RepeaterRegionEntry

    @Test
    fun `isWildcard matches only the star entry`() {
        assertEquals(true, RepeaterRegionEntry(name = "*", parentName = null, depth = 0, floodAllowed = true, isHome = false).isWildcard)
        assertEquals(false, RepeaterRegionEntry(name = "duckburg", parentName = "*", depth = 1, floodAllowed = true, isHome = false).isWildcard)
    }

    @Test
    fun `namedParent is null for the wildcard root and its direct children`() {
        val root = RepeaterRegionEntry(name = "*", parentName = null, depth = 0, floodAllowed = true, isHome = false)
        val topLevel = RepeaterRegionEntry(name = "duckburg", parentName = "*", depth = 1, floodAllowed = true, isHome = false)
        val nested = RepeaterRegionEntry(name = "gta", parentName = "on", depth = 2, floodAllowed = true, isHome = false)

        assertNull(root.namedParent)
        assertNull(topLevel.namedParent)
        assertEquals("on", nested.namedParent)
    }
}
