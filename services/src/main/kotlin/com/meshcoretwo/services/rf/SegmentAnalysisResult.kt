// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

/** Analysis result for a single path segment (A to R, or R to B). Ported from `SegmentAnalysisResult.swift`. */
data class SegmentAnalysisResult(
    val startLabel: String,
    val endLabel: String,
    val clearanceStatus: ClearanceStatus,
    val distanceMeters: Double,
    val worstClearancePercent: Double,
) {
    val distanceKm: Double get() = distanceMeters / 1000
}
