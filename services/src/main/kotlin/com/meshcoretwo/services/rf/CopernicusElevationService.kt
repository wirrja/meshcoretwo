// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * [ElevationService] that reads the Copernicus DEM GLO-90 (90 m) directly from its public Cloud
 * Optimized GeoTIFF tiles on AWS Open Data — no API key, no intermediary service, no third-party
 * library (see [CopernicusDemTile]). Each 1°×1° tile is one ~3–6 MB download, kept in memory for
 * the next samples; a 20 km path touches one or two tiles, so the whole Line of Sight run costs a
 * couple of requests instead of Open-Meteo's rate-limited batches.
 *
 * Ocean areas have no tile in the bucket (the dataset's documented convention is height 0 there),
 * so a 404 is treated as sea level.
 *
 * Data licence and required notice: see [ATTRIBUTION] and `THIRD_PARTY_NOTICES.md`.
 *
 * [tileSource] returns a tile's file bytes for its bucket object name, or `null` for "no such tile";
 * replaced in tests.
 */
class CopernicusElevationService(
    private val tileSource: suspend (tileName: String) -> ByteArray? = ::downloadTile,
) : ElevationService {
    /** `null` marks a tile the bucket doesn't have (open ocean): elevation 0 m. */
    private val cache = object : LinkedHashMap<String, CopernicusDemTile?>(MAX_CACHED_TILES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CopernicusDemTile?>): Boolean = size > MAX_CACHED_TILES
    }
    private val cacheLock = Mutex()

    override suspend fun fetchElevation(coordinate: GeoCoordinate): Double =
        fetchElevations(listOf(coordinate)).firstOrNull()?.elevation ?: throw ElevationServiceError.NoData

    override suspend fun fetchElevations(path: List<GeoCoordinate>): List<ElevationSample> {
        if (path.isEmpty()) throw ElevationServiceError.NoData
        val start = path[0]
        return path.map { coordinate ->
            ElevationSample(
                coordinate = coordinate,
                elevation = elevationAt(coordinate),
                distanceFromAMeters = RFCalculator.distance(from = start, to = coordinate),
            )
        }
    }

    private suspend fun elevationAt(coordinate: GeoCoordinate): Double {
        val tile = tile(tileName(coordinate.latitude, coordinate.longitude)) ?: return 0.0
        return tile.elevationAt(coordinate.latitude, coordinate.longitude)
    }

    private suspend fun tile(name: String): CopernicusDemTile? = cacheLock.withLock {
        if (cache.containsKey(name)) return@withLock cache[name]
        val parsed = tileSource(name)?.let { CopernicusDemTile.parse(it) }
        cache[name] = parsed
        parsed
    }

    companion object {
        /** Notice the Copernicus DEM licence requires when the data is adapted (here: sampled and interpolated). */
        const val ATTRIBUTION =
            "Produced using Copernicus WorldDEM-90 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 " +
                "provided under COPERNICUS by the European Union and ESA; all rights reserved"

        private const val BUCKET_URL = "https://copernicus-dem-90m.s3.amazonaws.com"
        private const val MAX_CACHED_TILES = 4
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000

        /** Bucket object name of the 1°×1° tile containing the point, e.g. `Copernicus_DSM_COG_30_N55_00_E037_00_DEM`. */
        fun tileName(latitude: Double, longitude: Double): String {
            val lat = floor(latitude).toInt()
            val lon = floor(longitude).toInt()
            return String.format(
                Locale.ROOT,
                "Copernicus_DSM_COG_30_%s%02d_00_%s%03d_00_DEM",
                if (lat >= 0) "N" else "S", abs(lat),
                if (lon >= 0) "E" else "W", abs(lon),
            )
        }

        private suspend fun downloadTile(name: String): ByteArray? = withContext(Dispatchers.IO) {
            val connection = try {
                (URI("$BUCKET_URL/$name/$name.tif").toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }
            } catch (error: Exception) {
                throw ElevationServiceError.NetworkError(error.message ?: "unknown")
            }
            try {
                when (val status = connection.responseCode) {
                    200 -> connection.inputStream.use { it.readBytes() }
                    // S3 answers 403 (not 404) for a missing key when the bucket forbids listing.
                    403, 404 -> null
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
