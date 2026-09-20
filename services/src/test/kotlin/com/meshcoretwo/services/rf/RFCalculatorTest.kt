// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Ported from `RFCalculatorTests.swift`'s `RFCalculatorTests` suite (constants/wavelength/Fresnel/earth-bulge/path-loss/diffraction/Haversine). */
class RFCalculatorTest {
    @Test
    fun `speed of light constant is correct`() {
        assertEquals(299_792_458.0, RFCalculator.SPEED_OF_LIGHT, 0.0)
    }

    @Test
    fun `earth radius constant is correct`() {
        assertEquals(6371.0, RFCalculator.EARTH_RADIUS_KM, 0.0)
    }

    @Test
    fun `wavelength at 910 MHz is approximately 0,3294m`() {
        val wavelength = RFCalculator.wavelength(frequencyMHz = 910.0)
        assertTrue(abs(wavelength - 0.3294) < 0.001)
    }

    @Test
    fun `wavelength at 2400 MHz is approximately 0,125m`() {
        val wavelength = RFCalculator.wavelength(frequencyMHz = 2400.0)
        assertTrue(abs(wavelength - 0.125) < 0.001)
    }

    @Test
    fun `wavelength returns 0 for zero frequency`() {
        assertEquals(0.0, RFCalculator.wavelength(frequencyMHz = 0.0), 0.0)
    }

    @Test
    fun `wavelength returns 0 for negative frequency`() {
        assertEquals(0.0, RFCalculator.wavelength(frequencyMHz = -100.0), 0.0)
    }

    @Test
    fun `fresnel radius at midpoint is approximately 22,23m for 6km at 910MHz`() {
        val radius = RFCalculator.fresnelRadius(frequencyMHz = 910.0, distanceToAMeters = 3000.0, distanceToBMeters = 3000.0)
        assertTrue(abs(radius - 22.23) < 0.5)
    }

    @Test
    fun `fresnel radius at quarter point is smaller than at midpoint`() {
        val totalDistance = 6000.0
        val quarterPoint = totalDistance / 4

        val radiusAtQuarter = RFCalculator.fresnelRadius(
            frequencyMHz = 910.0,
            distanceToAMeters = quarterPoint,
            distanceToBMeters = totalDistance - quarterPoint,
        )
        val radiusAtMidpoint = RFCalculator.fresnelRadius(frequencyMHz = 910.0, distanceToAMeters = 3000.0, distanceToBMeters = 3000.0)

        assertTrue(radiusAtQuarter < radiusAtMidpoint)
        assertTrue(abs(radiusAtQuarter - 19.27) < 0.5)
    }

    @Test
    fun `fresnel radius is symmetric`() {
        val radius1 = RFCalculator.fresnelRadius(frequencyMHz = 910.0, distanceToAMeters = 2000.0, distanceToBMeters = 4000.0)
        val radius2 = RFCalculator.fresnelRadius(frequencyMHz = 910.0, distanceToAMeters = 4000.0, distanceToBMeters = 2000.0)
        assertTrue(abs(radius1 - radius2) < 0.001)
    }

    @Test
    fun `fresnel radius returns 0 for invalid inputs`() {
        assertEquals(0.0, RFCalculator.fresnelRadius(frequencyMHz = 0.0, distanceToAMeters = 100.0, distanceToBMeters = 100.0), 0.0)
        assertEquals(0.0, RFCalculator.fresnelRadius(frequencyMHz = 910.0, distanceToAMeters = 0.0, distanceToBMeters = 100.0), 0.0)
        assertEquals(0.0, RFCalculator.fresnelRadius(frequencyMHz = 910.0, distanceToAMeters = 100.0, distanceToBMeters = 0.0), 0.0)
        assertEquals(0.0, RFCalculator.fresnelRadius(frequencyMHz = -100.0, distanceToAMeters = 100.0, distanceToBMeters = 100.0), 0.0)
    }

    @Test
    fun `earth bulge at midpoint with k=0,25 is approximately 2,82m for 6km`() {
        val bulge = RFCalculator.earthBulge(distanceToAMeters = 3000.0, distanceToBMeters = 3000.0, refractionK = 0.25)
        assertTrue(abs(bulge - 2.82) < 0.05)
    }

    @Test
    fun `earth bulge with standard atmosphere k=1,33 is smaller`() {
        val bulgeConservative = RFCalculator.earthBulge(distanceToAMeters = 3000.0, distanceToBMeters = 3000.0, refractionK = 0.25)
        val bulgeStandard = RFCalculator.earthBulge(distanceToAMeters = 3000.0, distanceToBMeters = 3000.0, refractionK = 1.33)

        assertTrue(bulgeStandard < bulgeConservative)
        assertTrue(abs(bulgeStandard - 0.53) < 0.05)
    }

    @Test
    fun `earth bulge is symmetric`() {
        val bulge1 = RFCalculator.earthBulge(distanceToAMeters = 2000.0, distanceToBMeters = 4000.0, refractionK = 1.0)
        val bulge2 = RFCalculator.earthBulge(distanceToAMeters = 4000.0, distanceToBMeters = 2000.0, refractionK = 1.0)
        assertTrue(abs(bulge1 - bulge2) < 0.001)
    }

    @Test
    fun `earth bulge returns 0 for invalid inputs`() {
        assertEquals(0.0, RFCalculator.earthBulge(distanceToAMeters = 0.0, distanceToBMeters = 100.0, refractionK = 1.0), 0.0)
        assertEquals(0.0, RFCalculator.earthBulge(distanceToAMeters = 100.0, distanceToBMeters = 0.0, refractionK = 1.0), 0.0)
        assertEquals(0.0, RFCalculator.earthBulge(distanceToAMeters = 100.0, distanceToBMeters = 100.0, refractionK = 0.0), 0.0)
        assertEquals(0.0, RFCalculator.earthBulge(distanceToAMeters = 100.0, distanceToBMeters = 100.0, refractionK = -1.0), 0.0)
    }

    @Test
    fun `path loss is approximately 107,2 dB for 6km at 910MHz`() {
        val loss = RFCalculator.pathLoss(distanceMeters = 6000.0, frequencyMHz = 910.0)
        assertTrue(abs(loss - 107.2) < 0.5)
    }

    @Test
    fun `path loss increases with distance`() {
        val loss1km = RFCalculator.pathLoss(distanceMeters = 1000.0, frequencyMHz = 910.0)
        val loss2km = RFCalculator.pathLoss(distanceMeters = 2000.0, frequencyMHz = 910.0)
        val loss4km = RFCalculator.pathLoss(distanceMeters = 4000.0, frequencyMHz = 910.0)

        assertTrue(loss2km > loss1km)
        assertTrue(loss4km > loss2km)
        assertTrue(abs((loss2km - loss1km) - 6.02) < 0.1)
        assertTrue(abs((loss4km - loss2km) - 6.02) < 0.1)
    }

    @Test
    fun `path loss increases with frequency`() {
        val loss400MHz = RFCalculator.pathLoss(distanceMeters = 1000.0, frequencyMHz = 400.0)
        val loss900MHz = RFCalculator.pathLoss(distanceMeters = 1000.0, frequencyMHz = 900.0)
        val loss2400MHz = RFCalculator.pathLoss(distanceMeters = 1000.0, frequencyMHz = 2400.0)

        assertTrue(loss900MHz > loss400MHz)
        assertTrue(loss2400MHz > loss900MHz)
    }

    @Test
    fun `path loss returns 0 for invalid inputs`() {
        assertEquals(0.0, RFCalculator.pathLoss(distanceMeters = 0.0, frequencyMHz = 910.0), 0.0)
        assertEquals(0.0, RFCalculator.pathLoss(distanceMeters = 1000.0, frequencyMHz = 0.0), 0.0)
        assertEquals(0.0, RFCalculator.pathLoss(distanceMeters = -100.0, frequencyMHz = 910.0), 0.0)
        assertEquals(0.0, RFCalculator.pathLoss(distanceMeters = 1000.0, frequencyMHz = -910.0), 0.0)
    }

    @Test
    fun `diffraction loss is zero for clear line-of-sight (v below grazing)`() {
        val loss = RFCalculator.diffractionLoss(
            obstructionHeightMeters = -50.0,
            distanceToAMeters = 3000.0,
            distanceToBMeters = 3000.0,
            frequencyMHz = 910.0,
        )
        assertEquals(0.0, loss, 0.0)
    }

    @Test
    fun `diffraction loss is approximately 6 dB for grazing (v near 0)`() {
        val loss = RFCalculator.diffractionLoss(
            obstructionHeightMeters = 0.0,
            distanceToAMeters = 3000.0,
            distanceToBMeters = 3000.0,
            frequencyMHz = 910.0,
        )
        assertTrue(abs(loss - 6.0) < 1.0)
    }

    @Test
    fun `diffraction loss increases for blocked path (v near 1)`() {
        val loss = RFCalculator.diffractionLoss(
            obstructionHeightMeters = 15.7,
            distanceToAMeters = 3000.0,
            distanceToBMeters = 3000.0,
            frequencyMHz = 910.0,
        )
        assertTrue(abs(loss - 13.9) < 0.5)
    }

    @Test
    fun `diffraction loss is greater for larger obstructions`() {
        val lossSmall = RFCalculator.diffractionLoss(obstructionHeightMeters = 5.0, distanceToAMeters = 3000.0, distanceToBMeters = 3000.0, frequencyMHz = 910.0)
        val lossMedium = RFCalculator.diffractionLoss(obstructionHeightMeters = 15.0, distanceToAMeters = 3000.0, distanceToBMeters = 3000.0, frequencyMHz = 910.0)
        val lossLarge = RFCalculator.diffractionLoss(obstructionHeightMeters = 30.0, distanceToAMeters = 3000.0, distanceToBMeters = 3000.0, frequencyMHz = 910.0)

        assertTrue(lossMedium > lossSmall)
        assertTrue(lossLarge > lossMedium)
    }

    @Test
    fun `diffraction loss matches ITU-R P526 reference value at strong obstruction (v approx 2,4)`() {
        val loss = RFCalculator.diffractionLoss(
            obstructionHeightMeters = 37.7,
            distanceToAMeters = 3000.0,
            distanceToBMeters = 3000.0,
            frequencyMHz = 910.0,
        )
        assertTrue(abs(loss - 20.5) < 0.7)
    }

    @Test
    fun `diffraction loss returns 0 for invalid inputs`() {
        assertEquals(0.0, RFCalculator.diffractionLoss(obstructionHeightMeters = 10.0, distanceToAMeters = 0.0, distanceToBMeters = 100.0, frequencyMHz = 910.0), 0.0)
        assertEquals(0.0, RFCalculator.diffractionLoss(obstructionHeightMeters = 10.0, distanceToAMeters = 100.0, distanceToBMeters = 0.0, frequencyMHz = 910.0), 0.0)
        assertEquals(0.0, RFCalculator.diffractionLoss(obstructionHeightMeters = 10.0, distanceToAMeters = 100.0, distanceToBMeters = 100.0, frequencyMHz = 0.0), 0.0)
    }

    @Test
    fun `distance between same coordinates is zero`() {
        val coord = GeoCoordinate(37.7749, -122.4194)
        assertEquals(0.0, RFCalculator.distance(from = coord, to = coord), 0.0)
    }

    @Test
    fun `haversine distance calculation is accurate`() {
        val sanFrancisco = GeoCoordinate(37.7749, -122.4194)
        val losAngeles = GeoCoordinate(34.0522, -118.2437)
        val distance = RFCalculator.distance(from = sanFrancisco, to = losAngeles)
        assertTrue(abs(distance - 559_000) < 10000)
    }

    @Test
    fun `haversine distance is symmetric`() {
        val coord1 = GeoCoordinate(37.7749, -122.4194)
        val coord2 = GeoCoordinate(34.0522, -118.2437)
        val distance1 = RFCalculator.distance(from = coord1, to = coord2)
        val distance2 = RFCalculator.distance(from = coord2, to = coord1)
        assertTrue(abs(distance1 - distance2) < 0.001)
    }

    @Test
    fun `distance across date line is correct`() {
        val tokyo = GeoCoordinate(35.6762, 139.6503)
        val sanFrancisco = GeoCoordinate(37.7749, -122.4194)
        val distance = RFCalculator.distance(from = tokyo, to = sanFrancisco)
        assertTrue(abs(distance - 8_280_000) < 100_000)
    }

    @Test
    fun `short distance calculation is accurate`() {
        val point1 = GeoCoordinate(37.7749, -122.4194)
        val point2 = GeoCoordinate(37.7839, -122.4194)
        val distance = RFCalculator.distance(from = point1, to = point2)
        assertTrue(abs(distance - 1000) < 100)
    }
}
