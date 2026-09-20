// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboundHopAdoptionTest {
    @Test
    fun `adopts incoming when nothing is stored yet`() {
        assertEquals(3 to 100u, adoptInboundHop(storedHops = null, storedTimestamp = null, incomingHops = 3, incomingTimestamp = 100u))
    }

    @Test
    fun `adopts incoming with no timestamp when stored timestamp is also null`() {
        assertEquals(3 to null, adoptInboundHop(storedHops = null, storedTimestamp = null, incomingHops = 3, incomingTimestamp = null))
    }

    @Test
    fun `ignores an ungrouped incoming read once a timestamp is stored`() {
        assertNull(adoptInboundHop(storedHops = 2, storedTimestamp = 100u, incomingHops = 1, incomingTimestamp = null))
    }

    @Test
    fun `adopts a newer advert regardless of hop count`() {
        assertEquals(5 to 200u, adoptInboundHop(storedHops = 1, storedTimestamp = 100u, incomingHops = 5, incomingTimestamp = 200u))
    }

    @Test
    fun `adopts a closer copy of the same broadcast`() {
        assertEquals(1 to 100u, adoptInboundHop(storedHops = 3, storedTimestamp = 100u, incomingHops = 1, incomingTimestamp = 100u))
    }

    @Test
    fun `ignores a farther copy of the same broadcast`() {
        assertNull(adoptInboundHop(storedHops = 1, storedTimestamp = 100u, incomingHops = 3, incomingTimestamp = 100u))
    }

    @Test
    fun `ignores an older advert`() {
        assertNull(adoptInboundHop(storedHops = 1, storedTimestamp = 200u, incomingHops = 0, incomingTimestamp = 100u))
    }
}
