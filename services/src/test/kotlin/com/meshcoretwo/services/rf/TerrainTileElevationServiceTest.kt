// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TerrainTileElevationServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Tiny tiles so that tile edges are easy to hit; z is fixed by the zoom under test. */
    private val source = TerrainTileSource(
        id = "test",
        urlTemplate = "tile:{z}/{x}/{y}",
        tileSize = 4,
        maxZoom = 12,
        dataSource = ElevationSource.MAPTERHORN,
    )

    private val downloads = mutableListOf<String>()

    /** Every pixel of tile (z, x, y) is `x * 100 + y` meters; the URL is the payload. */
    private val fakeDownload: suspend (String) -> ByteArray = { url ->
        downloads += url
        url.toByteArray()
    }

    private val fakeDecode: (ByteArray) -> TerrariumTile = { bytes ->
        val (_, x, y) = String(bytes).removePrefix("tile:").split("/").map { it.toInt() }
        TerrariumTile(source.tileSize, FloatArray(source.tileSize * source.tileSize) { (x * 100 + y).toFloat() })
    }

    private fun service(
        cacheDir: File? = null,
        maxCacheBytes: Long = TerrainTileElevationService.DEFAULT_MAX_CACHE_BYTES,
        download: suspend (String) -> ByteArray = fakeDownload,
        decode: (ByteArray) -> TerrariumTile = fakeDecode,
    ) = TerrainTileElevationService(cacheDir, source, maxCacheBytes, download, decode)

    /** Longitude of the left edge of tile column [x] at zoom [z]. */
    private fun tileLeftLongitude(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0

    /** Latitude of the top edge of tile row [y] at zoom [z]. */
    private fun tileTopLatitude(y: Double, z: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * y / (1 shl z)
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }

    @Test
    fun `fromArgb decodes terrarium RGB`() {
        val pixels = intArrayOf(
            0xFF800000.toInt(), // 128·256 − 32768 = 0 m
            0xFF806480.toInt(), // + 100 + 0.5 = 100.5 m
            0xFF7FFF00.toInt(), // 127·256 + 255 − 32768 = −1 m
            0x00800A00, // alpha ignored: 10 m
        )
        val tile = TerrariumTile.fromArgb(2, pixels)
        assertEquals(0f, tile.elevationAt(0, 0), 0f)
        assertEquals(100.5f, tile.elevationAt(1, 0), 0f)
        assertEquals(-1f, tile.elevationAt(0, 1), 0f)
        assertEquals(10f, tile.elevationAt(1, 1), 0f)
    }

    @Test
    fun `point in the middle of a tile reads that tile at max zoom`() = runTest {
        val coordinate = GeoCoordinate(tileTopLatitude(1280.5, 12), tileLeftLongitude(2476.5, 12))
        val elevation = service().fetchElevation(coordinate)
        assertEquals(2476 * 100.0 + 1280, elevation, 1e-6)
        assertEquals(listOf("tile:12/2476/1280"), downloads)
    }

    @Test
    fun `point on a tile edge interpolates across both tiles`() = runTest {
        val coordinate = GeoCoordinate(tileTopLatitude(1280.5, 12), tileLeftLongitude(2477.0, 12))
        val elevation = service().fetchElevation(coordinate)
        // Halfway between the last column of tile 2476 and the first of tile 2477.
        assertEquals((2476 * 100.0 + 1280 + 2477 * 100.0 + 1280) / 2, elevation, 1e-3)
        assertEquals(setOf("tile:12/2476/1280", "tile:12/2477/1280"), downloads.toSet())
    }

    @Test
    fun `decoded tiles are reused from memory`() = runTest {
        val service = service()
        val coordinate = GeoCoordinate(tileTopLatitude(1280.5, 12), tileLeftLongitude(2476.5, 12))
        service.fetchElevation(coordinate)
        service.fetchElevation(coordinate)
        assertEquals(1, downloads.size)
    }

    @Test
    fun `disk cache serves a new instance without downloading`() = runTest {
        val dir = temp.newFolder()
        val coordinate = GeoCoordinate(tileTopLatitude(1280.5, 12), tileLeftLongitude(2476.5, 12))
        service(cacheDir = dir).fetchElevation(coordinate)
        val elevation = service(cacheDir = dir).fetchElevation(coordinate)
        assertEquals(2476 * 100.0 + 1280, elevation, 1e-6)
        assertEquals(1, downloads.size)
        assertTrue(File(dir, "test/12-2476-1280.tile").isFile)
    }

    @Test
    fun `disk cache drops the oldest tiles past its limit`() = runTest {
        val dir = temp.newFolder()
        val payloadSize = "tile:12/2476/1280".length.toLong()
        val service = service(cacheDir = dir, maxCacheBytes = payloadSize * 2)
        for (x in 0 until 3) {
            service.fetchElevation(GeoCoordinate(tileTopLatitude(1280.5, 12), tileLeftLongitude(2476.5 + x, 12)))
            // lastModified has coarse resolution on some file systems.
            File(dir, "test/12-${2476 + x}-1280.tile").setLastModified(1_000L * (x + 1))
        }
        val remaining = File(dir, "test").listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("12-2477-1280.tile", "12-2478-1280.tile"), remaining)
    }

    @Test
    fun `undecodable cached tile is deleted and reported as invalid`() = runTest {
        val dir = temp.newFolder()
        File(dir, "test").mkdirs()
        val cached = File(dir, "test/12-2476-1280.tile").apply { writeText("garbage") }
        val failing = service(cacheDir = dir, decode = { throw IllegalStateException("bad image") })
        try {
            failing.fetchElevation(GeoCoordinate(tileTopLatitude(1280.5, 12), tileLeftLongitude(2476.5, 12)))
            fail("expected InvalidResponse")
        } catch (error: ElevationServiceError) {
            assertSame(ElevationServiceError.InvalidResponse, error)
        }
        assertFalse(cached.exists())
    }

    @Test
    fun `tile of the wrong size is rejected`() = runTest {
        val wrongSize = service(decode = { TerrariumTile(2, FloatArray(4)) })
        try {
            wrongSize.fetchElevation(GeoCoordinate(55.75, 37.62))
            fail("expected InvalidResponse")
        } catch (error: ElevationServiceError) {
            assertSame(ElevationServiceError.InvalidResponse, error)
        }
    }

    @Test
    fun `network errors propagate unchanged`() = runTest {
        val offline = service(download = { throw ElevationServiceError.NetworkError("no route") })
        try {
            offline.fetchElevation(GeoCoordinate(55.75, 37.62))
            fail("expected NetworkError")
        } catch (error: ElevationServiceError.NetworkError) {
            assertTrue(error.message!!.contains("no route"))
        }
    }

    @Test
    fun `profile samples carry distance and source`() = runTest {
        val a = GeoCoordinate(55.70, 37.50)
        val b = GeoCoordinate(55.80, 37.70)
        val path = OpenMeteoElevationService.sampleCoordinates(a, b, 20)
        val profile = service().fetchElevations(path)
        assertEquals(20, profile.size)
        assertEquals(0.0, profile.first().distanceFromAMeters, 0.0)
        assertEquals(RFCalculator.distance(a, b), profile.last().distanceFromAMeters, 1e-6)
        assertTrue(profile.all { it.source == ElevationSource.MAPTERHORN })
    }

    @Test
    fun `profile zoom follows sample spacing`() {
        val tiles512 = TerrainTileElevationService(null, source.copy(tileSize = 512), download = fakeDownload, decode = fakeDecode)
        val moscow = GeoCoordinate(55.75, 37.62)
        fun pathTo(kmEast: Double, samples: Int): List<GeoCoordinate> {
            val end = GeoCoordinate(moscow.latitude, moscow.longitude + kmEast / (111.32 * Math.cos(Math.toRadians(moscow.latitude))))
            return OpenMeteoElevationService.sampleCoordinates(moscow, end, samples)
        }
        // ~50 m spacing: z12 pixels are ~11 m here.
        assertEquals(12, tiles512.profileZoom(pathTo(1.0, 20)))
        // ~200 m spacing would allow z10 (~43 m pixels).
        assertEquals(10, tiles512.profileZoom(pathTo(20.0, 100)))
        // Long paths never go below z10.
        assertEquals(10, tiles512.profileZoom(pathTo(150.0, 100)))
        // A single point or a zero-length path reads at max zoom.
        assertEquals(12, tiles512.profileZoom(listOf(moscow)))
        assertEquals(12, tiles512.profileZoom(listOf(moscow, moscow)))
    }
}
