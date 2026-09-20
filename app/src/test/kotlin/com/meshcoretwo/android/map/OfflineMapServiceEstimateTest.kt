// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [OfflineMapService.estimatedDownloadSizeBytes] — ported from `estimatedDownloadSize`'s tile-count math. */
class OfflineMapServiceEstimateTest {
    // Small enough to stay within a single Web Mercator tile at every zoom level exercised below.
    private val south = 0.001
    private val west = 0.001
    private val north = 0.002
    private val east = 0.002

    @Test
    fun `a single tile costs one tile's bytes plus the fixed overhead`() {
        val estimate = OfflineMapService.estimatedDownloadSizeBytes(south, west, north, east, minZoom = 10, maxZoom = 10, layer = OfflineMapLayer.BASE)

        assertEquals(15_000L + 500_000L, estimate)
    }

    @Test
    fun `topo tiles are cheaper than base tiles at the same zoom`() {
        val base = OfflineMapService.estimatedDownloadSizeBytes(south, west, north, east, minZoom = 14, maxZoom = 14, layer = OfflineMapLayer.BASE)
        val topo = OfflineMapService.estimatedDownloadSizeBytes(south, west, north, east, minZoom = 14, maxZoom = 14, layer = OfflineMapLayer.TOPO)

        assertTrue(topo < base)
    }

    @Test
    fun `widening the zoom range never shrinks the estimate`() {
        val narrow = OfflineMapService.estimatedDownloadSizeBytes(south, west, north, east, minZoom = 10, maxZoom = 10, layer = OfflineMapLayer.BASE)
        val wide = OfflineMapService.estimatedDownloadSizeBytes(south, west, north, east, minZoom = 10, maxZoom = 11, layer = OfflineMapLayer.BASE)

        assertTrue(wide >= narrow)
    }

    @Test
    fun `a larger area never costs less than a smaller one at the same zoom`() {
        val small = OfflineMapService.estimatedDownloadSizeBytes(south, west, north, east, minZoom = 12, maxZoom = 12, layer = OfflineMapLayer.BASE)
        val large = OfflineMapService.estimatedDownloadSizeBytes(south = -1.0, west = -1.0, north = 1.0, east = 1.0, minZoom = 12, maxZoom = 12, layer = OfflineMapLayer.BASE)

        assertTrue(large > small)
    }
}
