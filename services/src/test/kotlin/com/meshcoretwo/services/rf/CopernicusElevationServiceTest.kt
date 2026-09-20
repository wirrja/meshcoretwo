// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * The decoder is checked against tiles this test encodes itself, in the layout the Copernicus DEM
 * COGs use (little-endian TIFF, float32, DEFLATE + predictor 3, one padded tile) — no fixture files
 * from the dataset are committed. A real tile was additionally compared by hand with Open-Meteo.
 */
class CopernicusElevationServiceTest {
    /** 4x3 image in a 4x4 tile, tie point (37 E, 56 N) at pixel (0, 0), pixel size 0.25° x 0.5°. */
    private val values = floatArrayOf(
        100f, 110f, 120f, 130f,
        200f, 210f, 220f, 230f,
        300f, 310f, 320f, 330f,
    )

    private fun tile() = CopernicusDemTile.parse(encodeTile(values, imageWidth = 4, imageHeight = 3, tileSize = 4))

    @Test
    fun pixelCentresReturnTheirExactSample() {
        val tile = tile()
        assertEquals(100.0, tile.elevationAt(latitude = 56.0, longitude = 37.0), 1e-6)
        assertEquals(230.0, tile.elevationAt(latitude = 55.5, longitude = 37.75), 1e-6)
        assertEquals(320.0, tile.elevationAt(latitude = 55.0, longitude = 37.5), 1e-6)
    }

    @Test
    fun betweenSamplesIsBilinear() {
        val tile = tile()
        // Halfway along the top row, halfway between rows 1 and 2 at column 0, and the centre of a 2x2 block.
        assertEquals(105.0, tile.elevationAt(latitude = 56.0, longitude = 37.125), 1e-6)
        assertEquals(250.0, tile.elevationAt(latitude = 55.25, longitude = 37.0), 1e-6)
        assertEquals(155.0, tile.elevationAt(latitude = 55.75, longitude = 37.125), 1e-6)
    }

    @Test
    fun positionsPastTheEdgeAreClampedToTheEdgeSample() {
        val tile = tile()
        assertEquals(330.0, tile.elevationAt(latitude = 54.0, longitude = 38.5), 1e-6)
        assertEquals(100.0, tile.elevationAt(latitude = 57.0, longitude = 36.0), 1e-6)
    }

    @Test
    fun garbageIsAnInvalidResponse() {
        assertThrows(ElevationServiceError.InvalidResponse::class.java) { CopernicusDemTile.parse(ByteArray(64)) }
        val truncated = encodeTile(values, 4, 3, 4).copyOf(60)
        assertThrows(ElevationServiceError.InvalidResponse::class.java) { CopernicusDemTile.parse(truncated) }
    }

    @Test
    fun tileNamesFollowTheBucketConvention() {
        assertEquals("Copernicus_DSM_COG_30_N55_00_E037_00_DEM", CopernicusElevationService.tileName(55.7558, 37.6173))
        assertEquals("Copernicus_DSM_COG_30_S34_00_W071_00_DEM", CopernicusElevationService.tileName(-33.5, -70.7))
        assertEquals("Copernicus_DSM_COG_30_N00_00_E000_00_DEM", CopernicusElevationService.tileName(0.2, 0.9))
        assertEquals("Copernicus_DSM_COG_30_N56_00_E037_00_DEM", CopernicusElevationService.tileName(56.0, 37.999))
    }

    @Test
    fun fetchesEachTileOnceAndMeasuresDistanceFromTheStart() = runBlocking {
        val requested = mutableListOf<String>()
        val service = CopernicusElevationService(tileSource = { name ->
            requested += name
            encodeTile(values, 4, 3, 4)
        })
        val samples = service.fetchElevations(
            listOf(GeoCoordinate(56.0, 37.0), GeoCoordinate(56.0, 37.125), GeoCoordinate(55.5, 37.75)),
        )
        assertEquals(listOf(100.0, 105.0, 230.0), samples.map { it.elevation })
        assertEquals(0.0, samples[0].distanceFromAMeters, 1e-9)
        assertEquals(listOf("Copernicus_DSM_COG_30_N56_00_E037_00_DEM", "Copernicus_DSM_COG_30_N55_00_E037_00_DEM"), requested)
    }

    @Test
    fun aMissingTileIsSeaLevelAndIsNotRequestedAgain() = runBlocking {
        var requests = 0
        val service = CopernicusElevationService(tileSource = { requests++; null })
        assertEquals(0.0, service.fetchElevation(GeoCoordinate(40.5, -39.5)), 0.0)
        assertEquals(0.0, service.fetchElevation(GeoCoordinate(40.6, -39.6)), 0.0)
        assertEquals(1, requests)
    }

    @Test
    fun emptyPathIsNoData() {
        assertThrows(ElevationServiceError.NoData::class.java) {
            runBlocking { CopernicusElevationService(tileSource = { null }).fetchElevations(emptyList()) }
        }
    }

    /** Encodes [samples] (row-major, imageWidth x imageHeight) into a padded tileSize x tileSize block. */
    private fun encodeTile(samples: FloatArray, imageWidth: Int, imageHeight: Int, tileSize: Int): ByteArray {
        // Predictor 3: split each row into four byte planes (MSB first), then difference bytewise.
        val raw = ByteArray(tileSize * tileSize * 4)
        for (row in 0 until tileSize) {
            val base = row * tileSize * 4
            for (column in 0 until tileSize) {
                val value = if (row < imageHeight && column < imageWidth) samples[row * imageWidth + column] else 0f
                val bits = value.toRawBits()
                for (plane in 0 until 4) raw[base + plane * tileSize + column] = (bits ushr (24 - 8 * plane)).toByte()
            }
            for (index in tileSize * 4 - 1 downTo 1) raw[base + index] = (raw[base + index] - raw[base + index - 1]).toByte()
        }
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val block = compressed.toByteArray()

        // Layout: header(8) | block | IFD | out-of-line values (scale, tie point).
        val entries = 13
        val ifdOffset = 8 + block.size
        val extraOffset = ifdOffset + 2 + entries * 12 + 4
        val out = ByteBuffer.allocate(extraOffset + 24 + 48).order(ByteOrder.LITTLE_ENDIAN)
        out.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdOffset)
        out.put(block)
        out.putShort(entries.toShort())
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            out.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value)
        }
        entry(256, 4, 1, imageWidth)
        entry(257, 4, 1, imageHeight)
        entry(258, 3, 1, 32)
        entry(259, 3, 1, 8)
        entry(317, 3, 1, 3)
        entry(322, 4, 1, tileSize)
        entry(323, 4, 1, tileSize)
        entry(324, 4, 1, 8)
        entry(325, 4, 1, block.size)
        entry(339, 3, 1, 3)
        entry(33550, 12, 3, extraOffset)
        entry(33922, 12, 6, extraOffset + 24)
        entry(34735, 3, 1, 0)
        out.putInt(0)
        listOf(0.25, 0.5, 0.0).forEach { out.putDouble(it) }
        listOf(0.0, 0.0, 0.0, 37.0, 56.0, 0.0).forEach { out.putDouble(it) }
        return out.array()
    }
}
