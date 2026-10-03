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
    /** Where the value came from, for the attribution under the terrain profile; `null` for synthetic profiles. */
    val source: ElevationSource? = null,
)

/** The dataset behind an [ElevationSample]. Not in Swift, which has Open-Meteo only. */
enum class ElevationSource {
    /** Mapterhorn terrain tiles, see [TerrainTileElevationService]. */
    MAPTERHORN,

    /** Copernicus DEM GLO-90 via the Open-Meteo API, see [OpenMeteoElevationService]. */
    OPEN_METEO,
}
