// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/**
 * [RegionGeocoder] backed by [android.location.Geocoder]. This is a plain framework API, not a
 * Google Play Services one — safe under the project's no-GMS constraint — but its backend is
 * OS-provided and can be entirely absent on a stock/AOSP build (common on GMS-less devices);
 * [Geocoder.isPresent] guards that, and any failure just yields null, matching the "silent
 * fallback to manual picker" contract [RegionResolver] expects.
 */
class AndroidRegionGeocoder(context: Context) : RegionGeocoder {
    // Fixed to en_US regardless of device locale, matching RegionResolver's normalization
    // (RegionalAreas' catalog is keyed on English administrative-area names).
    private val geocoder = Geocoder(context.applicationContext, Locale.US)

    override suspend fun reverseGeocode(latitude: Double, longitude: Double): GeocodeResult? {
        if (!Geocoder.isPresent()) return null
        val address = fetchAddress(latitude, longitude) ?: return null
        return GeocodeResult(
            countryCode = address.countryCode,
            administrativeArea = address.adminArea,
            subAdministrativeArea = address.subAdminArea,
        )
    }

    private suspend fun fetchAddress(latitude: Double, longitude: Double): Address? =
        if (Build.VERSION.SDK_INT >= 33) fetchAddressAsync(latitude, longitude) else fetchAddressBlocking(latitude, longitude)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun fetchAddressAsync(latitude: Double, longitude: Double): Address? =
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(
                latitude,
                longitude,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }

    private suspend fun fetchAddressBlocking(latitude: Double, longitude: Double): Address? =
        withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
            } catch (error: IOException) {
                null
            }
        }
}
