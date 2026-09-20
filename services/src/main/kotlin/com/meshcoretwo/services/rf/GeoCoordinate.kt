// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

/**
 * A plain lat/lon pair, the Kotlin analogue of `CLLocationCoordinate2D` for the RF/elevation code
 * in this package. Deliberately not [com.meshcoretwo.services.location.LocationFix] — that type is
 * scoped to "a fix the device just took", while this one is scoped to "any point on a path" (path
 * endpoints, repeater positions, elevation samples), most of which never touch the device GPS.
 */
data class GeoCoordinate(val latitude: Double, val longitude: Double)
