// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RF propagation calculator for line-of-sight analysis. Ported 1:1 from `RFCalculator.swift`
 * (`MC1Services/RF/`) — wavelength, Fresnel zones, earth bulge, path loss, and knife-edge
 * diffraction loss for radio frequency propagation analysis.
 */
object RFCalculator {
    /** Speed of light in meters per second. */
    const val SPEED_OF_LIGHT: Double = 299_792_458.0

    /** Earth's radius in kilometers. */
    const val EARTH_RADIUS_KM: Double = 6371.0

    /** Minimum Fresnel zone clearance percentage for a "clear" path. */
    const val CLEAR_CLEARANCE_THRESHOLD: Double = 80.0

    /** Minimum Fresnel zone clearance percentage for a "marginal" path. */
    const val MARGINAL_CLEARANCE_THRESHOLD: Double = 60.0

    /** Calculates the wavelength in meters for a given frequency in megahertz. */
    fun wavelength(frequencyMHz: Double): Double {
        if (frequencyMHz <= 0) return 0.0
        val frequencyHz = frequencyMHz * 1_000_000
        return SPEED_OF_LIGHT / frequencyHz
    }

    /**
     * Calculates the first Fresnel zone radius (meters) at a point along the path.
     *
     * The Fresnel zone represents the ellipsoidal region around the direct line-of-sight path
     * where radio waves propagate. For best reception, at least 60% of the first Fresnel zone
     * should be clear of obstructions.
     */
    fun fresnelRadius(frequencyMHz: Double, distanceToAMeters: Double, distanceToBMeters: Double): Double {
        if (frequencyMHz <= 0 || distanceToAMeters <= 0 || distanceToBMeters <= 0) return 0.0

        val lambda = wavelength(frequencyMHz)
        val totalDistance = distanceToAMeters + distanceToBMeters

        // First Fresnel zone radius: r = sqrt((lambda * d1 * d2) / (d1 + d2))
        return sqrt((lambda * distanceToAMeters * distanceToBMeters) / totalDistance)
    }

    /**
     * Calculates the earth bulge (curvature correction, in meters) at a point along the path.
     *
     * @param refractionK The effective earth radius factor. Use 1.0 for no adjustment, 1.33 (4/3)
     *   for standard atmosphere, or 4.0 for ducting conditions.
     */
    fun earthBulge(distanceToAMeters: Double, distanceToBMeters: Double, refractionK: Double): Double {
        if (distanceToAMeters <= 0 || distanceToBMeters <= 0 || refractionK <= 0) return 0.0

        val earthRadiusMeters = EARTH_RADIUS_KM * 1000
        val effectiveEarthRadius = refractionK * earthRadiusMeters

        // Earth bulge: h = (d1 * d2) / (2 * Re_effective)
        return (distanceToAMeters * distanceToBMeters) / (2 * effectiveEarthRadius)
    }

    /** Calculates the free-space path loss in decibels. */
    fun pathLoss(distanceMeters: Double, frequencyMHz: Double): Double {
        if (distanceMeters <= 0 || frequencyMHz <= 0) return 0.0

        // FSPL (dB) = 20*log10(d) + 20*log10(f) + 20*log10(4*pi/c)
        // The constant = 20*log10(4*pi*1e6/299792458) ~= -27.55
        val distanceComponent = 20 * log10(distanceMeters)
        val frequencyComponent = 20 * log10(frequencyMHz)
        val constant = -27.55
        return distanceComponent + frequencyComponent + constant
    }

    /**
     * Calculates the knife-edge diffraction loss (dB, positive = loss) for a single obstruction,
     * using the Fresnel-Kirchhoff diffraction parameter (v).
     *
     * @param obstructionHeightMeters Height of the obstruction above the line-of-sight (positive =
     *   blocked, negative = clearance).
     */
    fun diffractionLoss(
        obstructionHeightMeters: Double,
        distanceToAMeters: Double,
        distanceToBMeters: Double,
        frequencyMHz: Double,
    ): Double {
        if (distanceToAMeters <= 0 || distanceToBMeters <= 0 || frequencyMHz <= 0) return 0.0

        val lambda = wavelength(frequencyMHz)
        val totalDistance = distanceToAMeters + distanceToBMeters

        // Fresnel-Kirchhoff diffraction parameter: v = h * sqrt(2 * (d1 + d2) / (lambda * d1 * d2))
        val vParam = obstructionHeightMeters * sqrt(2 * totalDistance / (lambda * distanceToAMeters * distanceToBMeters))

        return diffractionLossFromV(vParam)
    }

    /** Fresnel-Kirchhoff parameter at or below which the path has full clearance and no knife-edge loss. */
    private const val DIFFRACTION_CLEAR_THRESHOLD_V: Double = -0.78

    /**
     * Calculates diffraction loss from the Fresnel-Kirchhoff v parameter, using the ITU-R P.526
     * single-equation knife-edge diffraction model. A single expression keeps the loss continuous
     * and monotonic across the whole obstruction range, avoiding the branch-join drift of
     * piecewise polynomial approximations.
     */
    private fun diffractionLossFromV(vParam: Double): Double {
        if (vParam <= DIFFRACTION_CLEAR_THRESHOLD_V) return 0.0
        val shifted = vParam - 0.1
        return 6.9 + 20 * log10(sqrt(shifted * shifted + 1) + shifted)
    }

    /** Calculates the great-circle distance between two coordinates using the Haversine formula, in meters. */
    fun distance(from: GeoCoordinate, to: GeoCoordinate): Double {
        val earthRadiusMeters = EARTH_RADIUS_KM * 1000

        val lat1 = from.latitude * Math.PI / 180
        val lat2 = to.latitude * Math.PI / 180
        val deltaLat = (to.latitude - from.latitude) * Math.PI / 180
        val deltaLon = (to.longitude - from.longitude) * Math.PI / 180

        val haversineA = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        val angularDistance = 2 * atan2(sqrt(haversineA), sqrt(1 - haversineA))

        return earthRadiusMeters * angularDistance
    }

    /**
     * Analyzes a full path for clearance and signal propagation: free-space path loss (FSPL),
     * additional loss from diffraction over obstructions, Fresnel zone clearance at each point,
     * and overall clearance status.
     *
     * @param pointAHeightMeters Antenna height at point A, meters above ground.
     * @param pointBHeightMeters Antenna height at point B, meters above ground.
     * @param refractionK The effective earth radius factor (see [earthBulge]).
     */
    fun analyzePath(
        elevationProfile: List<ElevationSample>,
        pointAHeightMeters: Double,
        pointBHeightMeters: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ): PathAnalysisResult =
        // Full-path distances are measured from A itself, so the origin is 0 even if the first
        // sample carries a non-zero distance.
        analyze(
            elevationProfile = elevationProfile,
            startHeightMeters = pointAHeightMeters,
            endHeightMeters = pointBHeightMeters,
            frequencyMHz = frequencyMHz,
            refractionK = refractionK,
            distanceOriginMeters = 0.0,
        )

    /**
     * Analyzes a segment of the path for clearance and signal propagation. Takes a [List] slice
     * (`subList`) rather than copying, mirroring the Swift `ArraySlice` overload used for
     * drag-performance-sensitive callers.
     */
    fun analyzePathSegment(
        elevationProfile: List<ElevationSample>,
        startHeightMeters: Double,
        endHeightMeters: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ): PathAnalysisResult =
        analyze(
            elevationProfile = elevationProfile,
            startHeightMeters = startHeightMeters,
            endHeightMeters = endHeightMeters,
            frequencyMHz = frequencyMHz,
            refractionK = refractionK,
            distanceOriginMeters = elevationProfile.firstOrNull()?.distanceFromAMeters ?: 0.0,
        )

    /** Distance margin (meters) within which a sample counts as an endpoint and is skipped. */
    private const val ENDPOINT_SKIP_MARGIN_METERS: Double = 1.0

    /**
     * Shared core for [analyzePath] and [analyzePathSegment].
     *
     * `distanceOriginMeters` anchors the local distance axis: 0 for a full path, the first
     * sample's `distanceFromAMeters` for a segment. Recorded obstruction points always keep the
     * sample's original `distanceFromAMeters` so they stay in full-path coordinates for chart
     * rendering.
     */
    private fun analyze(
        elevationProfile: List<ElevationSample>,
        startHeightMeters: Double,
        endHeightMeters: Double,
        frequencyMHz: Double,
        refractionK: Double,
        distanceOriginMeters: Double,
    ): PathAnalysisResult {
        val firstSample = elevationProfile.firstOrNull()
        val lastSample = elevationProfile.lastOrNull()
        if (elevationProfile.size < 2 || firstSample == null || lastSample == null) {
            return emptyResult(frequencyMHz, refractionK)
        }

        val lengthMeters = lastSample.distanceFromAMeters - distanceOriginMeters
        if (lengthMeters <= 0) return emptyResult(frequencyMHz, refractionK)

        // Antenna heights above sea level.
        val antennaStartHeight = firstSample.elevation + startHeightMeters
        val antennaEndHeight = lastSample.elevation + endHeightMeters

        val fspl = pathLoss(distanceMeters = lengthMeters, frequencyMHz = frequencyMHz)

        var worstClearancePercent = Double.POSITIVE_INFINITY
        var peakDiffractionLoss = 0.0
        val obstructionPoints = mutableListOf<ObstructionPoint>()

        for (sample in elevationProfile) {
            val distanceFromStart = sample.distanceFromAMeters - distanceOriginMeters
            val distanceToEnd = lengthMeters - distanceFromStart

            // Skip points at or very near the endpoints.
            if (distanceFromStart <= ENDPOINT_SKIP_MARGIN_METERS || distanceToEnd <= ENDPOINT_SKIP_MARGIN_METERS) continue

            // Line of sight height at this point (linear interpolation).
            val fraction = distanceFromStart / lengthMeters
            val losHeight = antennaStartHeight + fraction * (antennaEndHeight - antennaStartHeight)

            // Effective terrain height including earth bulge.
            val bulge = earthBulge(
                distanceToAMeters = distanceFromStart,
                distanceToBMeters = distanceToEnd,
                refractionK = refractionK,
            )
            val effectiveTerrainHeight = sample.elevation + bulge

            val fresnelZoneRadius = fresnelRadius(
                frequencyMHz = frequencyMHz,
                distanceToAMeters = distanceFromStart,
                distanceToBMeters = distanceToEnd,
            )

            // Clearance: distance from terrain to line of sight.
            val clearance = losHeight - effectiveTerrainHeight

            // Fresnel clearance percentage: 100% = terrain clears full first Fresnel zone,
            // 0% = terrain touches line of sight, <0% = terrain blocks line of sight.
            val clearancePercent = if (fresnelZoneRadius > 0) {
                (clearance / fresnelZoneRadius) * 100
            } else {
                if (clearance > 0) 100.0 else 0.0
            }

            if (clearancePercent < worstClearancePercent) worstClearancePercent = clearancePercent

            // Obstruction height is negative clearance (positive = blocked).
            val obstructionHeight = effectiveTerrainHeight - losHeight
            if (obstructionHeight > -fresnelZoneRadius) {
                val diffLoss = diffractionLoss(
                    obstructionHeightMeters = obstructionHeight,
                    distanceToAMeters = distanceFromStart,
                    distanceToBMeters = distanceToEnd,
                    frequencyMHz = frequencyMHz,
                )
                if (diffLoss > peakDiffractionLoss) peakDiffractionLoss = diffLoss
            }

            if (clearancePercent < MARGINAL_CLEARANCE_THRESHOLD) {
                obstructionPoints.add(
                    ObstructionPoint(
                        distanceFromAMeters = sample.distanceFromAMeters,
                        obstructionHeightMeters = obstructionHeight,
                        fresnelClearancePercent = clearancePercent,
                    ),
                )
            }
        }

        if (worstClearancePercent == Double.POSITIVE_INFINITY) worstClearancePercent = 100.0

        return PathAnalysisResult(
            distanceMeters = lengthMeters,
            freeSpacePathLoss = fspl,
            peakDiffractionLoss = peakDiffractionLoss,
            totalPathLoss = fspl + peakDiffractionLoss,
            clearanceStatus = clearanceStatus(worstClearancePercent),
            worstClearancePercent = worstClearancePercent,
            obstructionPoints = obstructionPoints,
            frequencyMHz = frequencyMHz,
            refractionK = refractionK,
        )
    }

    /** The zeroed blocked result returned for degenerate profiles. */
    private fun emptyResult(frequencyMHz: Double, refractionK: Double) = PathAnalysisResult(
        distanceMeters = 0.0,
        freeSpacePathLoss = 0.0,
        peakDiffractionLoss = 0.0,
        totalPathLoss = 0.0,
        clearanceStatus = ClearanceStatus.BLOCKED,
        worstClearancePercent = 0.0,
        obstructionPoints = emptyList(),
        frequencyMHz = frequencyMHz,
        refractionK = refractionK,
    )

    /** Maps a worst-case Fresnel clearance percentage to a clearance status. */
    private fun clearanceStatus(percent: Double): ClearanceStatus = when {
        percent >= CLEAR_CLEARANCE_THRESHOLD -> ClearanceStatus.CLEAR
        percent >= MARGINAL_CLEARANCE_THRESHOLD -> ClearanceStatus.MARGINAL
        percent >= 0 -> ClearanceStatus.PARTIAL_OBSTRUCTION
        else -> ClearanceStatus.BLOCKED
    }
}
