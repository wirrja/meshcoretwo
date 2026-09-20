// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import com.meshcoretwo.android.settings.PresetLocationPolicy.ResolveKind
import com.meshcoretwo.android.settings.PresetLocationPolicy.UseMyLocationAction
import com.meshcoretwo.services.region.RegionSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetLocationPolicyTest {
    private val manual = RegionSelection("NL", source = RegionSelection.Source.MANUAL)
    private val located = RegionSelection("US", "US-CA", source = RegionSelection.Source.LOCATION)
    private val fresh = RegionSelection("DE", source = RegionSelection.Source.LOCATION)

    @Test
    fun `appear-resolve needs permission and no manual pick`() {
        assertTrue(PresetLocationPolicy.shouldResolveOnAppear(granted = true, source = null))
        assertTrue(PresetLocationPolicy.shouldResolveOnAppear(granted = true, source = RegionSelection.Source.LOCATION))
        assertFalse(PresetLocationPolicy.shouldResolveOnAppear(granted = true, source = RegionSelection.Source.MANUAL))
        assertFalse(PresetLocationPolicy.shouldResolveOnAppear(granted = false, source = null))
    }

    @Test
    fun `an automatic resolve never overwrites a manual pick`() {
        assertEquals(manual, PresetLocationPolicy.committedSelection(manual, fresh, ResolveKind.APPEAR))
        assertEquals(fresh, PresetLocationPolicy.committedSelection(located, fresh, ResolveKind.APPEAR))
        assertEquals(fresh, PresetLocationPolicy.committedSelection(null, fresh, ResolveKind.APPEAR))
    }

    @Test
    fun `use my location overrides a manual pick`() {
        assertEquals(fresh, PresetLocationPolicy.committedSelection(manual, fresh, ResolveKind.USER_INITIATED))
    }

    @Test
    fun `a failed lookup keeps the current selection`() {
        assertEquals(manual, PresetLocationPolicy.committedSelection(manual, null, ResolveKind.USER_INITIATED))
        assertEquals(null, PresetLocationPolicy.committedSelection(null, null, ResolveKind.APPEAR))
    }

    @Test
    fun `permission decides between resolving and asking`() {
        assertEquals(UseMyLocationAction.RESOLVE, PresetLocationPolicy.useMyLocationAction(granted = true))
        assertEquals(UseMyLocationAction.REQUEST_PERMISSION, PresetLocationPolicy.useMyLocationAction(granted = false))
    }

    @Test
    fun `only user-initiated misses are surfaced`() {
        assertTrue(PresetLocationPolicy.shouldPresentLookupMiss(ResolveKind.USER_INITIATED))
        assertFalse(PresetLocationPolicy.shouldPresentLookupMiss(ResolveKind.APPEAR))
    }
}
