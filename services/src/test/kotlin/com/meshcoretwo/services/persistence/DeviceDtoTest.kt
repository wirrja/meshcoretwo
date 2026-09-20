// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.services.settings.AdvertLocationPolicy
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDtoTest {
    private fun deviceDto(
        firmwareVersion: UByte,
        firmwareVersionString: String = "v1.16.0",
        pathHashMode: UByte = 0u,
        telemetryModeBase: UByte = 0u,
        telemetryModeLocation: UByte = 0u,
        telemetryModeEnvironment: UByte = 0u,
        advertLocationPolicy: UByte = 0u,
        defaultFloodScopeName: String? = null,
        knownRegions: List<String> = emptyList(),
    ) = DeviceDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = ByteArray(32),
        nodeName = "Radio",
        firmwareVersion = firmwareVersion,
        firmwareVersionString = firmwareVersionString,
        manufacturerName = "Acme",
        buildDate = "2026-01-01",
        maxContacts = 100u,
        maxChannels = 8u,
        frequency = 915_000u,
        bandwidth = 250_000u,
        spreadingFactor = 10u,
        codingRate = 5u,
        txPower = 20,
        maxTxPower = 20,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0u,
        lastConnected = Instant.ofEpochSecond(1000),
        lastContactSync = 0u,
        isActive = false,
        ocvPreset = null,
        customOCVArrayString = null,
        pathHashMode = pathHashMode,
        telemetryModeBase = telemetryModeBase,
        telemetryModeLocation = telemetryModeLocation,
        telemetryModeEnvironment = telemetryModeEnvironment,
        advertLocationPolicy = advertLocationPolicy,
        defaultFloodScopeName = defaultFloodScopeName,
        knownRegions = knownRegions,
    )

    @Test
    fun `traceHashSize is power-of-2 encoding of pathHashMode`() {
        assertEquals(1, deviceDto(firmwareVersion = 9u, pathHashMode = 0u).traceHashSize)
        assertEquals(2, deviceDto(firmwareVersion = 9u, pathHashMode = 1u).traceHashSize)
        assertEquals(4, deviceDto(firmwareVersion = 9u, pathHashMode = 2u).traceHashSize)
    }

    @Test
    fun `supportsTraceHashSizeOverride is true from firmwareVersion 9`() {
        assertTrue(deviceDto(firmwareVersion = 9u).supportsTraceHashSizeOverride)
        assertTrue(deviceDto(firmwareVersion = 10u).supportsTraceHashSizeOverride)
    }

    @Test
    fun `supportsTraceHashSizeOverride is false below firmwareVersion 9`() {
        assertFalse(deviceDto(firmwareVersion = 8u).supportsTraceHashSizeOverride)
        assertFalse(deviceDto(firmwareVersion = 0u).supportsTraceHashSizeOverride)
    }

    @Test
    fun `supportsPathHashMode is true from firmwareVersion 10`() {
        assertTrue(deviceDto(firmwareVersion = 10u).supportsPathHashMode)
        assertTrue(deviceDto(firmwareVersion = 11u).supportsPathHashMode)
    }

    @Test
    fun `supportsPathHashMode is false below firmwareVersion 10`() {
        assertFalse(deviceDto(firmwareVersion = 9u).supportsPathHashMode)
        assertFalse(deviceDto(firmwareVersion = 0u).supportsPathHashMode)
    }

    @Test
    fun `telemetryModes packs the raw base, location, and environment fields`() {
        val device = deviceDto(
            firmwareVersion = 9u,
            telemetryModeBase = 2u,
            telemetryModeLocation = 1u,
            telemetryModeEnvironment = 3u,
        )
        assertEquals(2u.toUByte(), device.telemetryModes.base)
        assertEquals(1u.toUByte(), device.telemetryModes.location)
        assertEquals(3u.toUByte(), device.telemetryModes.environment)
    }

    @Test
    fun `advertLocationPolicyMode interprets the raw byte, falling back to NONE for an unknown value`() {
        assertEquals(
            AdvertLocationPolicy.SHARE,
            deviceDto(firmwareVersion = 9u, advertLocationPolicy = 1u).advertLocationPolicyMode,
        )
        assertEquals(
            AdvertLocationPolicy.NONE,
            deviceDto(firmwareVersion = 9u, advertLocationPolicy = 255u).advertLocationPolicyMode,
        )
    }

    @Test
    fun `supportsDefaultFloodScope is true from firmwareVersion 11`() {
        assertTrue(deviceDto(firmwareVersion = 11u).supportsDefaultFloodScope)
        assertFalse(deviceDto(firmwareVersion = 10u).supportsDefaultFloodScope)
    }

    @Test
    fun `supportsUnscopedFloodSend is true from firmwareVersion 12`() {
        assertTrue(deviceDto(firmwareVersion = 12u).supportsUnscopedFloodSend)
        assertFalse(deviceDto(firmwareVersion = 11u).supportsUnscopedFloodSend)
    }

    @Test
    fun `supportsAdHocRepeaterRequest is true from firmwareVersion 13`() {
        assertTrue(deviceDto(firmwareVersion = 13u, firmwareVersionString = "v1.15.0").supportsAdHocRepeaterRequest)
        assertFalse(deviceDto(firmwareVersion = 12u, firmwareVersionString = "v1.15.0").supportsAdHocRepeaterRequest)
    }

    @Test
    fun `supportsAdHocRepeaterRequest falls back to the version string for the v1_11-style disambiguation window`() {
        assertTrue(deviceDto(firmwareVersion = 8u, firmwareVersionString = "v1.16.0").supportsAdHocRepeaterRequest)
        assertFalse(deviceDto(firmwareVersion = 8u, firmwareVersionString = "v1.15.0").supportsAdHocRepeaterRequest)
    }

    @Test
    fun `toEntity and toDto round-trip defaultFloodScopeName and knownRegions as a comma-joined column`() {
        val device = deviceDto(firmwareVersion = 11u, defaultFloodScopeName = "de-hh", knownRegions = listOf("de-hh", "uk-ldn"))
        val entity = device.toEntity()
        assertEquals("de-hh,uk-ldn", entity.knownRegions)
        assertEquals("de-hh", entity.defaultFloodScopeName)
        assertEquals(device.knownRegions, entity.toDto().knownRegions)
        assertEquals(device.defaultFloodScopeName, entity.toDto().defaultFloodScopeName)
    }

    @Test
    fun `toEntity and toDto round-trip an empty knownRegions list as an empty string, not a list with one empty element`() {
        val device = deviceDto(firmwareVersion = 11u, knownRegions = emptyList())
        val entity = device.toEntity()
        assertEquals("", entity.knownRegions)
        assertEquals(emptyList<String>(), entity.toDto().knownRegions)
    }
}
