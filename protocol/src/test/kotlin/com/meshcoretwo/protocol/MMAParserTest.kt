// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** No direct Swift unit test file for MMAParser (only exercised via Session integration tests). */
class MMAParserTest {
    @Test
    fun `parses a temperature entry (big-endian, 2-byte values)`() {
        // channel(1) + type(0x67=temperature) + min/max/avg as int16 BE, *10
        var data = byteArrayOf(0x01, LPPSensorType.TEMPERATURE.value.toByte())
        data += shortBE(150) // 15.0C
        data += shortBE(250) // 25.0C
        data += shortBE(200) // 20.0C

        val entries = MMAParser.parse(data)

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(1u.toUByte(), entry.channel)
        assertEquals("Temperature", entry.type)
        assertTrue(abs(entry.min - 15.0) <= 0.001)
        assertTrue(abs(entry.max - 25.0) <= 0.001)
        assertTrue(abs(entry.avg - 20.0) <= 0.001)
    }

    @Test
    fun `parses a humidity entry (1-byte values)`() {
        // humidity: 1 byte per value, *0.5 resolution
        var data = byteArrayOf(0x02, LPPSensorType.HUMIDITY.value.toByte())
        data += byteArrayOf(100, 140.toByte(), 120) // 50.0%, 70.0%, 60.0%

        val entries = MMAParser.parse(data)

        assertEquals(1, entries.size)
        assertTrue(abs(entries[0].min - 50.0) <= 0.001)
        assertTrue(abs(entries[0].max - 70.0) <= 0.001)
        assertTrue(abs(entries[0].avg - 60.0) <= 0.001)
    }

    @Test
    fun `multiple entries in sequence`() {
        var data = byteArrayOf(0x01, LPPSensorType.TEMPERATURE.value.toByte())
        data += shortBE(150)
        data += shortBE(250)
        data += shortBE(200)
        data += byteArrayOf(0x02, LPPSensorType.HUMIDITY.value.toByte())
        data += byteArrayOf(100, 140.toByte(), 120)

        val entries = MMAParser.parse(data)
        assertEquals(2, entries.size)
        assertEquals("Temperature", entries[0].type)
        assertEquals("Humidity", entries[1].type)
    }

    @Test
    fun `stops at unknown sensor type`() {
        val data = byteArrayOf(0x01, 0xFE.toByte()) // 0xFE is not a defined LPPSensorType
        assertEquals(0, MMAParser.parse(data).size)
    }

    @Test
    fun `stops at a truncated trailing entry`() {
        // Declares temperature (needs 2*3=6 bytes) but only 3 remain
        var data = byteArrayOf(0x01, LPPSensorType.TEMPERATURE.value.toByte())
        data += byteArrayOf(0x00, 0x0F, 0x00)

        assertEquals(0, MMAParser.parse(data).size)
    }

    private fun shortBE(value: Short): ByteArray = byteArrayOf(((value.toInt() shr 8) and 0xFF).toByte(), (value.toInt() and 0xFF).toByte())
}
