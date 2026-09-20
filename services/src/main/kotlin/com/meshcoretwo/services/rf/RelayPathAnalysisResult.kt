// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

/** Combined result when analyzing a path via a repeater. Ported from `RelayPathAnalysisResult.swift`. */
data class RelayPathAnalysisResult(
    val segmentAR: SegmentAnalysisResult,
    val segmentRB: SegmentAnalysisResult,
) {
    val totalDistanceMeters: Double get() = segmentAR.distanceMeters + segmentRB.distanceMeters
    val totalDistanceKm: Double get() = totalDistanceMeters / 1000

    /** Overall status is the worst of the two segments. */
    val overallStatus: ClearanceStatus
        get() {
            val statusOrder = listOf(
                ClearanceStatus.CLEAR,
                ClearanceStatus.MARGINAL,
                ClearanceStatus.PARTIAL_OBSTRUCTION,
                ClearanceStatus.BLOCKED,
            )
            val arIndex = statusOrder.indexOf(segmentAR.clearanceStatus).coerceAtLeast(0)
            val rbIndex = statusOrder.indexOf(segmentRB.clearanceStatus).coerceAtLeast(0)
            return statusOrder[maxOf(arIndex, rbIndex)]
        }
}
