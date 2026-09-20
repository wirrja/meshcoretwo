// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from `FresnelZoneRendererTests.swift`. */
class FresnelZoneRendererTest {
    @Test
    fun `ProfileSample computes yTop and yBottom correctly`() {
        val sample = ProfileSample(x = 3000.0, yTerrain = 100.0, yLOS = 150.0, fresnelRadius = 20.0)

        assertEquals(170.0, sample.yTop, 0.0)
        assertEquals(130.0, sample.yBottom, 0.0)
    }

    @Test
    fun `isObstructed returns true when terrain above yBottom`() {
        val clear = ProfileSample(x = 0.0, yTerrain = 100.0, yLOS = 150.0, fresnelRadius = 20.0)
        val obstructed = ProfileSample(x = 0.0, yTerrain = 140.0, yLOS = 150.0, fresnelRadius = 20.0)

        assertFalse(clear.isObstructed)
        assertTrue(obstructed.isObstructed)
    }

    @Test
    fun `yVisibleBottom clamps to prevent path inversion`() {
        val normal = ProfileSample(x = 0.0, yTerrain = 100.0, yLOS = 150.0, fresnelRadius = 20.0)
        assertEquals(130.0, normal.yVisibleBottom, 0.0)

        val intrusion = ProfileSample(x = 0.0, yTerrain = 140.0, yLOS = 150.0, fresnelRadius = 20.0)
        assertEquals(140.0, intrusion.yVisibleBottom, 0.0)

        val blocked = ProfileSample(x = 0.0, yTerrain = 180.0, yLOS = 150.0, fresnelRadius = 20.0)
        assertEquals(170.0, blocked.yVisibleBottom, 0.0)
    }

    @Test
    fun `ProfileSample computes inner 60 percent zone bounds`() {
        val sample = ProfileSample(x = 3000.0, yTerrain = 100.0, yLOS = 150.0, fresnelRadius = 20.0)

        assertEquals(162.0, sample.yTop60, 0.0)
        assertEquals(138.0, sample.yBottom60, 0.0)
    }

    @Test
    fun `yVisibleBottom60 clamps terrain to inner zone`() {
        val clear = ProfileSample(x = 0.0, yTerrain = 100.0, yLOS = 150.0, fresnelRadius = 20.0)
        assertEquals(138.0, clear.yVisibleBottom60, 0.0)

        val intrusion = ProfileSample(x = 0.0, yTerrain = 145.0, yLOS = 150.0, fresnelRadius = 20.0)
        assertEquals(145.0, intrusion.yVisibleBottom60, 0.0)

        val blocked = ProfileSample(x = 0.0, yTerrain = 170.0, yLOS = 150.0, fresnelRadius = 20.0)
        assertEquals(162.0, blocked.yVisibleBottom60, 0.0)
    }

    @Test
    fun `losHeight interpolates linearly between endpoints`() {
        val heightA = 100.0
        val heightB = 200.0
        val totalDistance = 10000.0

        assertEquals(100.0, FresnelZoneRenderer.losHeight(0.0, totalDistance, heightA, heightB), 0.0)
        assertEquals(150.0, FresnelZoneRenderer.losHeight(5000.0, totalDistance, heightA, heightB), 0.0)
        assertEquals(200.0, FresnelZoneRenderer.losHeight(10000.0, totalDistance, heightA, heightB), 0.0)
    }

    @Test
    fun `buildProfileSamples creates samples with correct geometry`() {
        val elevationProfile = listOf(
            ElevationSample(GeoCoordinate(0.0, 0.0), elevation = 100.0, distanceFromAMeters = 0.0),
            ElevationSample(GeoCoordinate(0.0, 0.0), elevation = 100.0, distanceFromAMeters = 3000.0),
            ElevationSample(GeoCoordinate(0.0, 0.0), elevation = 100.0, distanceFromAMeters = 6000.0),
        )

        val samples = FresnelZoneRenderer.buildProfileSamples(
            elevationProfile = elevationProfile,
            pointAHeight = 50.0,
            pointBHeight = 50.0,
            frequencyMHz = 910.0,
            refractionK = 1.33,
        )

        assertEquals(3, samples.size)

        assertEquals(0.0, samples[0].x, 0.0)
        assertEquals(100.0, samples[0].yTerrain, 0.0)
        assertEquals(150.0, samples[0].yLOS, 0.0)
        assertEquals(0.0, samples[0].fresnelRadius, 0.0)

        assertEquals(3000.0, samples[1].x, 0.0)
        assertTrue(samples[1].yTerrain > 100.5)
        assertTrue(samples[1].yTerrain < 100.6)
        assertEquals(150.0, samples[1].yLOS, 0.0)
        assertTrue(samples[1].fresnelRadius > 20.0)

        assertEquals(6000.0, samples[2].x, 0.0)
        assertEquals(100.0, samples[2].yTerrain, 0.0)
        assertEquals(150.0, samples[2].yLOS, 0.0)
        assertEquals(0.0, samples[2].fresnelRadius, 0.0)
    }

    @Test
    fun `buildProfileSamples handles segment slice correctly for R to B case`() {
        val fullProfile = listOf(
            ElevationSample(GeoCoordinate(0.0, 0.0), elevation = 100.0, distanceFromAMeters = 0.0),
            ElevationSample(GeoCoordinate(0.0, 0.0), elevation = 100.0, distanceFromAMeters = 3000.0),
            ElevationSample(GeoCoordinate(0.0, 0.0), elevation = 100.0, distanceFromAMeters = 6000.0), // R
            ElevationSample(GeoCoordinate(0.0, 0.0), elevation = 100.0, distanceFromAMeters = 9000.0),
            ElevationSample(GeoCoordinate(0.0, 0.0), elevation = 100.0, distanceFromAMeters = 12000.0), // B
        )

        val segmentRB = fullProfile.subList(2, fullProfile.size)

        val samples = FresnelZoneRenderer.buildProfileSamples(
            elevationProfile = segmentRB,
            pointAHeight = 50.0,
            pointBHeight = 50.0,
            frequencyMHz = 910.0,
            refractionK = 1.33,
        )

        assertEquals(3, samples.size)

        assertEquals(6000.0, samples[0].x, 0.0)
        assertEquals("Fresnel radius at segment start (R) must be 0", 0.0, samples[0].fresnelRadius, 0.0)

        assertEquals(9000.0, samples[1].x, 0.0)
        assertTrue("Fresnel radius at segment midpoint should be maximum", samples[1].fresnelRadius > 20.0)

        assertEquals(12000.0, samples[2].x, 0.0)
        assertEquals("Fresnel radius at segment end (B) must be 0", 0.0, samples[2].fresnelRadius, 0.0)
    }
}
