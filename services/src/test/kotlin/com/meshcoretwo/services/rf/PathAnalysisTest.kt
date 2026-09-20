// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from `RFCalculatorTests.swift`'s `PathAnalysisTests`/`PathAnalysisResultFieldsTests`/`SegmentAnalysisTests` suites. */
class PathAnalysisTest {
    private val baseLatitude = 37.7749
    private val baseLongitude = -122.4194

    private fun flatProfile(elevationMeters: Double, totalDistanceMeters: Double, sampleCount: Int = 11): List<ElevationSample> =
        (0 until sampleCount).map { i ->
            val fraction = i.toDouble() / (sampleCount - 1)
            ElevationSample(
                coordinate = GeoCoordinate(baseLatitude + fraction * 0.01, baseLongitude),
                elevation = elevationMeters,
                distanceFromAMeters = fraction * totalDistanceMeters,
            )
        }

    /** Triangular mountain shape centered at the profile's midpoint. */
    private fun obstructedProfile(
        baseElevationMeters: Double,
        obstructionHeightMeters: Double,
        totalDistanceMeters: Double,
        sampleCount: Int = 11,
    ): List<ElevationSample> {
        val midpoint = sampleCount / 2
        return (0 until sampleCount).map { i ->
            val fraction = i.toDouble() / (sampleCount - 1)
            val distanceFromMid = kotlin.math.abs(i - midpoint)
            val peakFactor = maxOf(0.0, 1.0 - distanceFromMid.toDouble() / midpoint)
            ElevationSample(
                coordinate = GeoCoordinate(baseLatitude + fraction * 0.01, baseLongitude),
                elevation = baseElevationMeters + obstructionHeightMeters * peakFactor,
                distanceFromAMeters = fraction * totalDistanceMeters,
            )
        }
    }

    @Test
    fun `clear path with flat terrain returns clear status`() {
        val profile = flatProfile(elevationMeters = 0.0, totalDistanceMeters = 6000.0, sampleCount = 21)
        val result = RFCalculator.analyzePath(profile, pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 910.0, refractionK = 1.0)

        assertEquals(ClearanceStatus.CLEAR, result.clearanceStatus)
        assertTrue(result.worstClearancePercent >= 80)
        assertTrue(result.obstructionPoints.isEmpty())
        assertEquals(6000.0, result.distanceMeters, 0.0)
        assertEquals(6.0, result.distanceKm, 0.0)
    }

    @Test
    fun `clear path has only FSPL, no diffraction loss`() {
        val profile = flatProfile(elevationMeters = 0.0, totalDistanceMeters = 6000.0, sampleCount = 21)
        val result = RFCalculator.analyzePath(profile, pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 910.0, refractionK = 1.0)

        assertTrue(kotlin.math.abs(result.freeSpacePathLoss - 107.2) < 1.0)
        assertEquals(0.0, result.peakDiffractionLoss, 0.0)
        assertEquals(result.freeSpacePathLoss, result.totalPathLoss, 0.0)
    }

    @Test
    fun `blocked path with 100m mountain returns blocked status`() {
        val profile = obstructedProfile(baseElevationMeters = 0.0, obstructionHeightMeters = 100.0, totalDistanceMeters = 6000.0, sampleCount = 21)
        val result = RFCalculator.analyzePath(profile, pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 910.0, refractionK = 1.0)

        assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
        assertTrue(result.worstClearancePercent < 0)
        assertTrue(result.obstructionPoints.isNotEmpty())
    }

    @Test
    fun `blocked path has significant diffraction loss`() {
        val profile = obstructedProfile(baseElevationMeters = 0.0, obstructionHeightMeters = 100.0, totalDistanceMeters = 6000.0, sampleCount = 21)
        val result = RFCalculator.analyzePath(profile, pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 910.0, refractionK = 1.0)

        assertTrue(result.peakDiffractionLoss > 10)
        assertTrue(result.totalPathLoss > result.freeSpacePathLoss)
    }

    @Test
    fun `marginal path with partial obstruction returns marginal or clear status`() {
        val profile = obstructedProfile(baseElevationMeters = 0.0, obstructionHeightMeters = 25.0, totalDistanceMeters = 6000.0, sampleCount = 21)
        val result = RFCalculator.analyzePath(profile, pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 910.0, refractionK = 1.0)

        assertTrue(result.clearanceStatus == ClearanceStatus.CLEAR || result.clearanceStatus == ClearanceStatus.MARGINAL)
        assertTrue(result.worstClearancePercent >= 60)
    }

    @Test
    fun `partial obstruction path returns partial obstruction status`() {
        val profile = obstructedProfile(baseElevationMeters = 0.0, obstructionHeightMeters = 45.0, totalDistanceMeters = 6000.0, sampleCount = 21)
        val result = RFCalculator.analyzePath(profile, pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 910.0, refractionK = 1.0)

        assertEquals(ClearanceStatus.PARTIAL_OBSTRUCTION, result.clearanceStatus)
        assertTrue(result.worstClearancePercent >= 0)
        assertTrue(result.worstClearancePercent < 60)
        assertTrue(result.obstructionPoints.isNotEmpty())
    }

    @Test
    fun `empty profile returns blocked status`() {
        val result = RFCalculator.analyzePath(emptyList(), pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 910.0, refractionK = 1.0)
        assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
        assertEquals(0.0, result.distanceMeters, 0.0)
    }

    @Test
    fun `single sample profile returns blocked status`() {
        val sample = ElevationSample(GeoCoordinate(baseLatitude, baseLongitude), elevation = 0.0, distanceFromAMeters = 0.0)
        val result = RFCalculator.analyzePath(listOf(sample), pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 910.0, refractionK = 1.0)
        assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
    }

    @Test
    fun `profile with zero total distance returns blocked status`() {
        val samples = listOf(
            ElevationSample(GeoCoordinate(baseLatitude, baseLongitude), elevation = 0.0, distanceFromAMeters = 0.0),
            ElevationSample(GeoCoordinate(baseLatitude, baseLongitude), elevation = 0.0, distanceFromAMeters = 0.0),
        )
        val result = RFCalculator.analyzePath(samples, pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 910.0, refractionK = 1.0)
        assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
        assertEquals(0.0, result.distanceMeters, 0.0)
    }

    @Test
    fun `asymmetric antenna heights are handled correctly`() {
        val profile = flatProfile(elevationMeters = 0.0, totalDistanceMeters = 6000.0, sampleCount = 21)
        val result = RFCalculator.analyzePath(profile, pointAHeightMeters = 100.0, pointBHeightMeters = 20.0, frequencyMHz = 910.0, refractionK = 1.0)

        assertEquals(ClearanceStatus.CLEAR, result.clearanceStatus)
        assertTrue(result.worstClearancePercent >= 80)
    }

    @Test
    fun `custom k-factor affects earth bulge calculation`() {
        val profile = obstructedProfile(baseElevationMeters = 0.0, obstructionHeightMeters = 40.0, totalDistanceMeters = 6000.0, sampleCount = 21)

        val resultConservative = RFCalculator.analyzePath(profile, pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 910.0, refractionK = 0.25)
        val resultStandard = RFCalculator.analyzePath(profile, pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 910.0, refractionK = 1.33)

        assertTrue(resultStandard.worstClearancePercent > resultConservative.worstClearancePercent)
    }

    @Test
    fun `PathAnalysisResult includes frequency used in calculation`() {
        val profile = listOf(
            ElevationSample(GeoCoordinate(baseLatitude, baseLongitude), elevation = 0.0, distanceFromAMeters = 0.0),
            ElevationSample(GeoCoordinate(baseLatitude + 0.01, baseLongitude), elevation = 0.0, distanceFromAMeters = 1000.0),
        )
        val result = RFCalculator.analyzePath(profile, pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 915.0, refractionK = 1.33)
        assertEquals(915.0, result.frequencyMHz, 0.0)
    }

    @Test
    fun `PathAnalysisResult includes k-factor used in calculation`() {
        val profile = listOf(
            ElevationSample(GeoCoordinate(baseLatitude, baseLongitude), elevation = 0.0, distanceFromAMeters = 0.0),
            ElevationSample(GeoCoordinate(baseLatitude + 0.01, baseLongitude), elevation = 0.0, distanceFromAMeters = 1000.0),
        )
        val result = RFCalculator.analyzePath(profile, pointAHeightMeters = 50.0, pointBHeightMeters = 50.0, frequencyMHz = 906.0, refractionK = 1.33)
        assertEquals(1.33, result.refractionK, 0.0)
    }

    @Test
    fun `analyzePathSegment works with a sublist`() {
        val samples = (0..10).map { i ->
            ElevationSample(GeoCoordinate(37.0 + i * 0.01, -122.0), elevation = 100.0, distanceFromAMeters = i * 1000.0)
        }

        val firstHalf = samples.subList(0, 6)
        val result = RFCalculator.analyzePathSegment(firstHalf, startHeightMeters = 50.0, endHeightMeters = 50.0, frequencyMHz = 906.0, refractionK = 1.0)

        assertEquals(5000.0, result.distanceMeters, 0.0)
        assertEquals(ClearanceStatus.CLEAR, result.clearanceStatus)
    }

    @Test
    fun `analyzePathSegment handles a sublist offset from the start`() {
        val samples = (0..10).map { i ->
            ElevationSample(GeoCoordinate(37.0 + i * 0.01, -122.0), elevation = 100.0, distanceFromAMeters = i * 1000.0)
        }

        val secondHalf = samples.subList(5, 11)
        val result = RFCalculator.analyzePathSegment(secondHalf, startHeightMeters = 10.0, endHeightMeters = 10.0, frequencyMHz = 906.0, refractionK = 1.0)

        assertEquals(5000.0, result.distanceMeters, 0.0)
    }
}
