// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

/**
 * Elevation sample along a path. Ported from `ElevationSample.swift`. No synthetic `id` field
 * (Swift's is only there for `Identifiable`/SwiftUI list diffing) — a future Compose list can key
 * off `distanceFromAMeters`, which is unique per sample within one profile.
 */
data class ElevationSample(
    val coordinate: GeoCoordinate,
    /** Meters above sea level. */
    val elevation: Double,
    val distanceFromAMeters: Double,
)
