// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

/** Reverse-geocoding result, trimmed to the fields [RegionResolver] needs. */
data class GeocodeResult(
    /** ISO-3166 alpha-2 country code. */
    val countryCode: String?,
    /** State/province name as returned by the geocoder (not normalized). */
    val administrativeArea: String?,
    /** County/borough name as returned by the geocoder (not normalized). */
    val subAdministrativeArea: String?,
)

/** Reverse-geocodes a coordinate into [GeocodeResult]. Abstracted so [RegionResolver] is testable without a real geocoder backend. */
interface RegionGeocoder {
    /** Returns null on any failure (no backend, no result, offline) — [RegionResolver] falls back to the manual picker either way. */
    suspend fun reverseGeocode(latitude: Double, longitude: Double): GeocodeResult?
}
