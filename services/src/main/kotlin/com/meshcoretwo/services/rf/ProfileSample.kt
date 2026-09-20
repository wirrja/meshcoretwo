// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

/**
 * Terrain profile sample with computed line-of-sight and Fresnel-zone geometry, ready for
 * rendering. Ported from `ProfileSample` in `FresnelZoneRenderer.swift` — pure geometry with no
 * Canvas/Compose dependency, which is why it lives beside [RFCalculator] in `services` rather than
 * in `app`, even though its Swift counterpart sits in the View layer (`MC1/Views/Tools/LineOfSight/`).
 */
data class ProfileSample(
    /** Distance from A in meters. */
    val x: Double,
    /** Terrain elevation in meters, earth-bulge-adjusted. */
    val yTerrain: Double,
    /** Line-of-sight height in meters above sea level. */
    val yLOS: Double,
    val fresnelRadius: Double,
) {
    val yTop: Double get() = yLOS + fresnelRadius
    val yBottom: Double get() = yLOS - fresnelRadius

    /** Inner 60% zone boundaries (ideal clearance threshold). */
    val yTop60: Double get() = yLOS + fresnelRadius * 0.6
    val yBottom60: Double get() = yLOS - fresnelRadius * 0.6

    /** Visible bottom of the inner 60% zone (clamped). */
    val yVisibleBottom60: Double get() = yTerrain.coerceIn(yBottom60, yTop60)

    /** Whether terrain intrudes past the 60% Fresnel clearance threshold at this point. */
    val isObstructed: Boolean get() = yTerrain > yBottom60

    /** Visible bottom of the Fresnel zone (clamped to avoid path inversion). */
    val yVisibleBottom: Double get() = yTerrain.coerceIn(yBottom, yTop)
}
