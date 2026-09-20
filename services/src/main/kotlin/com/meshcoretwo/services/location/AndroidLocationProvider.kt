// SPDX-License-Identifier: GPL-3.0-only

@file:Suppress("MissingPermission")

package com.meshcoretwo.services.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * [LocationProvider] backed by [LocationManager] — deliberately not
 * `FusedLocationProviderClient`/`com.google.android.gms.location.*`, per the project's no-GMS
 * constraint (target devices include Huawei without Play Services). Requests only
 * `ACCESS_COARSE_LOCATION`: region resolution only needs country/state granularity, so the
 * lower-sensitivity coarse permission is enough.
 *
 * File-level `@Suppress("MissingPermission")` because [isAuthorized] is checked at the top of
 * [requestCurrentLocation] before any `LocationManager` call runs — lint can't trace that check
 * across the method, same rationale as `BleStateMachine`'s file-level suppression.
 */
class AndroidLocationProvider(context: Context) : LocationProvider {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override val isAuthorized: Boolean
        get() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun requestCurrentLocation(timeoutMs: Long): LocationFix {
        if (!isAuthorized) throw LocationProviderError.NotAuthorized
        val provider = bestProvider() ?: throw LocationProviderError.NoProviderAvailable

        return try {
            withTimeout(timeoutMs) { awaitLocation(provider) }
        } catch (error: TimeoutCancellationException) {
            throw LocationProviderError.Timeout
        }
    }

    private suspend fun awaitLocation(provider: String): LocationFix = suspendCancellableCoroutine { continuation ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(LocationFix(location.latitude, location.longitude)))
                }
            }
        }
        continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
        try {
            locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (error: SecurityException) {
            continuation.resumeWith(Result.failure(LocationProviderError.NotAuthorized))
        }
    }

    private fun bestProvider(): String? {
        val criteria = Criteria().apply { accuracy = Criteria.ACCURACY_COARSE }
        return locationManager.getBestProvider(criteria, true)
    }
}
