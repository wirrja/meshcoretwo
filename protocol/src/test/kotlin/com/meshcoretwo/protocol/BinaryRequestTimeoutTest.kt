// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Port of BinaryRequestTimeoutTests.swift. */
class BinaryRequestTimeoutTest {
    @Test
    fun `defaults use a 40 second overall budget and 1 second retransmit floor`() {
        val configuration = SessionConfiguration()
        assertEquals(40.0, configuration.binaryRequestOverallTimeout, 0.0)
        assertEquals(1.0, configuration.binaryRequestRetransmitInterval!!, 0.0)
        assertEquals(2.0, SessionConfiguration.BINARY_RETRANSMIT_RTT_HEADROOM, 0.0)
    }

    @Test
    fun `configuration accepts custom overall and null retransmit disables in exchange resends`() {
        val configuration = SessionConfiguration(
            binaryRequestOverallTimeout = 0.05,
            binaryRequestRetransmitInterval = null,
        )
        assertEquals(0.05, configuration.binaryRequestOverallTimeout, 0.0)
        assertNull(configuration.binaryRequestRetransmitInterval)
    }
}
