// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

/** Errors from [ElevationService]. Ported from `ElevationServiceError.swift`, English messages only — the `app` layer localizes for display (same convention as [com.meshcoretwo.services.location.LocationProviderError]). */
sealed class ElevationServiceError(message: String) : Exception(message) {
    class NetworkError(description: String) : ElevationServiceError("Network error: $description")
    object InvalidResponse : ElevationServiceError("Invalid response from elevation API")
    class ApiError(apiMessage: String) : ElevationServiceError("API error: $apiMessage")
    object NoData : ElevationServiceError("No elevation data returned")
    object RateLimited : ElevationServiceError("Rate limited by elevation API")
}
