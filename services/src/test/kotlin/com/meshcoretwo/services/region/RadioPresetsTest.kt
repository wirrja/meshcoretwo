// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

import com.meshcoretwo.services.region.RadioPresets.RadioRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from `RadioPresetRecommendationTests.swift`. */
class RadioPresetsRecommendedTest {
    private fun region(
        country: String,
        admin: String? = null,
        county: String? = null,
        source: RegionSelection.Source = RegionSelection.Source.LOCATION,
    ) = RegionSelection(countryCode = country, administrativeAreaCode = admin, countyKey = county, source = source)

    // MARK: - Tier 0 (county)

    @Test
    fun `LA, CA to WCMesh`() {
        val r = region("US", "US-CA", "los angeles")
        assertEquals("wcmesh", RadioPresets.recommended(r)?.id)
    }

    @Test
    fun `Sacramento no countyKey match to us-ca`() {
        val r = region("US", "US-CA", "sacramento")
        assertEquals("us-ca", RadioPresets.recommended(r)?.id)
    }

    @Test
    fun `Manual California pick countyKey null to us-ca`() {
        val r = region("US", "US-CA", source = RegionSelection.Source.MANUAL)
        assertEquals("us-ca", RadioPresets.recommended(r)?.id)
    }

    // MARK: - Tier 1 (sub-region)

    @Test
    fun `Queensland to au-qld`() {
        val r = region("AU", "AU-QLD")
        assertEquals("au-qld", RadioPresets.recommended(r)?.id)
    }

    @Test
    fun `Western Australia to au-sa-wa`() {
        val r = region("AU", "AU-WA")
        assertEquals("au-sa-wa", RadioPresets.recommended(r)?.id)
    }

    // MARK: - Tier 2 (country)

    @Test
    fun `Victoria AU no sub-region preset to au-915 Tier 2`() {
        val r = region("AU", "AU-VIC")
        assertEquals("au-915", RadioPresets.recommended(r)?.id)
    }

    @Test
    fun `Texas to us-ca`() {
        val r = region("US", "US-TX")
        assertEquals("us-ca", RadioPresets.recommended(r)?.id)
    }

    @Test
    fun `Lisbon PT to pt-868, priority 110 beats pt-433`() {
        val r = region("PT")
        assertEquals("pt-868", RadioPresets.recommended(r)?.id)
    }

    @Test
    fun `Vietnam to vn-narrow, priority 110 beats deprecated vn`() {
        val r = region("VN")
        assertEquals("vn-narrow", RadioPresets.recommended(r)?.id)
    }

    @Test
    fun `Netherlands to nl, country tier beats EU continent`() {
        val r = region("NL")
        assertEquals("nl", RadioPresets.recommended(r)?.id)
    }

    @Test
    fun `Chile to cl`() {
        assertEquals("cl", RadioPresets.recommended(region("CL"))?.id)
    }

    @Test
    fun `Chile preset carries the expected radio parameters`() {
        val preset = RadioPresets.all.first { it.id == "cl" }
        assertEquals(927.875, preset.frequencyMHz, 0.0)
        assertEquals(62.5, preset.bandwidthKHz, 0.0)
        assertEquals(8u.toUByte(), preset.spreadingFactor)
        assertEquals(5u.toUByte(), preset.codingRate)
        assertEquals(RadioRegion.SOUTH_AMERICA, preset.region)
    }

    @Test
    fun `Brazil to br`() {
        assertEquals("br", RadioPresets.recommended(region("BR"))?.id)
    }

    @Test
    fun `Meshcoretel MOW preset carries the expected radio parameters and path hash mode`() {
        val preset = RadioPresets.all.first { it.id == "meshcoretel-mow" }
        assertEquals(868.731018, preset.frequencyMHz, 0.0)
        assertEquals(62.5, preset.bandwidthKHz, 0.0)
        assertEquals(7u.toUByte(), preset.spreadingFactor)
        assertEquals(7u.toUByte(), preset.codingRate)
        assertEquals(1u.toUByte(), preset.pathHashMode)
        assertEquals(RadioRegion.MESHCORETEL_MOW, preset.region)
    }

    @Test
    fun `Meshcoretel MOW is not recommended for, or listed among, a mapped country's presets`() {
        assertFalse(RadioPresets.presets(region("DE")).map { it.id }.contains("meshcoretel-mow"))
        assertNotEquals("meshcoretel-mow", RadioPresets.recommended(region("DE"))?.id)
    }

    @Test
    fun `Meshcoretel MOW pseudo-country recommends its own preset`() {
        val r = region(RegionalAreas.MESHCORETEL_MOW_ID)
        assertEquals("meshcoretel-mow", RadioPresets.recommended(r)?.id)
        assertEquals(listOf("meshcoretel-mow"), RadioPresets.presets(r).map { it.id })
    }

    @Test
    fun `Brazil preset carries the expected radio parameters`() {
        val preset = RadioPresets.all.first { it.id == "br" }
        assertEquals(923.125, preset.frequencyMHz, 0.0)
        assertEquals(62.5, preset.bandwidthKHz, 0.0)
        assertEquals(8u.toUByte(), preset.spreadingFactor)
        assertEquals(8u.toUByte(), preset.codingRate)
        assertEquals(RadioRegion.SOUTH_AMERICA, preset.region)
    }

    // MARK: - Tier 3 (continent)

    @Test
    fun `Berlin DE to eu-narrow, priority 110 beats eu-lr`() {
        assertEquals("eu-narrow", RadioPresets.recommended(region("DE"))?.id)
    }

    // MARK: - No match

    @Test
    fun `Bermuda to null, no continent mapping`() {
        assertNull(RadioPresets.recommended(region("BM", source = RegionSelection.Source.MANUAL)))
    }

    // MARK: - presets(for:)

    @Test
    fun `presets for Sacramento includes wcmesh in alternatives`() {
        val ids = RadioPresets.presets(region("US", "US-CA", "sacramento")).map { it.id }
        assertTrue(ids.contains("wcmesh"))
        assertTrue(ids.contains("us-ca"))
    }

    @Test
    fun `presets for DE returns continent-tier presets`() {
        val ids = RadioPresets.presets(region("DE")).map { it.id }
        assertTrue(ids.contains("eu-narrow"))
        assertTrue(ids.contains("eu-lr"))
        assertFalse(ids.contains("us-ca"))
    }

    @Test
    fun `presets for PT returns country-and-below only`() {
        val ids = RadioPresets.presets(region("PT")).map { it.id }
        assertTrue(ids.contains("pt-868"))
        assertTrue(ids.contains("pt-433"))
        assertFalse(ids.contains("eu-narrow"))
    }

    @Test
    fun `presets for VN includes both vn-narrow and vn`() {
        val ids = RadioPresets.presets(region("VN")).map { it.id }
        assertTrue(ids.contains("vn-narrow"))
        assertTrue(ids.contains("vn"))
    }

    @Test
    fun `presets for BR includes br`() {
        assertTrue(RadioPresets.presets(region("BR")).map { it.id }.contains("br"))
    }
}

/** Ported from `RadioPresetSelectabilityTests` in `RadioPresetRecommendationTests.swift`. */
class RadioPresetsSelectabilityTest {
    private fun preset(id: String) = RadioPresets.all.first { it.id == id }

    @Test
    fun `SoCal county WCMesh selectable`() {
        val r = RegionSelection("US", "US-CA", "los angeles", RegionSelection.Source.LOCATION)
        assertTrue(RadioPresets.isSelectable(preset("wcmesh"), r))
    }

    @Test
    fun `NorCal county WCMesh hidden, us-ca still selectable`() {
        val r = RegionSelection("US", "US-CA", "sacramento", RegionSelection.Source.LOCATION)
        assertFalse(RadioPresets.isSelectable(preset("wcmesh"), r))
        assertTrue(RadioPresets.isSelectable(preset("us-ca"), r))
    }

    @Test
    fun `California with no county WCMesh hidden`() {
        val r = RegionSelection("US", "US-CA", source = RegionSelection.Source.MANUAL)
        assertFalse(RadioPresets.isSelectable(preset("wcmesh"), r))
    }

    @Test
    fun `Non-CA US state WCMesh hidden, us-ca selectable`() {
        val r = RegionSelection("US", "US-TX", source = RegionSelection.Source.LOCATION)
        assertFalse(RadioPresets.isSelectable(preset("wcmesh"), r))
        assertTrue(RadioPresets.isSelectable(preset("us-ca"), r))
    }

    @Test
    fun `null region WCMesh hidden, global presets selectable`() {
        assertFalse(RadioPresets.isSelectable(preset("wcmesh"), null))
        assertTrue(RadioPresets.isSelectable(preset("us-ca"), null))
        assertTrue(RadioPresets.isSelectable(preset("eu-narrow"), null))
    }

    @Test
    fun `Continent-country presets selectable for any region including null`() {
        val regions = listOf(
            null,
            RegionSelection("US", "US-TX", source = RegionSelection.Source.LOCATION),
            RegionSelection("DE", source = RegionSelection.Source.LOCATION),
        )
        for (r in regions) {
            assertTrue(RadioPresets.isSelectable(preset("eu-narrow"), r))
            assertTrue(RadioPresets.isSelectable(preset("us-ca"), r))
        }
    }
}

/** Ported from `RadioPresetEncodingTests` in `RadioPresetRecommendationTests.swift`. */
class RadioPresetsEncodingTest {
    private fun testPreset(frequencyMHz: Double, bandwidthKHz: Double) = RadioPresets.RadioPreset(
        id = "test",
        name = "Test",
        region = RadioRegion.NORTH_AMERICA,
        frequencyMHz = frequencyMHz,
        bandwidthKHz = bandwidthKHz,
        spreadingFactor = 7u,
        codingRate = 5u,
        availability = RadioPresets.PresetAvailability.Continent(RadioRegion.NORTH_AMERICA),
    )

    @Test
    fun `frequencyKHz rounds to the nearest kHz`() {
        // Truncation would yield 512001; rounding restores the representable 512002.
        assertEquals(512_002u, testPreset(frequencyMHz = 512.002, bandwidthKHz = 62.5).frequencyKHz)
    }

    @Test
    fun `bandwidthHz rounds to the nearest Hz`() {
        assertEquals(62_501u, testPreset(frequencyMHz = 915.0, bandwidthKHz = 62.501).bandwidthHz)
    }
}

/** Catalog additions synced from upstream (`e02cebe9`, `ae81a50c`, `08531d84`). */
class RadioPresetsCatalogTest {
    private fun region(country: String, admin: String? = null) =
        RegionSelection(countryCode = country, administrativeAreaCode = admin, countyKey = null, source = RegionSelection.Source.MANUAL)

    private fun preset(id: String) = RadioPresets.all.first { it.id == id }

    @Test
    fun `ids are unique`() {
        val ids = RadioPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `new country presets are recommended for their countries`() {
        assertEquals("hu", RadioPresets.recommended(region("HU"))?.id)
        assertEquals("sk", RadioPresets.recommended(region("SK"))?.id)
        assertEquals("cr", RadioPresets.recommended(region("CR"))?.id)
        assertEquals("ca", RadioPresets.recommended(region("CA"))?.id)
        assertEquals("us-ca", RadioPresets.recommended(region("US"))?.id)
    }

    @Test
    fun `the Netherlands recommends nl while Limburg stays an alternative`() {
        assertEquals("nl", RadioPresets.recommended(region("NL"))?.id)
        assertTrue(RadioPresets.presets(region("NL")).any { it.id == "nl-li" })
    }

    @Test
    fun `LVMesh is recommended in PA and NJ only`() {
        assertEquals("lvmesh", RadioPresets.recommended(region("US", "US-PA"))?.id)
        assertEquals("lvmesh", RadioPresets.recommended(region("US", "US-NJ"))?.id)
        assertEquals("us-ca", RadioPresets.recommended(region("US", "US-NY"))?.id)
    }

    @Test
    fun `sub-region presets are selectable only inside their areas`() {
        assertTrue(RadioPresets.isSelectable(preset("lvmesh"), region("US", "US-PA")))
        assertFalse(RadioPresets.isSelectable(preset("lvmesh"), region("US", "US-NY")))
        assertFalse(RadioPresets.isSelectable(preset("lvmesh"), null))
        assertTrue(RadioPresets.isSelectable(preset("au-qld"), region("AU", "AU-QLD")))
        assertFalse(RadioPresets.isSelectable(preset("au-qld"), region("AU", "AU-VIC")))
    }

    @Test
    fun `visiblePresets with no region hides gated presets unless active`() {
        val ids = RadioPresets.visiblePresets(region = null, activeID = null).map { it.id }
        assertFalse("wcmesh" in ids)
        assertFalse("lvmesh" in ids)
        assertTrue("wcmesh" in RadioPresets.visiblePresets(region = null, activeID = "wcmesh").map { it.id })
    }

    @Test
    fun `visiblePresets narrows to the region's country`() {
        val ids = RadioPresets.visiblePresets(region("NL"), activeID = null).map { it.id }
        assertEquals(setOf("nl", "nl-li"), ids.toSet())
    }

    @Test
    fun `visiblePresets appends an out-of-region active preset once`() {
        val ids = RadioPresets.visiblePresets(region("NL"), activeID = "hu").map { it.id }
        assertEquals(1, ids.count { it == "hu" })
        assertEquals("hu", ids.last())
    }

    @Test
    fun `visiblePresets falls back to the locale list for a region with no presets`() {
        assertTrue(RadioPresets.visiblePresets(region("MX"), activeID = null).isNotEmpty())
    }

    @Test
    fun `pathHashMode is the hash size minus one`() {
        assertEquals(1u.toUByte(), preset("hu").pathHashMode)
        assertEquals(2u.toUByte(), preset("ca").pathHashMode)
        assertEquals(0u.toUByte(), preset("nz-lr").pathHashMode)
        assertNull(preset("us-ca").pathHashMode)
    }

    @Test
    fun `Costa Rica and Slovakia resolve to their continents`() {
        assertEquals(RadioRegion.NORTH_AMERICA, RegionalAreas.continents["CR"])
        assertEquals(RadioRegion.EUROPE, RegionalAreas.continents["SK"])
    }
}

class RegionSelectionTest {
    private fun sel(country: String, admin: String? = null, source: RegionSelection.Source = RegionSelection.Source.LOCATION) =
        RegionSelection(countryCode = country, administrativeAreaCode = admin, source = source)

    @Test
    fun `choosing the already selected country writes nothing`() {
        assertNull(RegionSelection.afterChoosingCountry("US", sel("US")))
    }

    @Test
    fun `choosing a new country resets to a manual selection without a subdivision`() {
        val next = RegionSelection.afterChoosingCountry("CA", sel("US", "US-CA"))
        assertEquals(sel("CA", source = RegionSelection.Source.MANUAL), next)
    }

    @Test
    fun `choosing a subdivision needs a current selection and a different value`() {
        assertNull(RegionSelection.afterChoosingSubdivision("US-CA", null))
        assertNull(RegionSelection.afterChoosingSubdivision("US-CA", sel("US", "US-CA")))
        assertEquals(
            sel("US", "US-PA", RegionSelection.Source.MANUAL),
            RegionSelection.afterChoosingSubdivision("US-PA", sel("US", "US-CA")),
        )
    }

    @Test
    fun `codec round-trips every field and rejects malformed input`() {
        val full = RegionSelection("US", "US-CA", "los angeles", RegionSelection.Source.LOCATION)
        assertEquals(full, RegionSelectionCodec.decode(RegionSelectionCodec.encode(full)))
        val bare = sel("HU", source = RegionSelection.Source.MANUAL)
        assertEquals(bare, RegionSelectionCodec.decode(RegionSelectionCodec.encode(bare)))
        assertNull(RegionSelectionCodec.decode(null))
        assertNull(RegionSelectionCodec.decode("US|||NOPE"))
        assertNull(RegionSelectionCodec.decode("|||MANUAL"))
        assertNull(RegionSelectionCodec.decode("garbage"))
    }
}
