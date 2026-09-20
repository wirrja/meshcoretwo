// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

/** Complete analysis result for a path. Ported from `PathAnalysisResult.swift`. */
data class PathAnalysisResult(
    val distanceMeters: Double,
    val freeSpacePathLoss: Double,
    /** Peak diffraction loss from the single worst knife-edge obstruction (not cumulative). */
    val peakDiffractionLoss: Double,
    val totalPathLoss: Double,
    val clearanceStatus: ClearanceStatus,
    val worstClearancePercent: Double,
    val obstructionPoints: List<ObstructionPoint>,
    val frequencyMHz: Double,
    val refractionK: Double,
) {
    val distanceKm: Double
        get() = distanceMeters / 1000

    val worstObstructionPoint: ObstructionPoint?
        get() = obstructionPoints.minByOrNull { it.fresnelClearancePercent }

    /**
     * Returns the worst obstruction point per contiguous obstructed region. Groups adjacent
     * obstruction points by sample spacing, then picks the lowest-clearance point from each
     * group, one per red bar in the terrain profile.
     */
    val peakObstructionPerRegion: List<ObstructionPoint>
        get() {
            if (obstructionPoints.size < 2) return obstructionPoints

            // Find the smallest gap between consecutive points (= one sample step).
            var minGap = Double.POSITIVE_INFINITY
            for (i in 1 until obstructionPoints.size) {
                val gap = obstructionPoints[i].distanceFromAMeters - obstructionPoints[i - 1].distanceFromAMeters
                if (gap > 0 && gap < minGap) minGap = gap
            }
            if (!minGap.isFinite()) return listOf(obstructionPoints[0])

            // A gap > 2.5x the sample step means a non-obstructed sample separates two regions.
            val gapThreshold = minGap * 2.5

            val regions = mutableListOf<ObstructionPoint>()
            var regionWorst = obstructionPoints[0]

            for (i in 1 until obstructionPoints.size) {
                val point = obstructionPoints[i]
                val gap = point.distanceFromAMeters - obstructionPoints[i - 1].distanceFromAMeters

                if (gap > gapThreshold) {
                    regions.add(regionWorst)
                    regionWorst = point
                } else if (point.fresnelClearancePercent < regionWorst.fresnelClearancePercent) {
                    regionWorst = point
                }
            }
            regions.add(regionWorst)

            return regions
        }
}
