// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.location

/** A one-shot device location fix, decoupled from `android.location.Location` so callers are easy to fake in tests. */
data class LocationFix(val latitude: Double, val longitude: Double)

sealed class LocationProviderError(message: String) : Exception(message) {
    object NotAuthorized : LocationProviderError("Location permission not granted")
    object NoProviderAvailable : LocationProviderError("No positioning provider is enabled")
    object Timeout : LocationProviderError("Timed out waiting for a location fix")
    class RequestFailed(reason: String) : LocationProviderError(reason)
}

/**
 * Provides a one-shot device location fix. Ported from `LocationService.swift`, trimmed to what
 * region resolution needs: no continuous updates, and no authorization-request plumbing — unlike
 * iOS's `CLLocationManager.requestWhenInUseAuthorization()`, an Android runtime permission prompt
 * can only be triggered from an Activity/Compose `rememberLauncherForActivityResult`, so
 * requesting the permission is the UI layer's job. This interface only reports whether it's
 * already granted.
 */
interface LocationProvider {
    val isAuthorized: Boolean

    /** Requests a fresh location fix. Throws [LocationProviderError] on any failure, including not-authorized. */
    suspend fun requestCurrentLocation(timeoutMs: Long = 5_000): LocationFix
}
