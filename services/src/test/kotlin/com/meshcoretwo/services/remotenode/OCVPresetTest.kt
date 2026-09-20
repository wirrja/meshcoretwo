// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from `OCVPresetTests.swift`. */
class OCVPresetTest {
    private val nonCustomPresets = OCVPreset.entries.filter { it != OCVPreset.CUSTOM }

    @Test
    fun `All presets have exactly 11 values`() {
        for (preset in nonCustomPresets) {
            assertEquals("Preset $preset should have 11 values", 11, preset.ocvArray.size)
        }
    }

    @Test
    fun `All preset arrays are descending`() {
        for (preset in nonCustomPresets) {
            val array = preset.ocvArray
            for (i in 0 until array.size - 1) {
                assertTrue("Preset $preset should be descending at index $i", array[i] > array[i + 1])
            }
        }
    }

    @Test
    fun `All presets fit within UI validation range`() {
        for (preset in nonCustomPresets) {
            for (value in preset.ocvArray) {
                assertTrue("Preset $preset has value $value outside ${OCVPreset.VALID_MILLIVOLT_RANGE}", value in OCVPreset.VALID_MILLIVOLT_RANGE)
            }
        }
    }

    @Test
    fun `All presets have display names`() {
        for (preset in OCVPreset.entries) {
            assertTrue("Preset $preset should have a display name", preset.displayName.isNotEmpty())
        }
    }

    @Test
    fun `Selectable presets excludes custom`() {
        assertFalse(OCVPreset.CUSTOM in OCVPreset.selectablePresets)
        assertEquals(OCVPreset.entries.size - 1, OCVPreset.selectablePresets.size)
    }

    @Test
    fun `Li-Ion preset has expected values`() {
        val expected = listOf(4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100)
        assertEquals(expected, OCVPreset.LI_ION.ocvArray)
    }

    @Test
    fun `WisMesh Tag preset has expected values`() {
        val expected = listOf(4160, 4020, 3940, 3870, 3810, 3760, 3740, 3720, 3680, 3620, 2990)
        assertEquals(expected, OCVPreset.WIS_MESH_TAG.ocvArray)
    }

    @Test
    fun `LilyGo T-Beam 1W preset has expected values`() {
        val expected = listOf(7950, 7850, 7750, 7580, 7440, 7310, 7150, 7005, 6860, 6685, 6000)
        assertEquals(expected, OCVPreset.LILY_GO_TBEAM_1W.ocvArray)
    }

    @Test
    fun `ThinkNode M6 preset has expected values`() {
        val expected = listOf(4080, 3990, 3935, 3880, 3825, 3770, 3715, 3660, 3605, 3550, 3450)
        assertEquals(expected, OCVPreset.THINK_NODE_M6.ocvArray)
    }

    // MARK: - Category Tests

    @Test
    fun `Battery chemistry presets include only chemistry types`() {
        val presets = OCVPreset.batteryChemistryPresets

        assertTrue(OCVPreset.LI_ION in presets)
        assertTrue(OCVPreset.LI_FE_PO4 in presets)
        assertTrue(OCVPreset.LEAD_ACID in presets)
        assertTrue(OCVPreset.ALKALINE in presets)
        assertTrue(OCVPreset.NI_MH in presets)
        assertTrue(OCVPreset.LTO in presets)
        assertEquals(6, presets.size)
    }

    @Test
    fun `Battery chemistry presets exclude device-specific presets`() {
        val presets = OCVPreset.batteryChemistryPresets

        assertFalse(OCVPreset.TRACKER_T1000E in presets)
        assertFalse(OCVPreset.HELTEC_POCKET_5000 in presets)
        assertFalse(OCVPreset.CUSTOM in presets)
    }

    @Test
    fun `Li-Ion is battery chemistry category`() {
        assertEquals(OCVPresetCategory.BATTERY_CHEMISTRY, OCVPreset.LI_ION.category)
    }

    @Test
    fun `Tracker T1000-E is device specific category`() {
        assertEquals(OCVPresetCategory.DEVICE_SPECIFIC, OCVPreset.TRACKER_T1000E.category)
    }

    @Test
    fun `Custom is device specific category`() {
        assertEquals(OCVPresetCategory.DEVICE_SPECIFIC, OCVPreset.CUSTOM.category)
    }

    @Test
    fun `nodePresets adds the solar node preset to battery chemistry presets`() {
        val presets = OCVPreset.nodePresets

        assertEquals(OCVPreset.batteryChemistryPresets + OCVPreset.SEEED_SOLAR_NODE, presets)
    }

    // MARK: - Manufacturer Matching Tests

    @Test
    fun `Seeed Tracker T1000-e maps to trackerT1000E preset`() {
        assertEquals(OCVPreset.TRACKER_T1000E, OCVPreset.preset(forManufacturer = "Seeed Tracker T1000-e"))
        assertEquals(OCVPreset.TRACKER_T1000E, OCVPreset.preset(forManufacturer = "Seeed Tracker T1000-E"))
    }

    @Test
    fun `Seeed Wio Tracker L1 maps to seeedWioTracker preset`() {
        assertEquals(OCVPreset.SEEED_WIO_TRACKER, OCVPreset.preset(forManufacturer = "Seeed Wio Tracker L1"))
    }

    @Test
    fun `Seeed SenseCap Solar maps to seeedSolarNode preset`() {
        assertEquals(OCVPreset.SEEED_SOLAR_NODE, OCVPreset.preset(forManufacturer = "Seeed SenseCap Solar"))
    }

    @Test
    fun `RAK WisMesh Tag maps to wisMeshTag preset`() {
        assertEquals(OCVPreset.WIS_MESH_TAG, OCVPreset.preset(forManufacturer = "RAK WisMesh Tag"))
    }

    @Test
    fun `LilyGo T-Beam 1W maps to lilyGoTBeam1W preset`() {
        assertEquals(OCVPreset.LILY_GO_TBEAM_1W, OCVPreset.preset(forManufacturer = "LilyGo T-Beam 1W"))
    }

    @Test
    fun `Elecrow ThinkNode M6 maps to thinkNodeM6 preset`() {
        assertEquals(OCVPreset.THINK_NODE_M6, OCVPreset.preset(forManufacturer = "Elecrow ThinkNode M6"))
    }

    @Test
    fun `Unknown manufacturer returns null`() {
        assertNull(OCVPreset.preset(forManufacturer = "Generic ESP32"))
        assertNull(OCVPreset.preset(forManufacturer = "Heltec MeshPocket"))
        assertNull(OCVPreset.preset(forManufacturer = ""))
    }

    @Test
    fun `Manufacturer matching is case-sensitive`() {
        assertNull(OCVPreset.preset(forManufacturer = "seeed tracker t1000-e"))
        assertNull(OCVPreset.preset(forManufacturer = "SEEED TRACKER T1000-E"))
    }

    @Test
    fun `rawValue round-trips through fromRawValue`() {
        for (preset in OCVPreset.entries) {
            assertEquals(preset, OCVPreset.fromRawValue(preset.rawValue))
        }
        assertNull(OCVPreset.fromRawValue("not-a-real-preset"))
    }
}
