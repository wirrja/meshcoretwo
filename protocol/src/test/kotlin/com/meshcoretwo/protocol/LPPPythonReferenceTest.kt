// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies the Kotlin LPPEncoder produces bytes matching the Python cayennelpp library, via the
 * same reference byte fixtures MeshCoreTests uses (see PythonReferenceBytes.swift).
 *
 * Note: MeshCore's voltage type (0x74) differs from Python cayennelpp's analogInput (0x02) for
 * voltage; tests use analogInput for cross-library compatibility, same as the Swift suite.
 */
class LPPPythonReferenceTest {
    // MARK: - Temperature

    @Test
    fun `Temperature 25_5 matches Python`() {
        // channel(1) + type(0x67) + value(int16 BE, *10) = 25.5*10 = 255 = 0x00FF
        val encoder = LPPEncoder()
        encoder.addTemperature(channel = 1u, celsius = 25.5)
        assertArrayEquals(byteArrayOf(0x01, 0x67, 0x00, 0xFF.toByte()), encoder.encode())
    }

    @Test
    fun `Temperature negative round trip`() {
        val encoder = LPPEncoder()
        encoder.addTemperature(channel = 1u, celsius = -10.5)
        val encoded = encoder.encode()

        // -10.5 * 10 = -105 in signed int16 big-endian = 0xFF97
        assertArrayEquals(byteArrayOf(0x01, 0x67, 0xFF.toByte(), 0x97.toByte()), encoded)

        val decoded = LPPDecoder.decode(encoded)
        assertEquals(1, decoded.size)
        val value = decoded[0].value
        assertTrue(value is LPPValue.Float)
        assertTrue(kotlin.math.abs((value as LPPValue.Float).value - -10.5) <= 0.1)
    }

    // MARK: - Humidity

    @Test
    fun `Humidity 65 matches Python`() {
        // channel(1) + type(0x68) + value(uint8, *2) = 65*2 = 130 = 0x82
        val encoder = LPPEncoder()
        encoder.addHumidity(channel = 2u, percent = 65.0)
        assertArrayEquals(byteArrayOf(0x02, 0x68, 0x82.toByte()), encoder.encode())
    }

    // MARK: - Analog Input

    @Test
    fun `Analog input 3_3 matches Python`() {
        // channel(1) + type(0x02) + value(int16 BE, *100) = 3.3*100 = 330 = 0x014A
        val encoder = LPPEncoder()
        encoder.addAnalogInput(channel = 3u, value = 3.3)
        assertArrayEquals(byteArrayOf(0x03, 0x02, 0x01, 0x4A), encoder.encode())
    }

    // MARK: - GPS

    @Test
    fun `GPS SF matches Python`() {
        val encoder = LPPEncoder()
        encoder.addGPS(channel = 4u, latitude = 37.7749, longitude = -122.4194, altitude = 10.0)
        val expected = byteArrayOf(
            0x04, 0x88.toByte(), 0x05, 0xC3.toByte(), 0x95.toByte(),
            0xED.toByte(), 0x51, 0xFE.toByte(), 0x00, 0x03, 0xE8.toByte(),
        )
        assertArrayEquals(expected, encoder.encode())
    }

    @Test
    fun `GPS decode round trip`() {
        val encoder = LPPEncoder()
        encoder.addGPS(channel = 4u, latitude = 37.7749, longitude = -122.4194, altitude = 10.0)
        val decoded = LPPDecoder.decode(encoder.encode())

        assertEquals(1, decoded.size)
        assertEquals(4u.toUByte(), decoded[0].channel)
        assertEquals(LPPSensorType.GPS, decoded[0].type)

        val gps = decoded[0].value as LPPValue.Gps
        assertTrue(kotlin.math.abs(gps.latitude - 37.7749) <= 0.0001)
        assertTrue(kotlin.math.abs(gps.longitude - -122.4194) <= 0.0001)
        assertTrue(kotlin.math.abs(gps.altitude - 10.0) <= 0.01)
    }

    // MARK: - Barometer

    @Test
    fun `Barometer 1013 matches Python`() {
        val encoder = LPPEncoder()
        encoder.addBarometer(channel = 5u, hPa = 1013.2) // 1013.2 to match Python truncation
        assertArrayEquals(byteArrayOf(0x05, 0x73, 0x27, 0x94.toByte()), encoder.encode())
    }

    // MARK: - Accelerometer

    @Test
    fun `Accelerometer 1g matches Python`() {
        val encoder = LPPEncoder()
        encoder.addAccelerometer(channel = 6u, x = 0.0, y = 0.0, z = 1.0)
        assertArrayEquals(byteArrayOf(0x06, 0x71, 0x00, 0x00, 0x00, 0x00, 0x03, 0xE8.toByte()), encoder.encode())
    }

    @Test
    fun `Accelerometer decode round trip`() {
        val encoder = LPPEncoder()
        encoder.addAccelerometer(channel = 6u, x = 0.5, y = -0.5, z = 1.0)
        val decoded = LPPDecoder.decode(encoder.encode())

        assertEquals(1, decoded.size)
        assertEquals(6u.toUByte(), decoded[0].channel)
        assertEquals(LPPSensorType.ACCELEROMETER, decoded[0].type)

        val v = decoded[0].value as LPPValue.Vector3
        assertTrue(kotlin.math.abs(v.x - 0.5) <= 0.001)
        assertTrue(kotlin.math.abs(v.y - -0.5) <= 0.001)
        assertTrue(kotlin.math.abs(v.z - 1.0) <= 0.001)
    }

    // MARK: - Multi-sensor

    @Test
    fun `Multi-sensor payload`() {
        val encoder = LPPEncoder()
        encoder.addTemperature(channel = 1u, celsius = 25.5)
        encoder.addHumidity(channel = 2u, percent = 65.0)
        encoder.addBarometer(channel = 3u, hPa = 1013.2)
        val decoded = LPPDecoder.decode(encoder.encode())

        assertEquals(3, decoded.size)

        assertEquals(1u.toUByte(), decoded[0].channel)
        assertEquals(LPPSensorType.TEMPERATURE, decoded[0].type)
        assertTrue(kotlin.math.abs((decoded[0].value as LPPValue.Float).value - 25.5) <= 0.1)

        assertEquals(2u.toUByte(), decoded[1].channel)
        assertEquals(LPPSensorType.HUMIDITY, decoded[1].type)
        assertTrue(kotlin.math.abs((decoded[1].value as LPPValue.Float).value - 65.0) <= 0.5)

        assertEquals(3u.toUByte(), decoded[2].channel)
        assertEquals(LPPSensorType.BAROMETER, decoded[2].type)
        assertTrue(kotlin.math.abs((decoded[2].value as LPPValue.Float).value - 1013.2) <= 0.1)
    }

    // MARK: - Voltage (MeshCore-specific)

    @Test
    fun `Voltage encoding`() {
        val encoder = LPPEncoder()
        encoder.addVoltage(channel = 1u, volts = 3.8)
        val encoded = encoder.encode()

        // channel(1) + type(0x74) + value(uint16 BE, *100) = 3.8*100 = 380 = 0x017C
        assertArrayEquals(byteArrayOf(0x01, 0x74, 0x01, 0x7C), encoded)

        val decoded = LPPDecoder.decode(encoded)
        assertEquals(1, decoded.size)
        assertEquals(LPPSensorType.VOLTAGE, decoded[0].type)
        assertTrue(kotlin.math.abs((decoded[0].value as LPPValue.Float).value - 3.8) <= 0.01)
    }

    // MARK: - Edge Cases

    @Test
    fun `Illuminance encoding`() {
        val encoder = LPPEncoder()
        encoder.addIlluminance(channel = 1u, lux = 1000u)
        val encoded = encoder.encode()

        // channel(1) + type(0x65) + value(uint16 BE) = 1000 = 0x03E8
        assertArrayEquals(byteArrayOf(0x01, 0x65, 0x03, 0xE8.toByte()), encoded)

        val decoded = LPPDecoder.decode(encoded)
        assertEquals(1, decoded.size)
        assertEquals(1000L, (decoded[0].value as LPPValue.Integer).value)
    }

    @Test
    fun `Digital IO encoding`() {
        val encoder = LPPEncoder()
        encoder.addDigitalInput(channel = 1u, value = 1u)
        encoder.addDigitalOutput(channel = 2u, value = 0u)
        val encoded = encoder.encode()

        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x01, 0x02, 0x01, 0x00), encoded)

        val decoded = LPPDecoder.decode(encoded)
        assertEquals(2, decoded.size)
        assertTrue((decoded[0].value as LPPValue.Digital).value)
        assertTrue(!(decoded[1].value as LPPValue.Digital).value)
    }

    @Test
    fun `Gyrometer encoding`() {
        val encoder = LPPEncoder()
        encoder.addGyrometer(channel = 1u, x = 10.5, y = -5.25, z = 0.0)
        val encoded = encoder.encode()

        // x: 10.5*100=1050=0x041A; y: -5.25*100=-525=0xFDF3 (signed); z: 0
        assertArrayEquals(
            byteArrayOf(0x01, 0x86.toByte(), 0x04, 0x1A, 0xFD.toByte(), 0xF3.toByte(), 0x00, 0x00),
            encoded,
        )

        val decoded = LPPDecoder.decode(encoded)
        assertEquals(1, decoded.size)
        val v = decoded[0].value as LPPValue.Vector3
        assertTrue(kotlin.math.abs(v.x - 10.5) <= 0.01)
        assertTrue(kotlin.math.abs(v.y - -5.25) <= 0.01)
        assertTrue(kotlin.math.abs(v.z - 0.0) <= 0.01)
    }

    // MARK: - Load (type 122, 3-byte signed, 0.001 kg)

    @Test
    fun `Load positive decodes as 3-byte signed divided by 1000`() {
        // No addLoad encoder exists, so build the raw frame by hand.
        // channel(0x07) + type(0x7A=122) + value(int24 BE, *1000) = 12.345kg*1000=12345=0x003039
        val frame = byteArrayOf(0x07, 0x7A, 0x00, 0x30, 0x39)
        val decoded = LPPDecoder.decode(frame)
        assertEquals(1, decoded.size)
        assertEquals(0x07u.toUByte(), decoded[0].channel)
        assertEquals(LPPSensorType.LOAD, decoded[0].type)
        assertTrue(kotlin.math.abs((decoded[0].value as LPPValue.Float).value - 12.345) <= 0.001)
    }

    @Test
    fun `Load negative round trips through 24-bit sign extension`() {
        // -1.5 kg * 1000 = -1500 = 0xFFFA24 (24-bit two's complement)
        val frame = byteArrayOf(0x07, 0x7A, 0xFF.toByte(), 0xFA.toByte(), 0x24)
        val decoded = LPPDecoder.decode(frame)
        assertEquals(1, decoded.size)
        assertEquals(LPPSensorType.LOAD, decoded[0].type)
        assertTrue(kotlin.math.abs((decoded[0].value as LPPValue.Float).value - -1.5) <= 0.001)
    }

    @Test
    fun `Load consumes three bytes so the next datum stays aligned`() {
        // Load (3 bytes) followed by a temperature reading; if Load were mis-sized at 2 bytes
        // the temperature would be misaligned or dropped.
        // Load: 1.000 kg = 1000 = 0x0003E8; Temp: 25.5C = 255 = 0x00FF
        val frame = byteArrayOf(0x07, 0x7A, 0x00, 0x03, 0xE8.toByte(), 0x01, 0x67, 0x00, 0xFF.toByte())
        val decoded = LPPDecoder.decode(frame)
        assertEquals(2, decoded.size)
        assertEquals(LPPSensorType.LOAD, decoded[0].type)
        assertTrue(kotlin.math.abs((decoded[0].value as LPPValue.Float).value - 1.0) <= 0.001)
        assertEquals(LPPSensorType.TEMPERATURE, decoded[1].type)
        assertTrue(kotlin.math.abs((decoded[1].value as LPPValue.Float).value - 25.5) <= 0.1)
    }

    // MARK: - Generic Sensor (type 100, 4-byte unsigned)

    @Test
    fun `Generic sensor decodes high bit set as a large positive integer`() {
        // No addGenericSensor encoder exists, so build the raw frame by hand.
        // channel(0x01) + type(0x64=100) + value(uint32 BE) = 0x80000000 = 2_147_483_648;
        // a signed decode would yield -2_147_483_648, and a Kotlin Int (32-bit) would wrap too.
        val frame = byteArrayOf(0x01, 0x64, 0x80.toByte(), 0x00, 0x00, 0x00)
        val decoded = LPPDecoder.decode(frame)
        assertEquals(1, decoded.size)
        assertEquals(LPPSensorType.GENERIC_SENSOR, decoded[0].type)
        val value = decoded[0].value
        if (value is LPPValue.Integer) {
            assertEquals(2_147_483_648L, value.value)
        } else {
            fail("Expected LPPValue.Integer for generic sensor")
        }
    }
}
