// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import com.meshcoretwo.services.utilities.HTTP_USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.tan

/** A Terrarium-encoded DEM tile set: where to fetch it and what it covers. */
data class TerrainTileSource(
    /** Subdirectory of the disk cache; change it when the URL starts serving different data. */
    val id: String,
    /** XYZ template with `{z}`, `{x}` and `{y}`. */
    val urlTemplate: String,
    /** Width and height of one tile in pixels. */
    val tileSize: Int,
    val maxZoom: Int,
    val dataSource: ElevationSource,
) {
    fun url(z: Int, x: Int, y: Int): String =
        urlTemplate.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    companion object {
        /**
         * Mapterhorn terrain tiles mirrored by VersaTiles (`tiles.versatiles.org`, the host of the
         * VersaTiles basemap): 512 px lossless WebP, z0–12, sea at 0 m. Mapterhorn's own endpoint
         * sits behind Cloudflare, which Russian ISPs throttle.
         */
        val versaTilesMapterhorn = TerrainTileSource(
            id = "mapterhorn-versatiles",
            urlTemplate = "https://tiles.versatiles.org/tiles/elevation/{z}/{x}/{y}",
            tileSize = 512,
            maxZoom = 12,
            dataSource = ElevationSource.MAPTERHORN,
        )
    }
}

/** One decoded DEM tile: [size]×[size] elevations in meters, row-major from the top-left. */
class TerrariumTile(val size: Int, private val elevations: FloatArray) {
    init {
        require(elevations.size == size * size) { "expected ${size * size} elevations, got ${elevations.size}" }
    }

    fun elevationAt(px: Int, py: Int): Float = elevations[py * size + px]

    companion object {
        /**
         * Decodes Terrarium RGB, `R·256 + G + B/256 − 32768` meters, from ARGB pixels as returned
         * by `Bitmap.getPixels`. Alpha is ignored.
         */
        fun fromArgb(size: Int, pixels: IntArray): TerrariumTile {
            val elevations = FloatArray(pixels.size)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                elevations[i] = r * 256f + g + b / 256f - 32768f
            }
            return TerrariumTile(size, elevations)
        }
    }
}

/** Decodes a tile image with [BitmapFactory], without premultiplied alpha or color conversion. */
object BitmapTerrariumDecoder : (ByteArray) -> TerrariumTile {
    override fun invoke(bytes: ByteArray): TerrariumTile {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw ElevationServiceError.InvalidResponse
        try {
            if (bitmap.width != bitmap.height) throw ElevationServiceError.InvalidResponse
            val size = bitmap.width
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            return TerrariumTile.fromArgb(size, pixels)
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * [ElevationService] that reads Terrarium DEM raster tiles instead of querying a point API. Not a
 * port: iOS has Open-Meteo only (`ElevationService.swift`). Open-Meteo is hosted at Hetzner, which
 * Russian ISPs throttle the same way as Cloudflare (PLAN.md Phase 49), so line of sight failed
 * there unless a VPN was on. The default [source] is on the same host as the VersaTiles basemap.
 *
 * Tiles are kept on disk under [cacheDir] (capped at [maxCacheBytes], oldest first out), so an
 * area analyzed once also works offline, and the last few decoded tiles stay in memory. A profile
 * is read at the zoom whose pixel is at most a quarter of its sample spacing ([profileZoom]);
 * single points at the source's highest zoom. Values are interpolated bilinearly, across tile
 * edges where needed.
 *
 * [download] and [decode] are injectable for tests; every failure surfaces as an
 * [ElevationServiceError], so [FallbackElevationService] can move on to the next provider.
 */
class TerrainTileElevationService(
    private val cacheDir: File?,
    private val source: TerrainTileSource = TerrainTileSource.versaTilesMapterhorn,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    private val download: suspend (String) -> ByteArray = ::httpGet,
    private val decode: (ByteArray) -> TerrariumTile = BitmapTerrariumDecoder,
) : ElevationService {
    private data class TileKey(val z: Int, val x: Int, val y: Int)

    /** A sample point in global pixel space: the top-left of its four neighbor pixels and the fractions toward the others. */
    private class Bilinear(val x0: Long, val y0: Long, val fx: Double, val fy: Double)

    private val memoryCache = object : LinkedHashMap<TileKey, TerrariumTile>(MEMORY_TILES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, TerrariumTile>?): Boolean = size > MEMORY_TILES
    }

    private val tileDir: File? get() = cacheDir?.let { File(it, source.id) }

    override suspend fun fetchElevation(coordinate: GeoCoordinate): Double =
        sample(listOf(coordinate), source.maxZoom).first()

    override suspend fun fetchElevations(path: List<GeoCoordinate>): List<ElevationSample> {
        if (path.isEmpty()) throw ElevationServiceError.NoData
        val elevations = sample(path, profileZoom(path))
        val start = path[0]
        return path.mapIndexed { index, coordinate ->
            ElevationSample(
                coordinate = coordinate,
                elevation = elevations[index],
                distanceFromAMeters = RFCalculator.distance(from = start, to = coordinate),
                source = source.dataSource,
            )
        }
    }

    /** Zoom for a profile: pixel at most 1/[PIXELS_PER_SAMPLE] of the sample spacing, within [MIN_PROFILE_ZOOM]..maxZoom. */
    internal fun profileZoom(path: List<GeoCoordinate>): Int {
        if (path.size < 2) return source.maxZoom
        val spacing = RFCalculator.distance(path.first(), path.last()) / (path.size - 1)
        if (spacing <= 0.0) return source.maxZoom
        val midLatitude = Math.toRadians((path.first().latitude + path.last().latitude) / 2)
        val metersPerPixelAtZoom0 = EARTH_CIRCUMFERENCE_METERS * cos(midLatitude) / source.tileSize
        val zoom = ceil(log2(metersPerPixelAtZoom0 * PIXELS_PER_SAMPLE / spacing)).toInt()
        return zoom.coerceIn(MIN_PROFILE_ZOOM, source.maxZoom)
    }

    private suspend fun sample(coordinates: List<GeoCoordinate>, zoom: Int): List<Double> {
        val tileSize = source.tileSize
        val worldPixels = tileSize.toLong() shl zoom
        val points = coordinates.map { coordinate ->
            // Pixel centers sit at +0.5, so shift by half a pixel before splitting into cell and fraction.
            val x = pixelX(coordinate.longitude, worldPixels) - 0.5
            val y = pixelY(coordinate.latitude, worldPixels) - 0.5
            val x0 = floor(x).toLong()
            val y0 = floor(y).toLong()
            Bilinear(x0, y0, x - x0, y - y0)
        }

        // Columns wrap around the antimeridian; rows clamp at the Mercator edge.
        fun wrapX(px: Long): Long = Math.floorMod(px, worldPixels)
        fun clampY(py: Long): Long = py.coerceIn(0L, worldPixels - 1)
        fun keyOf(px: Long, py: Long) = TileKey(zoom, (wrapX(px) / tileSize).toInt(), (clampY(py) / tileSize).toInt())

        val keys = points.flatMapTo(mutableSetOf()) { point ->
            listOf(
                keyOf(point.x0, point.y0),
                keyOf(point.x0 + 1, point.y0),
                keyOf(point.x0, point.y0 + 1),
                keyOf(point.x0 + 1, point.y0 + 1),
            )
        }
        val tiles = coroutineScope { keys.map { key -> async { key to loadTile(key) } }.awaitAll().toMap() }

        fun pixel(px: Long, py: Long): Double {
            val tile = tiles.getValue(keyOf(px, py))
            return tile.elevationAt((wrapX(px) % tileSize).toInt(), (clampY(py) % tileSize).toInt()).toDouble()
        }

        return points.map { point ->
            val top = pixel(point.x0, point.y0) * (1 - point.fx) + pixel(point.x0 + 1, point.y0) * point.fx
            val bottom = pixel(point.x0, point.y0 + 1) * (1 - point.fx) + pixel(point.x0 + 1, point.y0 + 1) * point.fx
            top * (1 - point.fy) + bottom * point.fy
        }
    }

    private suspend fun loadTile(key: TileKey): TerrariumTile {
        synchronized(memoryCache) { memoryCache[key] }?.let { return it }

        val cached = withContext(Dispatchers.IO) { readCachedTile(key) }
        val bytes = cached ?: download(source.url(key.z, key.x, key.y))
        val tile = try {
            withContext(Dispatchers.Default) { decode(bytes) }.also {
                if (it.size != source.tileSize) throw ElevationServiceError.InvalidResponse
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (cached != null) withContext(Dispatchers.IO) { cacheFile(key)?.delete() }
            throw error as? ElevationServiceError ?: ElevationServiceError.InvalidResponse
        }
        if (cached == null) withContext(Dispatchers.IO) { writeCachedTile(key, bytes) }

        synchronized(memoryCache) { memoryCache[key] = tile }
        return tile
    }

    private fun cacheFile(key: TileKey): File? = tileDir?.let { File(it, "${key.z}-${key.x}-${key.y}.tile") }

    private fun readCachedTile(key: TileKey): ByteArray? {
        val file = cacheFile(key)?.takeIf { it.isFile } ?: return null
        return try {
            file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
        } catch (error: IOException) {
            null
        }
    }

    /** Best effort: a failed write only means the next request downloads the tile again. */
    private fun writeCachedTile(key: TileKey, bytes: ByteArray) {
        val dir = tileDir ?: return
        val file = cacheFile(key) ?: return
        try {
            dir.mkdirs()
            val temp = File(dir, "${file.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) temp.delete()
            trimCache(dir)
        } catch (error: IOException) {
            // Cache is optional.
        }
    }

    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxCacheBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxCacheBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    companion object {
        private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686
        private const val PIXELS_PER_SAMPLE = 4.0
        private const val MIN_PROFILE_ZOOM = 10
        private const val MEMORY_TILES = 8
        private const val MAX_TILE_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_CACHE_BYTES = 64L * 1024 * 1024

        private fun pixelX(longitude: Double, worldPixels: Long): Double = (longitude + 180.0) / 360.0 * worldPixels

        private fun pixelY(latitude: Double, worldPixels: Long): Double {
            val radians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
            return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * worldPixels
        }

        /** GET with short timeouts: a throttled host should hand over to the next provider quickly. */
        private suspend fun httpGet(url: String): ByteArray = withContext(Dispatchers.IO) {
            val connection = try {
                (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", HTTP_USER_AGENT)
                }
            } catch (error: Exception) {
                throw ElevationServiceError.NetworkError(error.message ?: "unknown")
            }
            try {
                when (val status = connection.responseCode) {
                    200 -> connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            if (output.size() > MAX_TILE_BYTES) throw ElevationServiceError.InvalidResponse
                        }
                        output.toByteArray()
                    }
                    429 -> throw ElevationServiceError.RateLimited
                    else -> throw ElevationServiceError.ApiError("HTTP $status")
                }
            } catch (error: IOException) {
                throw ElevationServiceError.NetworkError(error.message ?: "unknown")
            } finally {
                connection.disconnect()
            }
        }
    }
}
