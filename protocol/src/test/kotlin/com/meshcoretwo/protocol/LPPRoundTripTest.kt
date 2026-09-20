// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Port of the LPP round-trip cases from MeshCoreTests/Validation/RoundTripTests.swift. */
class LPPRoundTripTest {
    @Test
    fun `LPP encoder-decoder temperature round trip`() {
        val encoder = LPPEncoder()
        encoder.addTemperature(channel = 1u, celsius = 22.5)
        val decoded = LPPDecoder.decode(encoder.encode())

        assertEquals(1, decoded.size)
        assertEquals(1u.toUByte(), decoded[0].channel)
        assertEquals(LPPSensorType.TEMPERATURE, decoded[0].type)
        assertTrue(abs((decoded[0].value as LPPValue.Float).value - 22.5) <= 0.1)
    }

    @Test
    fun `LPP encoder-decoder GPS round trip`() {
        val encoder = LPPEncoder()
        encoder.addGPS(channel = 3u, latitude = 37.7749, longitude = -122.4194, altitude = 50.0)
        val decoded = LPPDecoder.decode(encoder.encode())

        assertEquals(1, decoded.size)
        assertEquals(3u.toUByte(), decoded[0].channel)
        assertEquals(LPPSensorType.GPS, decoded[0].type)

        val gps = decoded[0].value as LPPValue.Gps
        assertTrue(abs(gps.latitude - 37.7749) <= 0.0001)
        assertTrue(abs(gps.longitude - -122.4194) <= 0.0001)
        assertTrue(abs(gps.altitude - 50.0) <= 0.01)
    }

    @Test
    fun `LPP encoder-decoder multi-sensor round trip`() {
        val encoder = LPPEncoder()
        encoder.addTemperature(channel = 1u, celsius = 25.0)
        encoder.addHumidity(channel = 2u, percent = 60.0)
        encoder.addVoltage(channel = 3u, volts = 3.7)
        val decoded = LPPDecoder.decode(encoder.encode())

        assertEquals(3, decoded.size)

        assertEquals(1u.toUByte(), decoded[0].channel)
        assertEquals(LPPSensorType.TEMPERATURE, decoded[0].type)
        assertTrue(abs((decoded[0].value as LPPValue.Float).value - 25.0) <= 0.1)

        assertEquals(2u.toUByte(), decoded[1].channel)
        assertEquals(LPPSensorType.HUMIDITY, decoded[1].type)
        assertTrue(abs((decoded[1].value as LPPValue.Float).value - 60.0) <= 0.5)

        assertEquals(3u.toUByte(), decoded[2].channel)
        assertEquals(LPPSensorType.VOLTAGE, decoded[2].type)
        assertTrue(abs((decoded[2].value as LPPValue.Float).value - 3.7) <= 0.01)
    }

    @Test
    fun `LPP encoder-decoder negative temperature round trip`() {
        val encoder = LPPEncoder()
        encoder.addTemperature(channel = 1u, celsius = -15.5)
        val decoded = LPPDecoder.decode(encoder.encode())

        assertEquals(1, decoded.size)
        assertTrue(abs((decoded[0].value as LPPValue.Float).value - -15.5) <= 0.1)
    }

    @Test
    fun `LPP encoder-decoder accelerometer round trip`() {
        val encoder = LPPEncoder()
        encoder.addAccelerometer(channel = 5u, x = -0.5, y = 0.25, z = 1.0)
        val decoded = LPPDecoder.decode(encoder.encode())

        assertEquals(1, decoded.size)
        assertEquals(LPPSensorType.ACCELEROMETER, decoded[0].type)

        val v = decoded[0].value as LPPValue.Vector3
        assertTrue(abs(v.x - -0.5) <= 0.001)
        assertTrue(abs(v.y - 0.25) <= 0.001)
        assertTrue(abs(v.z - 1.0) <= 0.001)
    }

    // MARK: - Additional coverage for the checked-conversion "trap" contract (no direct Swift
    // unit test — Swift Int16(_:) traps and crashes the process, which isn't testable there
    // either; Kotlin's equivalent throws, which IS testable, so it's worth locking in).

    @Test(expected = IllegalArgumentException::class)
    fun `addTemperature throws when the scaled value overflows Int16`() {
        LPPEncoder().addTemperature(channel = 1u, celsius = 10_000.0) // *10 = 100_000, overflows Int16
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addHumidity throws when the scaled value overflows UInt8`() {
        LPPEncoder().addHumidity(channel = 1u, percent = 200.0) // *2 = 400, overflows UInt8
    }

    @Test
    fun `addRaw rejects a data size mismatch`() {
        val encoder = LPPEncoder()
        try {
            encoder.addRaw(channel = 1u, type = LPPSensorType.TEMPERATURE, data = byteArrayOf(0x01))
            org.junit.Assert.fail("Expected IllegalArgumentException for size mismatch")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
