// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

/**
 * Fetches elevation data for line-of-sight analysis. Ported from `ElevationServiceProtocol.swift`
 * — kept as its own interface (rather than folding into [OpenMeteoElevationService] directly) so a
 * future `LineOfSightViewModel` port can substitute a fake in tests, same as the Swift ViewModel
 * does via dependency injection.
 */
interface ElevationService {
    /** Fetches elevation (meters above sea level) for a single coordinate. */
    suspend fun fetchElevation(coordinate: GeoCoordinate): Double

    /** Fetches elevations for multiple coordinates along a path, with distance from the first point. */
    suspend fun fetchElevations(path: List<GeoCoordinate>): List<ElevationSample>
}
