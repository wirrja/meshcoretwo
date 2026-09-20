// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

// MARK: - LPP Sensor Types

/**
 * Cayenne Low Power Payload (LPP) sensor types.
 *
 * LPP is a compact binary format for transmitting sensor data over low-bandwidth networks like
 * LoRa. Each sensor type has a defined data size and encoding.
 *
 * For the full specification, see:
 * [Cayenne LPP Documentation](https://developers.mydevices.com/cayenne/docs/lora/#lora-cayenne-low-power-payload)
 */
enum class LPPSensorType(val value: UByte) {
    /** Digital input (1 byte). */
    DIGITAL_INPUT(0u),
    /** Digital output (1 byte). */
    DIGITAL_OUTPUT(1u),
    /** Analog input (2 bytes, 0.01 resolution). */
    ANALOG_INPUT(2u),
    /** Analog output (2 bytes, 0.01 resolution). */
    ANALOG_OUTPUT(3u),
    /** Generic sensor (4 bytes). */
    GENERIC_SENSOR(100u),
    /** Illuminance (2 bytes, 1 lux resolution). */
    ILLUMINANCE(101u),
    /** Presence (1 byte). */
    PRESENCE(102u),
    /** Temperature (2 bytes, 0.1C resolution). */
    TEMPERATURE(103u),
    /** Humidity (1 byte, 0.5% resolution). */
    HUMIDITY(104u),
    /** Accelerometer (6 bytes, 0.001G resolution). */
    ACCELEROMETER(113u),
    /** Barometer (2 bytes, 0.1 hPa resolution). */
    BAROMETER(115u),
    /** Voltage (2 bytes, 0.01V resolution). */
    VOLTAGE(116u),
    /** Current (2 bytes, 0.001A resolution). */
    CURRENT(117u),
    /** Frequency (4 bytes, 1 Hz resolution). */
    FREQUENCY(118u),
    /** Percentage (1 byte, 1% resolution). */
    PERCENTAGE(120u),
    /** Altitude (2 bytes, 1m resolution). */
    ALTITUDE(121u),
    /** Load (3 bytes, 0.001kg resolution, signed). */
    LOAD(122u),
    /** Concentration (2 bytes, 1 ppm resolution). */
    CONCENTRATION(125u),
    /** Power (2 bytes, 1W resolution). */
    POWER(128u),
    /** Distance (4 bytes, 0.001m resolution). */
    DISTANCE(130u),
    /** Energy (4 bytes, 0.001kWh resolution). */
    ENERGY(131u),
    /** Direction (2 bytes, 1 degree resolution). */
    DIRECTION(132u),
    /** Unix Time (4 bytes). */
    UNIX_TIME(133u),
    /** Gyrometer (6 bytes, 0.01 degree/s resolution). */
    GYROMETER(134u),
    /** Colour (3 bytes, RGB). */
    COLOUR(135u),
    /** GPS (9 bytes, 0.0001 degree lat/lon, 0.01m alt). */
    GPS(136u),
    /** Switch (1 byte). */
    SWITCH_VALUE(142u);

    /** The size in bytes for this sensor type's data payload. */
    val dataSize: Int
        get() = when (this) {
            // 1-byte types
            DIGITAL_INPUT, DIGITAL_OUTPUT, PRESENCE, HUMIDITY, PERCENTAGE, SWITCH_VALUE -> 1
            // 2-byte types
            ANALOG_INPUT, ANALOG_OUTPUT, ILLUMINANCE, TEMPERATURE, BAROMETER,
            VOLTAGE, CURRENT, ALTITUDE, CONCENTRATION, POWER, DIRECTION,
            -> 2
            // 3-byte types
            COLOUR, LOAD -> 3
            // 4-byte types
            GENERIC_SENSOR, FREQUENCY, DISTANCE, ENERGY, UNIX_TIME -> 4
            // 6-byte types (3 x 2-byte values)
            ACCELEROMETER, GYROMETER -> 6
            // 9-byte types (3 x 3-byte values)
            GPS -> 9
        }

    /** The human-readable name for the sensor type. */
    val displayName: String
        get() = when (this) {
            DIGITAL_INPUT -> "Digital Input"
            DIGITAL_OUTPUT -> "Digital Output"
            ANALOG_INPUT -> "Analog Input"
            ANALOG_OUTPUT -> "Analog Output"
            GENERIC_SENSOR -> "Sensor"
            ILLUMINANCE -> "Illuminance"
            PRESENCE -> "Presence"
            TEMPERATURE -> "Temperature"
            HUMIDITY -> "Humidity"
            ACCELEROMETER -> "Accelerometer"
            BAROMETER -> "Pressure"
            VOLTAGE -> "Voltage"
            CURRENT -> "Current"
            FREQUENCY -> "Frequency"
            PERCENTAGE -> "Percentage"
            ALTITUDE -> "Altitude"
            LOAD -> "Load"
            CONCENTRATION -> "Concentration"
            POWER -> "Power"
            DISTANCE -> "Distance"
            ENERGY -> "Energy"
            DIRECTION -> "Direction"
            UNIX_TIME -> "Time"
            GYROMETER -> "Gyrometer"
            COLOUR -> "Colour"
            GPS -> "GPS"
            SWITCH_VALUE -> "Switch"
        }

    /** The standard unit symbol for the sensor type. */
    val unit: String
        get() = when (this) {
            VOLTAGE -> "V"
            TEMPERATURE -> "°C"
            HUMIDITY, PERCENTAGE -> "%"
            BAROMETER -> "hPa"
            ILLUMINANCE -> "lux"
            CURRENT -> "A"
            POWER -> "W"
            FREQUENCY -> "Hz"
            ALTITUDE, DISTANCE -> "m"
            ENERGY -> "kWh"
            DIRECTION -> "°"
            LOAD -> "kg"
            CONCENTRATION -> "ppm"
            else -> ""
        }

    companion object {
        private val byValue = entries.associateBy { it.value }

        /** Raw-value lookup. */
        fun fromValue(value: UByte): LPPSensorType? = byValue[value]

        /** Looks up a sensor type by its human-readable name. */
        fun fromName(name: String): LPPSensorType? = entries.firstOrNull { it.displayName == name }
    }
}

// MARK: - LPP Values

/** A decoded LPP sensor value. The specific case indicates the value type, depending on the sensor. */
sealed class LPPValue {
    /** Boolean value (digital input/output, presence, switch). */
    data class Digital(val value: Boolean) : LPPValue()

    /**
     * Integer value (illuminance in lux, percentage, direction in degrees, generic sensor, ...).
     *
     * Widened to [Long] (unlike Swift's platform-width `Int`, which is 64-bit and so never
     * overflows here either) because the largest source field is an unsigned 32-bit read
     * (generic sensor, frequency) — a Kotlin `Int` would wrap a value like `0x80000000`
     * (2147483648) into a negative number.
     */
    data class Integer(val value: Long) : LPPValue()

    /** Floating-point value with unit context. */
    data class Float(val value: Double) : LPPValue()

    /** 3D vector (accelerometer in g, gyrometer in degrees/s). */
    data class Vector3(val x: Double, val y: Double, val z: Double) : LPPValue()

    /** GPS coordinates. */
    data class Gps(val latitude: Double, val longitude: Double, val altitude: Double) : LPPValue()

    /** RGB colour. */
    data class Rgb(val red: UByte, val green: UByte, val blue: UByte) : LPPValue()

    /** Unix timestamp. */
    data class Timestamp(val value: Instant) : LPPValue()
}

// MARK: - LPP Data Point

/** A single decoded LPP data point. */
data class LPPDataPoint(
    /** The channel identifier (application-specific). */
    val channel: UByte,
    /** The sensor type. */
    val type: LPPSensorType,
    /** The decoded value. */
    val value: LPPValue,
)

// MARK: - LPP Decoder

/**
 * Decodes Cayenne Low Power Payload (LPP) format sensor data.
 *
 * Parses binary LPP frames into structured [LPPDataPoint] values. LPP is commonly used for
 * transmitting sensor telemetry over LoRa networks.
 *
 * ## Frame Format
 * Each LPP frame consists of multiple sensor readings:
 * - Channel (1 byte): Identifies the sensor instance (0-255)
 * - Type (1 byte): Sensor type from [LPPSensorType]
 * - Value (N bytes): Type-specific encoded value
 */
object LPPDecoder {
    /**
     * Decodes LPP data from raw bytes.
     *
     * @param data Raw LPP-encoded data bytes.
     * @return A list of decoded data points. Empty if the data is empty or cannot be parsed.
     */
    fun decode(data: ByteArray): List<LPPDataPoint> {
        val result = mutableListOf<LPPDataPoint>()
        var offset = 0

        while (offset < data.size) {
            if (offset + 2 > data.size) break

            val channel = data[offset].toUByte()
            val typeCode = data[offset + 1].toUByte()
            offset += 2

            val sensorType = LPPSensorType.fromValue(typeCode) ?: break

            // Check we have enough data for this sensor type
            if (offset + sensorType.dataSize > data.size) break

            val valueData = data.copyOfRange(offset, offset + sensorType.dataSize)
            offset += sensorType.dataSize

            val value = decodeValue(sensorType, valueData)
            if (value != null) {
                result.add(LPPDataPoint(channel = channel, type = sensorType, value = value))
            }
        }

        return result
    }

    // MARK: - Private Helpers

    private fun decodeValue(type: LPPSensorType, data: ByteArray): LPPValue? = when (type) {
        LPPSensorType.DIGITAL_INPUT, LPPSensorType.DIGITAL_OUTPUT,
        LPPSensorType.PRESENCE, LPPSensorType.SWITCH_VALUE,
        -> LPPValue.Digital(data[0] != 0.toByte())

        LPPSensorType.PERCENTAGE -> LPPValue.Integer((data[0].toInt() and 0xFF).toLong())

        LPPSensorType.HUMIDITY -> LPPValue.Float((data[0].toInt() and 0xFF) * 0.5)

        LPPSensorType.TEMPERATURE -> LPPValue.Float(readInt16BE(data) / 10.0)

        LPPSensorType.BAROMETER -> LPPValue.Float(readUInt16BE(data).toDouble() / 10.0)

        LPPSensorType.VOLTAGE ->
            // MeshCore firmware uses 0.01V units (multiplier 100)
            LPPValue.Float(readUInt16BE(data).toDouble() / 100.0)

        LPPSensorType.CURRENT -> LPPValue.Float(readInt16BE(data) / 1000.0)

        LPPSensorType.ILLUMINANCE -> LPPValue.Integer(readUInt16BE(data).toLong())

        LPPSensorType.ALTITUDE -> LPPValue.Float(readInt16BE(data).toDouble())

        LPPSensorType.LOAD -> LPPValue.Float(readInt24BE(data, 0) / 1000.0)

        LPPSensorType.CONCENTRATION -> LPPValue.Integer(readUInt16BE(data).toLong())

        LPPSensorType.POWER -> LPPValue.Integer(readUInt16BE(data).toLong())

        LPPSensorType.DIRECTION -> LPPValue.Integer(readUInt16BE(data).toLong())

        LPPSensorType.ANALOG_INPUT, LPPSensorType.ANALOG_OUTPUT ->
            LPPValue.Float(readInt16BE(data) / 100.0)

        LPPSensorType.GENERIC_SENSOR -> LPPValue.Integer(readUInt32BE(data).toLong())

        LPPSensorType.FREQUENCY -> LPPValue.Integer(readUInt32BE(data).toLong())

        LPPSensorType.DISTANCE -> LPPValue.Float(readUInt32BE(data).toLong() / 1000.0)

        LPPSensorType.ENERGY -> LPPValue.Float(readUInt32BE(data).toLong() / 1000.0)

        LPPSensorType.UNIX_TIME -> LPPValue.Timestamp(Instant.ofEpochSecond(readUInt32BE(data).toLong()))

        LPPSensorType.ACCELEROMETER -> {
            val x = readInt16BE(data, 0)
            val y = readInt16BE(data, 2)
            val z = readInt16BE(data, 4)
            LPPValue.Vector3(x = x / 1000.0, y = y / 1000.0, z = z / 1000.0)
        }

        LPPSensorType.GYROMETER -> {
            val x = readInt16BE(data, 0)
            val y = readInt16BE(data, 2)
            val z = readInt16BE(data, 4)
            LPPValue.Vector3(x = x / 100.0, y = y / 100.0, z = z / 100.0)
        }

        LPPSensorType.COLOUR -> LPPValue.Rgb(red = data[0].toUByte(), green = data[1].toUByte(), blue = data[2].toUByte())

        LPPSensorType.GPS -> {
            // lat/lon: 0.0001 degree resolution, alt: 0.01m resolution
            val lat = readInt24BE(data, 0)
            val lon = readInt24BE(data, 3)
            val alt = readInt24BE(data, 6)
            LPPValue.Gps(latitude = lat / 10000.0, longitude = lon / 10000.0, altitude = alt / 100.0)
        }
    }

    // MARK: - Binary Reading Helpers (Big-Endian for MeshCore/LPP compatibility)

    private fun readInt16BE(data: ByteArray, offset: Int = 0): Short {
        if (offset + 2 > data.size) return 0
        val hi = data[offset].toInt() and 0xFF
        val lo = data[offset + 1].toInt() and 0xFF
        return ((hi shl 8) or lo).toShort()
    }

    private fun readUInt16BE(data: ByteArray, offset: Int = 0): UShort {
        if (offset + 2 > data.size) return 0u
        val hi = data[offset].toInt() and 0xFF
        val lo = data[offset + 1].toInt() and 0xFF
        return ((hi shl 8) or lo).toUShort()
    }

    private fun readUInt32BE(data: ByteArray, offset: Int = 0): UInt {
        if (offset + 4 > data.size) return 0u
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        val b3 = data[offset + 3].toInt() and 0xFF
        return ((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3).toUInt()
    }

    /** Reads a 24-bit signed integer (big-endian). */
    private fun readInt24BE(data: ByteArray, offset: Int): Int {
        if (offset + 3 > data.size) return 0
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        var value = (b0 shl 16) or (b1 shl 8) or b2
        // Sign extend if negative (bit 23 is set)
        if (value and 0x800000 != 0) {
            value = value or -0x1000000 // 0xFF000000
        }
        return value
    }
}
