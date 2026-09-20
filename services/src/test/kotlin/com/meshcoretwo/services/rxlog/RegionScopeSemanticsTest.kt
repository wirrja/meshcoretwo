// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rxlog

import com.meshcoretwo.protocol.RegionMatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from `RegionScopeSemanticsTests.swift`. */
class RegionScopeSemanticsTest {
    @Test
    fun `storageFields maps none to empty fields`() {
        val fields = RegionScopeSemantics.storageFields(RegionMatchResult.None)
        assertNull(fields.regionScope)
        assertEquals(emptyList<String>(), fields.regionScopeMatches)
    }

    @Test
    fun `storageFields maps unique to name plus single-element matches`() {
        val fields = RegionScopeSemantics.storageFields(RegionMatchResult.Unique("Germany"))
        assertEquals("Germany", fields.regionScope)
        assertEquals(listOf("Germany"), fields.regionScopeMatches)
    }

    @Test
    fun `storageFields maps ambiguous to null scope and sorted names`() {
        val fields = RegionScopeSemantics.storageFields(RegionMatchResult.Ambiguous(listOf("de-hh", "de-by")))
        assertNull(fields.regionScope)
        assertEquals(listOf("de-by", "de-hh"), fields.regionScopeMatches)
    }

    @Test
    fun `coalesce prioritizes two-plus matches over a stale scope`() {
        val result = RegionScopeSemantics.coalesce(scope = "de-hh", matches = listOf("de-by", "de-hh"))
        assertEquals(RegionMatchResult.Ambiguous(listOf("de-by", "de-hh")), result)
    }

    @Test
    fun `coalesce treats a single match as unique regardless of scope`() {
        assertEquals(RegionMatchResult.Unique("USA"), RegionScopeSemantics.coalesce(scope = null, matches = listOf("USA")))
    }

    @Test
    fun `coalesce falls back to a legacy non-null scope when matches is empty`() {
        assertEquals(RegionMatchResult.Unique("Bavaria"), RegionScopeSemantics.coalesce(scope = "Bavaria", matches = emptyList()))
    }

    @Test
    fun `coalesce is none when both fields are empty or blank`() {
        assertEquals(RegionMatchResult.None, RegionScopeSemantics.coalesce(scope = null, matches = emptyList()))
        assertEquals(RegionMatchResult.None, RegionScopeSemantics.coalesce(scope = "  ", matches = listOf("", " ")))
    }

    @Test
    fun `chipLabel is null for none, the name for unique, and slash-joined for ambiguous`() {
        assertNull(RegionScopeSemantics.chipLabel(RegionMatchResult.None))
        assertEquals("Germany", RegionScopeSemantics.chipLabel(RegionMatchResult.Unique("Germany")))
        assertEquals("de-by / de-hh", RegionScopeSemantics.chipLabel(RegionMatchResult.Ambiguous(listOf("de-by", "de-hh"))))
    }

    @Test
    fun `matchNames unwraps each case into a flat list`() {
        assertEquals(emptyList<String>(), RegionScopeSemantics.matchNames(RegionMatchResult.None))
        assertEquals(listOf("Germany"), RegionScopeSemantics.matchNames(RegionMatchResult.Unique("Germany")))
        assertEquals(listOf("de-by", "de-hh"), RegionScopeSemantics.matchNames(RegionMatchResult.Ambiguous(listOf("de-by", "de-hh"))))
    }
}
