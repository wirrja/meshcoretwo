// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of `WiFiHostValidationTests.swift`, same cases. */
class WiFiAddressValidationTest {
    @Test
    fun `classifies host`() {
        val cases = listOf(
            "192.168.1.50" to true,
            "radio.local" to true,
            "example.com" to true,
            "repeater" to true,
            "  radio.local  " to true,
            "" to false,
            "999.999.999.999" to false,
            "192.168.1" to false,
            "http://radio.local" to false,
        )
        for ((host, expected) in cases) {
            assertEquals("host=$host", expected, WiFiAddressValidation.isValidHost(host))
        }
    }

    @Test
    fun `classifies port`() {
        val cases = listOf(
            "5000" to true,
            "1" to true,
            "65535" to true,
            "0" to false,
            "65536" to false,
            "" to false,
            "abc" to false,
        )
        for ((port, expected) in cases) {
            assertEquals("port=$port", expected, WiFiAddressValidation.isValidPort(port))
        }
    }
}
