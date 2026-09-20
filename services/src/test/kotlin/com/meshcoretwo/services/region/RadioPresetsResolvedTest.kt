// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers `matchingPresets`/`resolvedPreset` (upstream `74ad7911`): `au-sa-wa` and `br` share an RF tuple. */
class RadioPresetsResolvedTest {
    private val brazil = RadioPresets.all.first { it.id == "br" }
    private val saWa = RadioPresets.all.first { it.id == "au-sa-wa" }

    private fun resolve(preferredID: String?) = RadioPresets.resolvedPreset(
        brazil.frequencyKHz, brazil.bandwidthHz, brazil.spreadingFactor, brazil.codingRate,
        preferredID = preferredID, region = null,
    )

    @Test
    fun `matchingPresets returns every catalog row sharing the tuple`() {
        val ids = RadioPresets.matchingPresets(brazil.frequencyKHz, brazil.bandwidthHz, brazil.spreadingFactor, brazil.codingRate).map { it.id }
        assertTrue(ids.containsAll(listOf("br", "au-sa-wa")))
    }

    @Test
    fun `resolvedPreset prefers the applied id when it is still RF-equal`() {
        assertEquals("br", resolve("br")?.id)
        assertEquals("au-sa-wa", resolve("au-sa-wa")?.id)
    }

    @Test
    fun `resolvedPreset ignores an applied id that no longer matches`() {
        val other = RadioPresets.all.first { it.frequencyKHz != brazil.frequencyKHz }
        assertNull(resolve(other.id))
    }

    @Test
    fun `matchingPreset is null on a colliding tuple instead of first-match`() {
        assertNull(RadioPresets.matchingPreset(brazil.frequencyKHz, brazil.bandwidthHz, brazil.spreadingFactor, brazil.codingRate))
    }

    @Test
    fun `a unique tuple still resolves without an applied id`() {
        val unique = RadioPresets.all.first {
            RadioPresets.matchingPresets(it.frequencyKHz, it.bandwidthHz, it.spreadingFactor, it.codingRate).size == 1
        }
        assertEquals(unique.id, RadioPresets.matchingPreset(unique.frequencyKHz, unique.bandwidthHz, unique.spreadingFactor, unique.codingRate)?.id)
        assertEquals(saWa.frequencyKHz, brazil.frequencyKHz)
    }
}
