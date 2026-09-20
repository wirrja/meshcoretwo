// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Ported from `RegionalAreasTests.swift`. */
class RegionalAreasTest {
    @Test
    fun `matchSubdivision finds California from normalized state name`() {
        assertEquals("US-CA", RegionalAreas.matchSubdivision(country = "US", normalized = "ca"))
    }

    @Test
    fun `matchSubdivision finds Queensland from short suffix`() {
        assertEquals("AU-QLD", RegionalAreas.matchSubdivision(country = "AU", normalized = "qld"))
    }

    @Test
    fun `matchSubdivision returns null for unknown subdivision`() {
        assertNull(RegionalAreas.matchSubdivision(country = "US", normalized = "zz"))
    }

    @Test
    fun `matchSubdivision returns null for null input`() {
        assertNull(RegionalAreas.matchSubdivision(country = "US", normalized = null))
    }

    @Test
    fun `matchCounty finds Los Angeles in US-CA`() {
        assertEquals("los angeles", RegionalAreas.matchCounty(country = "US", state = "US-CA", normalized = "los angeles"))
    }

    @Test
    fun `matchCounty rejects unknown county`() {
        assertNull(RegionalAreas.matchCounty(country = "US", state = "US-CA", normalized = "sacramento"))
    }

    @Test
    fun `matchCounty rejects non-US country`() {
        assertNull(RegionalAreas.matchCounty(country = "CA", state = "CA-ON", normalized = "york"))
    }

    @Test
    fun `matchCounty rejects null state`() {
        assertNull(RegionalAreas.matchCounty(country = "US", state = null, normalized = "los angeles"))
    }

    @Test
    fun `continents map covers known European countries`() {
        assertEquals(RadioPresets.RadioRegion.EUROPE, RegionalAreas.continents["DE"])
        assertEquals(RadioPresets.RadioRegion.EUROPE, RegionalAreas.continents["GB"])
        assertEquals(RadioPresets.RadioRegion.EUROPE, RegionalAreas.continents["PT"])
    }

    @Test
    fun `continents map covers Oceania and Asia`() {
        assertEquals(RadioPresets.RadioRegion.OCEANIA, RegionalAreas.continents["AU"])
        assertEquals(RadioPresets.RadioRegion.OCEANIA, RegionalAreas.continents["NZ"])
        assertEquals(RadioPresets.RadioRegion.ASIA, RegionalAreas.continents["VN"])
    }

    @Test
    fun `Mexico is intentionally absent from continents`() {
        assertNull(RegionalAreas.continents["MX"])
    }

    @Test
    fun `displayName uses short form for US states`() {
        val region = RegionSelection(countryCode = "US", administrativeAreaCode = "US-CA", source = RegionSelection.Source.MANUAL)
        assertEquals("California", RegionalAreas.displayName(region, Locale.US))
    }

    @Test
    fun `displayName uses disambiguated form for AU territories`() {
        val region = RegionSelection(countryCode = "AU", administrativeAreaCode = "AU-QLD", source = RegionSelection.Source.MANUAL)
        val name = RegionalAreas.displayName(region, Locale.US)
        assertTrue(name.contains("Queensland"))
        assertTrue(name.contains("Australia"))
    }

    @Test
    fun `displayName falls back to country name when admin is null`() {
        val region = RegionSelection(countryCode = "US", source = RegionSelection.Source.MANUAL)
        assertEquals("United States", RegionalAreas.displayName(region, Locale.US))
    }

    @Test
    fun `Meshcoretel MOW pseudo-country is selectable in the country picker with a friendly name`() {
        val entry = RegionalAreas.countriesSortedByLocalizedName(Locale.US)
            .first { it.id == RegionalAreas.MESHCORETEL_MOW_ID }
        assertEquals("Meshcoretel MOW", entry.localizedName(Locale.US))
    }

    @Test
    fun `displayName resolves the Meshcoretel MOW pseudo-country without crashing on the non-ISO id`() {
        val region = RegionSelection(countryCode = RegionalAreas.MESHCORETEL_MOW_ID, source = RegionSelection.Source.MANUAL)
        assertEquals("Meshcoretel MOW", RegionalAreas.displayName(region, Locale.US))
    }

    @Test
    fun `continents and countries cover the same set of country codes`() {
        // Adding a country to one table without the other silently breaks the picker
        // (visible but no recommendation) or the recommendation (no picker entry).
        val continentKeys = RegionalAreas.continents.keys
        val countryIds = RegionalAreas.countries.map { it.id }.toSet()
        assertEquals(continentKeys, countryIds)
    }
}
