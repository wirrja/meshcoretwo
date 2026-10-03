// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import android.content.Context
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Asks each of [services] in turn and returns the first answer. Not a port: iOS has one provider.
 * Which elevation host is reachable depends on the network (see [TerrainTileElevationService]),
 * so the provider that answered last is asked first next time: a blocked host then costs one
 * timeout per process, not one per request. When every provider fails, the last error is thrown.
 */
class FallbackElevationService(private val services: List<ElevationService>) : ElevationService {
    init {
        require(services.isNotEmpty())
    }

    @Volatile
    private var preferredIndex = 0

    override suspend fun fetchElevation(coordinate: GeoCoordinate): Double = firstAnswer { it.fetchElevation(coordinate) }

    override suspend fun fetchElevations(path: List<GeoCoordinate>): List<ElevationSample> = firstAnswer { it.fetchElevations(path) }

    private suspend fun <T> firstAnswer(request: suspend (ElevationService) -> T): T {
        val start = preferredIndex
        var lastError: Exception? = null
        for (offset in services.indices) {
            val index = (start + offset) % services.size
            try {
                return request(services[index]).also { preferredIndex = index }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw checkNotNull(lastError)
    }

    companion object {
        @Volatile
        private var shared: ElevationService? = null

        /**
         * The app's elevation provider: Mapterhorn tiles via VersaTiles, then Open-Meteo. One per
         * process, so the decoded-tile cache and the last working provider outlive a screen.
         */
        fun shared(context: Context): ElevationService = shared ?: synchronized(this) {
            shared ?: FallbackElevationService(
                listOf(
                    TerrainTileElevationService(cacheDir = File(context.applicationContext.cacheDir, "elevation-tiles")),
                    OpenMeteoElevationService(),
                ),
            ).also { shared = it }
        }
    }
}
