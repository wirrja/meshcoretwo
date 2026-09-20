// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.location.LocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Resolves a [RegionSelection] from the device's current location. Ported from
 * `RegionResolver.swift`, adapted from `@MainActor`/`CheckedContinuation` to plain suspend
 * functions — Android has no UI-thread affinity requirement for this kind of one-shot
 * orchestration, unlike iOS's `CLLocationManagerDelegate`.
 *
 * Lives in `services` (unlike iOS, where the equivalent sits in `MC1` next to `LocationService`
 * because `MC1Services` is intentionally CoreLocation-free) because `LocationManager`/`Geocoder`
 * are plain Android framework APIs `services` is already allowed to depend on — see project constraints.
 */
class RegionResolver(
    private val location: LocationProvider,
    private val geocoder: RegionGeocoder,
) {
    private val cache = mutableMapOf<CacheKey, CachedResult>()

    /**
     * Returns a [RegionSelection] derived from the device's current location, or null for any
     * failure (not authorized, timeout, no geocoder result). Failure modes are silent — callers
     * fall through to the manual picker.
     */
    suspend fun resolve(): RegionSelection? {
        if (!location.isAuthorized) return null
        return try {
            val fix = location.requestCurrentLocation(LOCATION_TIMEOUT_MS)
            val key = CacheKey(fix)
            cache[key]?.takeIf { it.isFresh() }?.let { return it.value }

            val result = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) { geocoder.reverseGeocode(fix.latitude, fix.longitude) }
            val countryCode = result?.countryCode ?: return null

            val normalizedAdmin = result.administrativeArea?.let(::normalize)
            val normalizedCounty = result.subAdministrativeArea?.let(::normalize)?.replace(COUNTY_SUFFIX, "")

            val adminCode = RegionalAreas.matchSubdivision(country = countryCode, normalized = normalizedAdmin)
            val countyKey = RegionalAreas.matchCounty(country = countryCode, state = adminCode, normalized = normalizedCounty)

            val selection = RegionSelection(
                countryCode = countryCode,
                administrativeAreaCode = adminCode,
                countyKey = countyKey,
                source = RegionSelection.Source.LOCATION,
            )
            cache[key] = CachedResult(selection, Instant.now().plus(CACHE_TTL_HOURS, ChronoUnit.HOURS))
            selection
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    private fun normalize(value: String): String {
        val folded = Normalizer.normalize(value.trim().lowercase(GEOCODING_LOCALE), Normalizer.Form.NFD)
        return folded.replace(DIACRITIC_MARKS, "")
    }

    private data class CacheKey(val lat: Int, val lng: Int) {
        constructor(fix: LocationFix) : this(fix.latitude.roundToInt(), fix.longitude.roundToInt())
    }

    private data class CachedResult(val value: RegionSelection, val expiresAt: Instant) {
        fun isFresh(): Boolean = Instant.now().isBefore(expiresAt)
    }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 5_000L
        const val GEOCODE_TIMEOUT_MS = 5_000L
        const val CACHE_TTL_HOURS = 24L
        const val COUNTY_SUFFIX = " county"
        val GEOCODING_LOCALE: Locale = Locale.US
        val DIACRITIC_MARKS = Regex("\\p{Mn}+")
    }
}
