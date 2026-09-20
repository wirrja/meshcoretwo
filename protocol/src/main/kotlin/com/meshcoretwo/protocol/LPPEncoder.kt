// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// Swift's Int16(_:)/UInt16(_:)/UInt8(_:) from a Double truncate the fractional part but TRAP
// (crash) if the whole-number part doesn't fit — a deliberate "fail fast rather than transmit
// corrupted telemetry" contract, distinct from PacketBuilder's saturating clamps. These throw
// IllegalArgumentException to match, rather than silently wrapping like a raw Kotlin
// Double.toShort() would.

private fun checkedInt16(value: Double): Short {
    val truncated = value.toLong()
    require(truncated in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()) {
        "LPP value $value does not fit in Int16"
    }
    return truncated.toShort()
}

private fun checkedUInt16(value: Double): UShort {
    val truncated = value.toLong()
    require(truncated in 0..UShort.MAX_VALUE.toLong()) { "LPP value $value does not fit in UInt16" }
    return truncated.toUShort()
}

private fun checkedUInt8(value: Double): UByte {
    val truncated = value.toLong()
    require(truncated in 0..UByte.MAX_VALUE.toLong()) { "LPP value $value does not fit in UInt8" }
    return truncated.toUByte()
}

/**
 * Encodes sensor data into Cayenne Low Power Payload (LPP) format.
 *
 * LPP is a compact binary format for transmitting sensor data over low-bandwidth networks.
 * Each data point is encoded as:
 * - Channel (1 byte): Identifies the sensor instance
 * - Type (1 byte): Sensor type from [LPPSensorType]
 * - Value (variable): Type-specific encoded value
 *
 * Encoded payloads can be decoded using [LPPDecoder].
 */
class LPPEncoder {
    private var buffer: ByteArray = ByteArray(0)

    /** The current encoded payload size in bytes. */
    val count: Int
        get() = buffer.size

    /** Resets the encoder, clearing all buffered data. */
    fun reset() {
        buffer = ByteArray(0)
    }

    /** Returns the encoded payload. */
    fun encode(): ByteArray = buffer

    // MARK: - Digital I/O

    /**
     * Adds a digital input value.
     * @param channel Sensor channel (0-255).
     * @param value Digital value (0 or 1).
     */
    fun addDigitalInput(channel: UByte, value: UByte) {
        buffer += channel.toByte()
        buffer += LPPSensorType.DIGITAL_INPUT.value.toByte()
        buffer += (if (value.toInt() != 0) 1 else 0).toByte()
    }

    /**
     * Adds a digital output value.
     * @param channel Sensor channel (0-255).
     * @param value Digital value (0 or 1).
     */
    fun addDigitalOutput(channel: UByte, value: UByte) {
        buffer += channel.toByte()
        buffer += LPPSensorType.DIGITAL_OUTPUT.value.toByte()
        buffer += (if (value.toInt() != 0) 1 else 0).toByte()
    }

    // MARK: - Analog I/O

    /**
     * Adds an analog input value.
     * @param channel Sensor channel (0-255).
     * @param value Analog value (0.01 resolution, range -327.68 to 327.67).
     */
    fun addAnalogInput(channel: UByte, value: Double) {
        buffer += channel.toByte()
        buffer += LPPSensorType.ANALOG_INPUT.value.toByte()
        appendInt16(checkedInt16(value * 100))
    }

    /**
     * Adds an analog output value.
     * @param channel Sensor channel (0-255).
     * @param value Analog value (0.01 resolution, range -327.68 to 327.67).
     */
    fun addAnalogOutput(channel: UByte, value: Double) {
        buffer += channel.toByte()
        buffer += LPPSensorType.ANALOG_OUTPUT.value.toByte()
        appendInt16(checkedInt16(value * 100))
    }

    // MARK: - Environmental

    /**
     * Adds a temperature reading.
     * @param channel Sensor channel (0-255).
     * @param celsius Temperature in Celsius (0.1 resolution).
     */
    fun addTemperature(channel: UByte, celsius: Double) {
        buffer += channel.toByte()
        buffer += LPPSensorType.TEMPERATURE.value.toByte()
        appendInt16(checkedInt16(celsius * 10))
    }

    /**
     * Adds a humidity reading.
     * @param channel Sensor channel (0-255).
     * @param percent Relative humidity 0-100 (0.5 resolution).
     */
    fun addHumidity(channel: UByte, percent: Double) {
        buffer += channel.toByte()
        buffer += LPPSensorType.HUMIDITY.value.toByte()
        buffer += checkedUInt8(percent * 2).toByte()
    }

    /**
     * Adds a barometric pressure reading.
     * @param channel Sensor channel (0-255).
     * @param hPa Pressure in hectopascals (0.1 resolution).
     */
    fun addBarometer(channel: UByte, hPa: Double) {
        buffer += channel.toByte()
        buffer += LPPSensorType.BAROMETER.value.toByte()
        appendUInt16(checkedUInt16(hPa * 10))
    }

    /**
     * Adds an illuminance reading.
     * @param channel Sensor channel (0-255).
     * @param lux Illuminance in lux (1 lux resolution).
     */
    fun addIlluminance(channel: UByte, lux: UShort) {
        buffer += channel.toByte()
        buffer += LPPSensorType.ILLUMINANCE.value.toByte()
        appendUInt16(lux)
    }

    // MARK: - Motion

    /**
     * Adds an accelerometer reading.
     * @param channel Sensor channel (0-255).
     * @param x X-axis acceleration in G (0.001 resolution).
     * @param y Y-axis acceleration in G.
     * @param z Z-axis acceleration in G.
     */
    fun addAccelerometer(channel: UByte, x: Double, y: Double, z: Double) {
        buffer += channel.toByte()
        buffer += LPPSensorType.ACCELEROMETER.value.toByte()
        appendInt16(checkedInt16(x * 1000))
        appendInt16(checkedInt16(y * 1000))
        appendInt16(checkedInt16(z * 1000))
    }

    /**
     * Adds a gyrometer reading.
     * @param channel Sensor channel (0-255).
     * @param x X-axis rotation in deg/s (0.01 resolution).
     * @param y Y-axis rotation in deg/s.
     * @param z Z-axis rotation in deg/s.
     */
    fun addGyrometer(channel: UByte, x: Double, y: Double, z: Double) {
        buffer += channel.toByte()
        buffer += LPPSensorType.GYROMETER.value.toByte()
        appendInt16(checkedInt16(x * 100))
        appendInt16(checkedInt16(y * 100))
        appendInt16(checkedInt16(z * 100))
    }

    // MARK: - Location

    /**
     * Adds a GPS location.
     * @param channel Sensor channel (0-255).
     * @param latitude Latitude in degrees (-90 to 90, 0.0001 resolution).
     * @param longitude Longitude in degrees (-180 to 180, 0.0001 resolution).
     * @param altitude Altitude in meters (0.01 resolution).
     */
    fun addGPS(channel: UByte, latitude: Double, longitude: Double, altitude: Double) {
        buffer += channel.toByte()
        buffer += LPPSensorType.GPS.value.toByte()
        appendInt24((latitude * 10000).toInt())
        appendInt24((longitude * 10000).toInt())
        appendInt24((altitude * 100).toInt())
    }

    // MARK: - Electrical

    /**
     * Adds a voltage reading.
     * @param channel Sensor channel (0-255).
     * @param volts Voltage in volts (0.01V resolution per MeshCore firmware).
     */
    fun addVoltage(channel: UByte, volts: Double) {
        buffer += channel.toByte()
        buffer += LPPSensorType.VOLTAGE.value.toByte()
        // MeshCore firmware uses 0.01V units (multiplier 100)
        appendUInt16(checkedUInt16(volts * 100))
    }

    /**
     * Adds a current reading.
     * @param channel Sensor channel (0-255).
     * @param milliamps Current in milliamps (0.001A resolution).
     */
    fun addCurrent(channel: UByte, milliamps: UShort) {
        buffer += channel.toByte()
        buffer += LPPSensorType.CURRENT.value.toByte()
        appendUInt16(milliamps)
    }

    // MARK: - Generic

    /**
     * Adds a raw data point.
     * @param channel Sensor channel (0-255).
     * @param type Sensor type.
     * @param data Raw encoded data (must match type's dataSize).
     */
    fun addRaw(channel: UByte, type: LPPSensorType, data: ByteArray) {
        require(data.size == type.dataSize) { "Data size must match sensor type" }
        buffer += channel.toByte()
        buffer += type.value.toByte()
        buffer += data
    }

    // MARK: - Private Helpers (Big-Endian for MeshCore/LPP compatibility)

    private fun appendInt16(value: Short) {
        buffer += ((value.toInt() shr 8) and 0xFF).toByte() // High byte first
        buffer += (value.toInt() and 0xFF).toByte() // Low byte second
    }

    private fun appendUInt16(value: UShort) {
        buffer += ((value.toInt() shr 8) and 0xFF).toByte() // High byte first
        buffer += (value.toInt() and 0xFF).toByte() // Low byte second
    }

    private fun appendInt24(value: Int) {
        // Big-endian 24-bit signed
        buffer += ((value shr 16) and 0xFF).toByte() // High byte first
        buffer += ((value shr 8) and 0xFF).toByte()
        buffer += (value and 0xFF).toByte() // Low byte last
    }
}
