// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * [ElevationService] backed by the Open-Meteo elevation API (plain HTTPS REST, no API key, no
 * Google/GMS dependency — ported from `ElevationService.swift`'s `actor ElevationService`).
 * Uses [java.net.HttpURLConnection] and `org.json` rather than adding an HTTP client dependency —
 * same "no new dependency for one simple call" call already made for
 * [com.meshcoretwo.services.InlineImageDimensionsStore]'s JSON handling.
 */
class OpenMeteoElevationService : ElevationService {
    override suspend fun fetchElevation(coordinate: GeoCoordinate): Double {
        val samples = fetchElevations(listOf(coordinate))
        return samples.firstOrNull()?.elevation ?: throw ElevationServiceError.NoData
    }

    override suspend fun fetchElevations(path: List<GeoCoordinate>): List<ElevationSample> {
        if (path.isEmpty()) throw ElevationServiceError.NoData

        val coordinatesToFetch = if (path.size > MAX_POINTS_PER_REQUEST) {
            subsample(path, MAX_POINTS_PER_REQUEST)
        } else {
            path
        }

        val url = buildUrl(coordinatesToFetch)
        val data = performRequest(url)
        val elevations = parseElevationResponse(data)

        if (elevations.size != coordinatesToFetch.size) throw ElevationServiceError.InvalidResponse

        val startCoordinate = coordinatesToFetch[0]
        return coordinatesToFetch.mapIndexed { index, coordinate ->
            ElevationSample(
                coordinate = coordinate,
                elevation = elevations[index],
                distanceFromAMeters = RFCalculator.distance(from = startCoordinate, to = coordinate),
            )
        }
    }

    private fun buildUrl(coordinates: List<GeoCoordinate>): String {
        fun format(value: Double) = String.format(Locale.ROOT, "%.6f", value)
        val latitudes = coordinates.joinToString(",") { format(it.latitude) }
        val longitudes = coordinates.joinToString(",") { format(it.longitude) }
        val encodedLat = URLEncoder.encode(latitudes, "UTF-8")
        val encodedLon = URLEncoder.encode(longitudes, "UTF-8")
        return "$API_ENDPOINT?latitude=$encodedLat&longitude=$encodedLon"
    }

    /** Performs an HTTP request with exponential backoff retry on HTTP 429 (rate limit). */
    private suspend fun performRequest(url: String): String = withContext(Dispatchers.IO) {
        repeat(MAX_RETRIES) { attempt ->
            val connection = try {
                (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }
            } catch (error: Exception) {
                throw ElevationServiceError.NetworkError(error.message ?: "unknown")
            }

            try {
                val statusCode = connection.responseCode

                if (statusCode == 429) {
                    connection.disconnect()
                    val delayMs = BASE_RETRY_DELAY_MS * (1L shl attempt)
                    delay(delayMs)
                    return@repeat
                }

                if (statusCode != 200) throw ElevationServiceError.ApiError("HTTP $statusCode")

                return@withContext connection.inputStream.bufferedReader().use { it.readText() }
            } catch (error: IOException) {
                throw ElevationServiceError.NetworkError(error.message ?: "unknown")
            } finally {
                connection.disconnect()
            }
        }

        throw ElevationServiceError.RateLimited
    }

    private fun parseElevationResponse(data: String): List<Double> {
        val elevations = try {
            JSONObject(data).getJSONArray("elevation")
        } catch (error: Exception) {
            throw ElevationServiceError.InvalidResponse
        }
        return (0 until elevations.length()).map { elevations.getDouble(it) }
    }

    /** Subsamples a list to a target count, preserving the first and last elements. */
    private fun <T> subsample(list: List<T>, targetCount: Int): List<T> {
        if (list.size <= targetCount || targetCount < 2) return list

        val result = mutableListOf<T>()
        result.add(list[0])

        val middleCount = targetCount - 2
        if (middleCount > 0) {
            val step = (list.size - 2).toDouble() / (middleCount + 1)
            for (i in 1..middleCount) {
                result.add(list[(i * step).toInt()])
            }
        }

        result.add(list[list.size - 1])
        return result
    }

    companion object {
        private const val API_ENDPOINT = "https://api.open-meteo.com/v1/elevation"
        private const val MAX_POINTS_PER_REQUEST = 100
        private const val MAX_RETRIES = 3
        private const val BASE_RETRY_DELAY_MS = 500L

        private const val THRESHOLD_UNDER_1KM = 1000.0
        private const val THRESHOLD_1_TO_5KM = 5000.0
        private const val THRESHOLD_5_TO_20KM = 20000.0

        private const val SAMPLE_COUNT_UNDER_1KM = 20
        private const val SAMPLE_COUNT_1_TO_5KM = 50
        private const val SAMPLE_COUNT_5_TO_20KM = 80
        private const val SAMPLE_COUNT_OVER_20KM = 100

        /** Recommends a sample count for a path of the given length. */
        fun optimalSampleCount(distanceMeters: Double): Int = when {
            distanceMeters < THRESHOLD_UNDER_1KM -> SAMPLE_COUNT_UNDER_1KM
            distanceMeters < THRESHOLD_1_TO_5KM -> SAMPLE_COUNT_1_TO_5KM
            distanceMeters < THRESHOLD_5_TO_20KM -> SAMPLE_COUNT_5_TO_20KM
            else -> SAMPLE_COUNT_OVER_20KM
        }

        /** Generates `sampleCount` evenly spaced coordinates between two points, including both endpoints. */
        fun sampleCoordinates(from: GeoCoordinate, to: GeoCoordinate, sampleCount: Int): List<GeoCoordinate> {
            val count = sampleCount.coerceIn(2, MAX_POINTS_PER_REQUEST)

            val coordinates = mutableListOf<GeoCoordinate>()
            for (i in 0 until count) {
                val fraction = i.toDouble() / (count - 1)
                coordinates.add(
                    GeoCoordinate(
                        latitude = from.latitude + fraction * (to.latitude - from.latitude),
                        longitude = from.longitude + fraction * (to.longitude - from.longitude),
                    ),
                )
            }
            return coordinates
        }
    }
}
