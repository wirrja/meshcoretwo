// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

/** Point where obstruction affects the path. Ported from `ObstructionPoint.swift`, no synthetic id (see [ElevationSample]). */
data class ObstructionPoint(
    val distanceFromAMeters: Double,
    val obstructionHeightMeters: Double,
    val fresnelClearancePercent: Double,
)
