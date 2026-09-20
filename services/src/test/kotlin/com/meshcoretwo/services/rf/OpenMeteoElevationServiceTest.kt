// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Ported from `ElevationServiceTests.swift`, limited to the pure logic that doesn't require a real
 * network call (`optimalSampleCount`/`sampleCoordinates`/error messages/distance integration) —
 * same scope the Swift suite covers, it never mocks `URLSession` either.
 */
class OpenMeteoElevationServiceTest {
    private val pointA = GeoCoordinate(37.7749, -122.4194)
    private val pointB = GeoCoordinate(37.8049, -122.3894)

    @Test
    fun `optimalSampleCount returns 20 for distances under 1km`() {
        assertEquals(20, OpenMeteoElevationService.optimalSampleCount(0.0))
        assertEquals(20, OpenMeteoElevationService.optimalSampleCount(500.0))
        assertEquals(20, OpenMeteoElevationService.optimalSampleCount(999.0))
    }

    @Test
    fun `optimalSampleCount returns 50 for distances 1-5km`() {
        assertEquals(50, OpenMeteoElevationService.optimalSampleCount(1000.0))
        assertEquals(50, OpenMeteoElevationService.optimalSampleCount(2500.0))
        assertEquals(50, OpenMeteoElevationService.optimalSampleCount(4999.0))
    }

    @Test
    fun `optimalSampleCount returns 80 for distances 5-20km`() {
        assertEquals(80, OpenMeteoElevationService.optimalSampleCount(5000.0))
        assertEquals(80, OpenMeteoElevationService.optimalSampleCount(10000.0))
        assertEquals(80, OpenMeteoElevationService.optimalSampleCount(19999.0))
    }

    @Test
    fun `optimalSampleCount returns 100 for distances over 20km`() {
        assertEquals(100, OpenMeteoElevationService.optimalSampleCount(20000.0))
        assertEquals(100, OpenMeteoElevationService.optimalSampleCount(50000.0))
        assertEquals(100, OpenMeteoElevationService.optimalSampleCount(100_000.0))
    }

    @Test
    fun `optimalSampleCount never exceeds 100`() {
        listOf(0, 100, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100_000, 1_000_000).forEach { distance ->
            assertTrue(OpenMeteoElevationService.optimalSampleCount(distance.toDouble()) <= 100)
        }
    }

    @Test
    fun `sampleCoordinates first coordinate equals pointA`() {
        val samples = OpenMeteoElevationService.sampleCoordinates(pointA, pointB, sampleCount = 10)
        assertEquals(pointA.latitude, samples.first().latitude, 0.0)
        assertEquals(pointA.longitude, samples.first().longitude, 0.0)
    }

    @Test
    fun `sampleCoordinates last coordinate equals pointB`() {
        val samples = OpenMeteoElevationService.sampleCoordinates(pointA, pointB, sampleCount = 10)
        assertEquals(pointB.latitude, samples.last().latitude, 0.0)
        assertEquals(pointB.longitude, samples.last().longitude, 0.0)
    }

    @Test
    fun `sampleCoordinates returns the requested number of samples`() {
        listOf(2, 5, 10, 20, 50, 100).forEach { count ->
            assertEquals(count, OpenMeteoElevationService.sampleCoordinates(pointA, pointB, sampleCount = count).size)
        }
    }

    @Test
    fun `sampleCoordinates count is clamped to a minimum of 2`() {
        assertEquals(2, OpenMeteoElevationService.sampleCoordinates(pointA, pointB, sampleCount = 1).size)
        assertEquals(2, OpenMeteoElevationService.sampleCoordinates(pointA, pointB, sampleCount = 0).size)
    }

    @Test
    fun `sampleCoordinates count is clamped to a maximum of 100`() {
        assertEquals(100, OpenMeteoElevationService.sampleCoordinates(pointA, pointB, sampleCount = 150).size)
    }

    @Test
    fun `sampleCoordinates are evenly distributed`() {
        val samples = OpenMeteoElevationService.sampleCoordinates(pointA, pointB, sampleCount = 5)
        val latStep = (pointB.latitude - pointA.latitude) / 4
        val lonStep = (pointB.longitude - pointA.longitude) / 4

        for (i in 0 until 5) {
            val expectedLat = pointA.latitude + i * latStep
            val expectedLon = pointA.longitude + i * lonStep
            assertTrue(abs(samples[i].latitude - expectedLat) < 0.0001)
            assertTrue(abs(samples[i].longitude - expectedLon) < 0.0001)
        }
    }

    @Test
    fun `sampleCoordinates of identical points returns the same coordinate repeated`() {
        val samples = OpenMeteoElevationService.sampleCoordinates(pointA, pointA, sampleCount = 5)
        assertEquals(5, samples.size)
        samples.forEach { sample ->
            assertEquals(pointA.latitude, sample.latitude, 0.0)
            assertEquals(pointA.longitude, sample.longitude, 0.0)
        }
    }

    @Test
    fun `networkError has a descriptive message`() {
        val error = ElevationServiceError.NetworkError("Connection failed")
        assertTrue(error.message?.contains("Network error") == true)
        assertTrue(error.message?.contains("Connection failed") == true)
    }

    @Test
    fun `invalidResponse has a descriptive message`() {
        assertEquals("Invalid response from elevation API", ElevationServiceError.InvalidResponse.message)
    }

    @Test
    fun `apiError includes the message`() {
        val error = ElevationServiceError.ApiError("Rate limit exceeded")
        assertTrue(error.message?.contains("API error") == true)
        assertTrue(error.message?.contains("Rate limit exceeded") == true)
    }

    @Test
    fun `noData has a descriptive message`() {
        assertEquals("No elevation data returned", ElevationServiceError.NoData.message)
    }

    @Test
    fun `sampleCoordinates work with RFCalculator distance`() {
        val totalDistance = RFCalculator.distance(pointA, pointB)
        val samples = OpenMeteoElevationService.sampleCoordinates(pointA, pointB, sampleCount = 5)

        var cumulativeDistance = 0.0
        for (i in 1 until samples.size) {
            cumulativeDistance += RFCalculator.distance(samples[i - 1], samples[i])
        }

        assertTrue(abs(cumulativeDistance - totalDistance) < 1.0)
    }
}
