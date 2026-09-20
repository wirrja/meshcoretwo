// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

/**
 * Builds [ProfileSample]s (LOS height + Fresnel radius per terrain sample) for line-of-sight
 * terrain-profile rendering. Ported 1:1 from `FresnelZoneRenderer.swift`.
 */
object FresnelZoneRenderer {
    /** Calculates the LOS height at a given distance along the path, meters above sea level. */
    fun losHeight(atDistance: Double, totalDistance: Double, heightA: Double, heightB: Double): Double {
        if (totalDistance <= 0) return heightA
        val fraction = atDistance / totalDistance
        return heightA + fraction * (heightB - heightA)
    }

    /**
     * Builds profile samples from elevation data.
     *
     * @param elevationProfile Elevation samples from the terrain API — can be a segment slice.
     * @param pointAHeight Antenna height at segment start, meters above ground.
     * @param pointBHeight Antenna height at segment end, meters above ground.
     * @param frequencyMHz Operating frequency for the Fresnel zone calculation.
     * @param refractionK Effective earth radius factor for the earth-bulge calculation.
     *
     * When passed a segment slice (e.g. R→B), samples may carry non-zero `distanceFromAMeters`.
     * This calculates Fresnel zones relative to the segment boundaries while preserving the
     * original x-coordinates for rendering.
     */
    fun buildProfileSamples(
        elevationProfile: List<ElevationSample>,
        pointAHeight: Double,
        pointBHeight: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ): List<ProfileSample> {
        val first = elevationProfile.firstOrNull() ?: return emptyList()
        val last = elevationProfile.lastOrNull() ?: return emptyList()

        // Segment-relative distances for Fresnel/LOS calculations, handling both full paths
        // (offset 0) and segment slices (offset > 0).
        val segmentOffset = first.distanceFromAMeters
        val segmentLength = last.distanceFromAMeters - segmentOffset

        val heightA = first.elevation + pointAHeight
        val heightB = last.elevation + pointBHeight

        return elevationProfile.map { sample ->
            val distanceFromSegmentStart = sample.distanceFromAMeters - segmentOffset
            val distanceToSegmentEnd = segmentLength - distanceFromSegmentStart

            val yLOS = losHeight(
                atDistance = distanceFromSegmentStart,
                totalDistance = segmentLength,
                heightA = heightA,
                heightB = heightB,
            )

            val radius = RFCalculator.fresnelRadius(
                frequencyMHz = frequencyMHz,
                distanceToAMeters = distanceFromSegmentStart,
                distanceToBMeters = distanceToSegmentEnd,
            )

            val earthBulge = RFCalculator.earthBulge(
                distanceToAMeters = distanceFromSegmentStart,
                distanceToBMeters = distanceToSegmentEnd,
                refractionK = refractionK,
            )

            // Preserve the original x-coordinate for correct rendering position.
            ProfileSample(
                x = sample.distanceFromAMeters,
                yTerrain = sample.elevation + earthBulge,
                yLOS = yLOS,
                fresnelRadius = radius,
            )
        }
    }
}
