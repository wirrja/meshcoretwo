// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * Port of the SelfInfo/DeviceInfo/CustomVars round-trip cases from RoundTripTests.swift and all
 * of DeviceInfoParsingTests.swift.
 */
class DeviceParsersTest {
    @Test
    fun `SelfInfo round trip`() {
        val advType: UByte = 1u
        val txPower: Byte = 20
        val maxTxPower: Byte = 30
        val publicKey = ByteArray(32) { 0xBB.toByte() }
        val lat = 37_774_900
        val lon = -122_419_400
        val multiAcks: UByte = 1u
        val advLocPolicy: UByte = 2u
        // env=0 (bits 5-4), loc=1 (bits 3-2), base=2 (bits 1-0)
        val telemetryMode = (((0 and 0b11) shl 4) or ((1 and 0b11) shl 2) or (2 and 0b11)).toUByte()
        val manualAdd: UByte = 1u
        val radioFreq = 906_875u
        val radioBW = 250_000u
        val radioSF: UByte = 11u
        val radioCR: UByte = 8u
        val name = "MyNode"

        var data = byteArrayOf(advType.toByte(), txPower, maxTxPower)
        data += publicKey
        data += lat.toLittleEndianBytes()
        data += lon.toLittleEndianBytes()
        data += byteArrayOf(multiAcks.toByte(), advLocPolicy.toByte(), telemetryMode.toByte(), manualAdd.toByte())
        data += radioFreq.toLittleEndianBytes()
        data += radioBW.toLittleEndianBytes()
        data += byteArrayOf(radioSF.toByte(), radioCR.toByte())
        data += name.toByteArray(Charsets.UTF_8)

        val event = SelfInfoParser.parse(data)
        val info = (event as? MeshEvent.SelfInfoEvent)?.info ?: run {
            fail("Expected SelfInfoEvent, got $event")
            return
        }

        assertEquals(advType, info.advertisementType)
        assertEquals(txPower, info.txPower)
        assertEquals(maxTxPower, info.maxTxPower)
        assertArrayEquals(publicKey, info.publicKey)
        assertTrue(abs(info.latitude - 37.7749) <= 0.0001)
        assertTrue(abs(info.longitude - -122.4194) <= 0.0001)
        assertEquals(multiAcks, info.multiAcks)
        assertEquals(advLocPolicy, info.advertisementLocationPolicy)
        assertEquals(0u.toUByte(), info.telemetryModeEnvironment)
        assertEquals(1u.toUByte(), info.telemetryModeLocation)
        assertEquals(2u.toUByte(), info.telemetryModeBase)
        assertTrue(info.manualAddContacts)
        assertTrue(abs(info.radioFrequency - 906.875) <= 0.001)
        assertTrue(abs(info.radioBandwidth - 250.0) <= 0.001)
        assertEquals(radioSF, info.radioSpreadingFactor)
        assertEquals(radioCR, info.radioCodingRate)
        assertEquals("MyNode", info.name)
    }

    @Test
    fun `SelfInfo negative TX power round trip`() {
        var data = byteArrayOf(1, -5, 30)
        data += ByteArray(32) { 0xCC.toByte() }
        data += 0.toLittleEndianBytes()
        data += 0.toLittleEndianBytes()
        data += byteArrayOf(0, 0, 0, 0)
        data += 915_000u.toLittleEndianBytes()
        data += 250_000u.toLittleEndianBytes()
        data += byteArrayOf(10, 5)
        data += "NegPwr".toByteArray(Charsets.UTF_8)

        val event = SelfInfoParser.parse(data)
        val info = (event as? MeshEvent.SelfInfoEvent)?.info ?: run {
            fail("Expected SelfInfoEvent, got $event")
            return
        }

        assertEquals(-5, info.txPower.toInt())
        assertEquals(30, info.maxTxPower.toInt())
        assertEquals("NegPwr", info.name)
    }

    @Test
    fun `SelfInfo parses minimum-length payload without device name`() {
        var data = byteArrayOf(0x01, 20, 30)
        data += ByteArray(32) { 0xAA.toByte() }
        data += 0.toLittleEndianBytes()
        data += 0.toLittleEndianBytes()
        data += byteArrayOf(0x02, 0x01, 0x00, 0x01)
        data += 915_000u.toLittleEndianBytes()
        data += 250_000u.toLittleEndianBytes()
        data += byteArrayOf(0x0A, 0x05)

        assertEquals(57, data.size)

        val event = SelfInfoParser.parse(data)
        val info = (event as? MeshEvent.SelfInfoEvent)?.info ?: run {
            fail("Expected SelfInfoEvent, got $event")
            return
        }

        assertEquals(0x0Au.toUByte(), info.radioSpreadingFactor)
        assertEquals(0x05u.toUByte(), info.radioCodingRate)
        assertTrue(info.name.isEmpty())
    }

    @Test
    fun `SelfInfo rejects truncated fixed-width payload`() {
        var data = byteArrayOf(0x01, 20, 30)
        data += ByteArray(32) { 0xAA.toByte() }
        data += 0.toLittleEndianBytes()
        data += 0.toLittleEndianBytes()
        data += byteArrayOf(0x02, 0x01, 0x00, 0x01)
        data += 915_000u.toLittleEndianBytes()
        data += 250_000u.toLittleEndianBytes()

        assertEquals(55, data.size)

        val event = SelfInfoParser.parse(data)
        val failure = event as? MeshEvent.ParseFailure ?: run {
            fail("Expected ParseFailure event, got $event")
            return
        }
        assertTrue(failure.reason.contains("SelfInfo response too short"))
    }

    // MARK: - DeviceInfo

    private fun buildV3Payload(
        fwVer: UByte = 10u,
        maxContactsHalf: UByte = 50u,
        maxChannels: UByte = 8u,
        blePin: UInt = 123_456u,
        fwBuild: String = "2025-01-01",
        model: String = "T-Deck",
        version: String = "1.12.0",
        includeClientRepeat: Boolean = true,
        clientRepeat: UByte = 1u,
        includePathHashMode: Boolean = true,
        pathHashMode: UByte = 0u,
    ): ByteArray {
        var data = byteArrayOf(fwVer.toByte(), maxContactsHalf.toByte(), maxChannels.toByte())
        data += blePin.toLittleEndianBytes()
        data += fwBuild.toByteArray(Charsets.UTF_8).paddedOrTruncated(12)
        data += model.toByteArray(Charsets.UTF_8).paddedOrTruncated(40)
        data += version.toByteArray(Charsets.UTF_8).paddedOrTruncated(20)
        if (includeClientRepeat) data += clientRepeat.toByte()
        if (includePathHashMode) data += pathHashMode.toByte()
        return data
    }

    @Test
    fun `DeviceInfo v9 client repeat round trip`() {
        val data = buildV3Payload(fwVer = 9u, blePin = 123_456u, fwBuild = "15 Feb 2026", version = "1.13.0", includePathHashMode = false)
        assertEquals(80, data.size)

        val event = DeviceInfoParser.parse(data)
        val caps = (event as? MeshEvent.DeviceInfo)?.capabilities ?: run {
            fail("Expected DeviceInfo event, got $event")
            return
        }

        assertEquals(9u.toUByte(), caps.firmwareVersion)
        assertEquals(100, caps.maxContacts)
        assertEquals(8, caps.maxChannels)
        assertEquals(123_456u, caps.blePin)
        assertTrue(caps.clientRepeat)
    }

    @Test
    fun `DeviceInfo v10 pathHashMode round trip`() {
        val data = buildV3Payload(
            fwVer = 10u, blePin = 654_321u, fwBuild = "20 Feb 2026", version = "1.14.0", pathHashMode = 2u,
        )
        assertEquals(81, data.size)

        val event = DeviceInfoParser.parse(data)
        val caps = (event as? MeshEvent.DeviceInfo)?.capabilities ?: run {
            fail("Expected DeviceInfo event, got $event")
            return
        }

        assertEquals(10u.toUByte(), caps.firmwareVersion)
        assertEquals(100, caps.maxContacts)
        assertEquals(8, caps.maxChannels)
        assertEquals(654_321u, caps.blePin)
        assertTrue(caps.clientRepeat)
        assertEquals(2u.toUByte(), caps.pathHashMode)
    }

    @Test
    fun `Full v10 payload parses all fields`() {
        val data = buildV3Payload()
        val caps = (DeviceInfoParser.parse(data) as? MeshEvent.DeviceInfo)?.capabilities ?: run {
            fail("Expected DeviceInfo event")
            return
        }
        assertEquals(10u.toUByte(), caps.firmwareVersion)
        assertEquals(100, caps.maxContacts)
        assertEquals(8, caps.maxChannels)
        assertEquals(123_456u, caps.blePin)
        assertTrue(caps.clientRepeat)
        assertEquals(0u.toUByte(), caps.pathHashMode)
    }

    @Test
    fun `v9 payload missing client_repeat byte defaults to false`() {
        val data = buildV3Payload(fwVer = 9u, includeClientRepeat = false, includePathHashMode = false)
        assertEquals(79, data.size)
        val caps = (DeviceInfoParser.parse(data) as? MeshEvent.DeviceInfo)?.capabilities ?: run {
            fail("Expected DeviceInfo event")
            return
        }
        assertEquals(9u.toUByte(), caps.firmwareVersion)
        assertEquals(100, caps.maxContacts)
        assertFalse(caps.clientRepeat)
    }

    @Test
    fun `v10 payload missing pathHashMode byte defaults to 0`() {
        val data = buildV3Payload(fwVer = 10u, includePathHashMode = false)
        assertEquals(80, data.size)
        val caps = (DeviceInfoParser.parse(data) as? MeshEvent.DeviceInfo)?.capabilities ?: run {
            fail("Expected DeviceInfo event")
            return
        }
        assertEquals(10u.toUByte(), caps.firmwareVersion)
        assertTrue(caps.clientRepeat)
        assertEquals(0u.toUByte(), caps.pathHashMode)
        assertEquals(1, caps.hashSize)
    }

    @Test
    fun `v10 payload missing both extension bytes defaults both`() {
        val data = buildV3Payload(fwVer = 10u, includeClientRepeat = false, includePathHashMode = false)
        assertEquals(79, data.size)
        val caps = (DeviceInfoParser.parse(data) as? MeshEvent.DeviceInfo)?.capabilities ?: run {
            fail("Expected DeviceInfo event")
            return
        }
        assertEquals(10u.toUByte(), caps.firmwareVersion)
        assertEquals(100, caps.maxContacts)
        assertFalse(caps.clientRepeat)
        assertEquals(0u.toUByte(), caps.pathHashMode)
    }

    @Test
    fun `v3+ payload shorter than 79 bytes is rejected`() {
        var data = byteArrayOf(10)
        data += ByteArray(9)
        assertTrue(DeviceInfoParser.parse(data) is MeshEvent.ParseFailure)
    }

    @Test
    fun `Empty payload still returns parseFailure`() {
        assertTrue(DeviceInfoParser.parse(ByteArray(0)) is MeshEvent.ParseFailure)
    }

    @Test
    fun `Pre-v3 firmware parses without v3 fields`() {
        val data = byteArrayOf(2)
        val caps = (DeviceInfoParser.parse(data) as? MeshEvent.DeviceInfo)?.capabilities ?: run {
            fail("Expected DeviceInfo event for pre-v3 firmware")
            return
        }
        assertEquals(2u.toUByte(), caps.firmwareVersion)
        assertEquals(0, caps.maxContacts)
        assertEquals("", caps.model)
    }

    @Test
    fun `DeviceInfo v8 no client repeat round trip`() {
        val data = buildV3Payload(fwVer = 8u, maxContactsHalf = 25u, maxChannels = 4u, blePin = 0u, fwBuild = "", model = "", version = "", includeClientRepeat = false, includePathHashMode = false)
        assertEquals(79, data.size)
        val caps = (DeviceInfoParser.parse(data) as? MeshEvent.DeviceInfo)?.capabilities ?: run {
            fail("Expected DeviceInfo event")
            return
        }
        assertEquals(8u.toUByte(), caps.firmwareVersion)
        assertFalse(caps.clientRepeat)
    }

    // MARK: - CustomVars

    @Test
    fun `CustomVars round trip`() {
        val varString = "key1:value1,key2:value2,mode:auto"
        val data = varString.toByteArray(Charsets.UTF_8)

        val event = CustomVarsParser.parse(data)
        val vars = (event as? MeshEvent.CustomVars)?.vars ?: run {
            fail("Expected CustomVars event, got $event")
            return
        }

        assertEquals("value1", vars["key1"])
        assertEquals("value2", vars["key2"])
        assertEquals("auto", vars["mode"])
    }
}
