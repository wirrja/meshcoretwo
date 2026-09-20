// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import com.meshcoretwo.services.rendering.SNRQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceHopTest {
    private fun hop(latitude: Double?, longitude: Double?) = TraceHop(
        hashBytes = byteArrayOf(0x3F),
        resolvedName = "Tower",
        snr = 5.0,
        isStartNode = false,
        isEndNode = false,
        latitude = latitude,
        longitude = longitude,
    )

    @Test
    fun `hasLocation is true with valid non-zero coordinates`() {
        assertTrue(hop(37.7749, -122.4194).hasLocation)
    }

    @Test
    fun `hasLocation is false with zero coordinates`() {
        assertFalse(hop(0.0, 0.0).hasLocation)
    }

    @Test
    fun `hasLocation is false with null coordinates`() {
        assertFalse(hop(null, null).hasLocation)
    }

    @Test
    fun `hasLocation is true if only latitude is non-zero`() {
        assertTrue(hop(45.0, 0.0).hasLocation)
    }

    @Test
    fun `hasLocation is true if only longitude is non-zero`() {
        assertTrue(hop(0.0, -122.0).hasLocation)
    }

    @Test
    fun `hashDisplayString is uppercase hex, null for start-end nodes`() {
        assertEquals("3F", hop(0.0, 0.0).hashDisplayString)
        assertEquals(null, hop(0.0, 0.0).copy(hashBytes = null).hashDisplayString)
    }

    @Test
    fun `snrQuality delegates to SNRQuality of`() {
        assertEquals(SNRQuality.of(5.0), hop(0.0, 0.0).snrQuality)
    }

    @Test
    fun `equals and hashCode compare hashBytes by content`() {
        val a = hop(1.0, 1.0)
        val b = hop(1.0, 1.0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
