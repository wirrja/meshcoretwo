// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapBaseStyle.styleBuilder] and [MapBaseStyle.firstTextFont] build and parse JSON with
 * `org.json`, which is only an Android stub on the unit-test classpath, so they are not covered
 * here.
 */
class MapBaseStyleTest {
    @Test
    fun `custom style URL is a vector source that allows offline packs`() {
        val style = checkNotNull(MapBaseStyle.custom("  https://maps.example.org/style.json  "))
        assertEquals("https://maps.example.org/style.json", style.styleUrl)
        assertEquals(style.styleUrl, style.offlineStyleUrl)
        assertEquals(style.styleUrl, style.probeUrl)
        assertNull("the font comes from the loaded style", style.labelFont)
    }

    @Test
    fun `custom template with z is a raster source without offline packs`() {
        val style = checkNotNull(MapBaseStyle.custom("https://tiles.example.org/{z}/{x}/{y}.png"))
        assertNull(style.styleUrl)
        assertEquals("https://tiles.example.org/{z}/{x}/{y}.png", style.rasterTileUrl)
        assertNull(style.offlineStyleUrl)
        assertEquals("https://tiles.example.org/12/2476/1280.png", style.probeUrl)
        assertEquals("noto_sans_regular", style.labelFont?.single())
    }

    @Test
    fun `custom URL must be http or https`() {
        assertNull(MapBaseStyle.custom(""))
        assertNull(MapBaseStyle.custom("maps.example.org/style.json"))
        assertNull(MapBaseStyle.custom("file:///sdcard/style.json"))
    }

    @Test
    fun `OpenStreetMap tiles are never downloaded for offline use`() {
        assertNull(MapBaseStyle.openStreetMap.offlineStyleUrl)
    }

    @Test
    fun `every auto candidate is a built-in style`() {
        MapTileProviderId.autoCandidates.forEach { assertNotNull(MapBaseStyle.builtIn(it)) }
    }

    /** Raster styles name this font stack; MapLibre fetches every range it may need from assets. */
    @Test
    fun `bundled glyphs cover all 256 ranges`() {
        val dir = File("src/main/assets/glyphs/noto_sans_regular")
        val ranges = (0 until 256).map { "${it * 256}-${it * 256 + 255}.pbf" }
        ranges.forEach { assertTrue("missing $it", File(dir, it).isFile) }
        assertEquals(256, dir.listFiles().orEmpty().size)
    }
}
