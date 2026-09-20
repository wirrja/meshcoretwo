// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayPathAnalysisResultTest {
    private fun segment(status: ClearanceStatus, distanceMeters: Double = 1000.0) = SegmentAnalysisResult(
        startLabel = "A",
        endLabel = "R",
        clearanceStatus = status,
        distanceMeters = distanceMeters,
        worstClearancePercent = 50.0,
    )

    @Test
    fun `totalDistance sums both segments`() {
        val result = RelayPathAnalysisResult(
            segmentAR = segment(ClearanceStatus.CLEAR, 1200.0),
            segmentRB = segment(ClearanceStatus.CLEAR, 800.0),
        )

        assertEquals(2000.0, result.totalDistanceMeters, 0.0)
        assertEquals(2.0, result.totalDistanceKm, 0.0)
    }

    @Test
    fun `overallStatus is the worst of the two segments`() {
        val clearBoth = RelayPathAnalysisResult(segment(ClearanceStatus.CLEAR), segment(ClearanceStatus.CLEAR))
        assertEquals(ClearanceStatus.CLEAR, clearBoth.overallStatus)

        val oneMarginal = RelayPathAnalysisResult(segment(ClearanceStatus.CLEAR), segment(ClearanceStatus.MARGINAL))
        assertEquals(ClearanceStatus.MARGINAL, oneMarginal.overallStatus)

        val oneBlocked = RelayPathAnalysisResult(segment(ClearanceStatus.PARTIAL_OBSTRUCTION), segment(ClearanceStatus.BLOCKED))
        assertEquals(ClearanceStatus.BLOCKED, oneBlocked.overallStatus)
    }

    @Test
    fun `distanceKm converts segment distance`() {
        assertEquals(1.5, segment(ClearanceStatus.CLEAR, 1500.0).distanceKm, 0.0)
    }
}
