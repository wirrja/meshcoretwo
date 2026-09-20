// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// MARK: - MMA Parser

/** Specialized parser for MMA (Min/Max/Average) sensor data. */
object MMAParser {
    /**
     * Parses MMA entries from binary protocol data.
     *
     * ### Binary Format
     * `[channel:1][type:1][min:N][max:N][avg:N]`... where N is sensor data size.
     *
     * LPP sensor values use **Big-Endian** byte order.
     */
    fun parse(data: ByteArray): List<MMAEntry> {
        val entries = mutableListOf<MMAEntry>()
        var offset = 0

        while (offset < data.size) {
            if (offset + 2 > data.size) break

            val channel = data[offset].toUByte()
            val typeCode = data[offset + 1].toUByte()
            offset += 2

            val sensorType = LPPSensorType.fromValue(typeCode) ?: break

            val valueSize = sensorType.dataSize
            if (offset + valueSize * 3 > data.size) break

            val minData = data.copyOfRange(offset, offset + valueSize); offset += valueSize
            val maxData = data.copyOfRange(offset, offset + valueSize); offset += valueSize
            val avgData = data.copyOfRange(offset, offset + valueSize); offset += valueSize

            entries.add(
                MMAEntry(
                    channel = channel,
                    type = sensorType.displayName,
                    min = decodeToDouble(sensorType, minData),
                    max = decodeToDouble(sensorType, maxData),
                    avg = decodeToDouble(sensorType, avgData),
                ),
            )
        }

        return entries
    }

    /** Decodes an LPP value to a double for MMA entries. */
    private fun decodeToDouble(type: LPPSensorType, data: ByteArray): Double = when (type) {
        LPPSensorType.DIGITAL_INPUT, LPPSensorType.DIGITAL_OUTPUT, LPPSensorType.PRESENCE, LPPSensorType.SWITCH_VALUE ->
            (data[0].toInt() and 0xFF).toDouble()

        LPPSensorType.PERCENTAGE -> (data[0].toInt() and 0xFF).toDouble()

        LPPSensorType.HUMIDITY -> (data[0].toInt() and 0xFF) * 0.5

        LPPSensorType.TEMPERATURE -> readInt16BE(data).toDouble() / 10.0

        LPPSensorType.BAROMETER -> readUInt16BE(data).toDouble() / 10.0

        LPPSensorType.VOLTAGE -> readUInt16BE(data).toDouble() / 100.0

        LPPSensorType.CURRENT -> readUInt16BE(data).toDouble() / 1000.0

        LPPSensorType.ILLUMINANCE, LPPSensorType.CONCENTRATION, LPPSensorType.POWER, LPPSensorType.DIRECTION ->
            readUInt16BE(data).toDouble()

        LPPSensorType.ALTITUDE -> readInt16BE(data).toDouble()

        LPPSensorType.LOAD -> readInt24BE(data).toDouble() / 1000.0

        LPPSensorType.ANALOG_INPUT, LPPSensorType.ANALOG_OUTPUT -> readInt16BE(data).toDouble() / 100.0

        LPPSensorType.GENERIC_SENSOR -> readUInt32BE(data).toDouble()

        LPPSensorType.FREQUENCY -> readUInt32BE(data).toDouble()

        LPPSensorType.DISTANCE, LPPSensorType.ENERGY -> readUInt32BE(data).toDouble() / 1000.0

        LPPSensorType.UNIX_TIME -> readUInt32BE(data).toDouble()

        LPPSensorType.ACCELEROMETER, LPPSensorType.GYROMETER, LPPSensorType.COLOUR, LPPSensorType.GPS ->
            // Complex types - return first component only for MMA
            readInt16BE(data).toDouble() / (if (type == LPPSensorType.ACCELEROMETER) 1000.0 else 100.0)
    }

    // MARK: - Big-Endian reading helpers (duplicated from LPPDecoder, matching the Swift source's
    // own per-file private duplication rather than sharing across files)

    private fun readInt16BE(data: ByteArray, offset: Int = 0): Short {
        if (offset + 2 > data.size) return 0
        val hi = data[offset].toInt() and 0xFF
        val lo = data[offset + 1].toInt() and 0xFF
        return ((hi shl 8) or lo).toShort()
    }

    private fun readInt24BE(data: ByteArray, offset: Int = 0): Int {
        if (offset + 3 > data.size) return 0
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        var value = (b0 shl 16) or (b1 shl 8) or b2
        if (value and 0x800000 != 0) {
            value = value or -0x1000000
        }
        return value
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
}
