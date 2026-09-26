// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.region.RadioPresets
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualRadioSettingsTest {
    private val preset = RadioPresets.all.first()

    @Test
    fun `applies every field including path hash`() {
        val updated = device().withManualRadioSettings(settings(frequencyKHz = 869_525u, pathHashMode = 2u))
        assertEquals(869_525u, updated.frequency)
        assertEquals(250_000u, updated.bandwidth)
        assertEquals(11.toUByte(), updated.spreadingFactor)
        assertEquals(17.toByte(), updated.txPower)
        assertEquals(2.toUByte(), updated.pathHashMode)
    }

    @Test
    fun `path hash is left alone on firmware without path hash mode`() {
        val updated = device(firmwareVersion = 9u).withManualRadioSettings(settings(pathHashMode = 2u))
        assertEquals(0.toUByte(), updated.pathHashMode)
    }

    @Test
    fun `entering repeat saves previous radio settings and leaving clears them`() {
        val original = device()
        val repeating = original.withManualRadioSettings(settings(frequencyKHz = 869_000u, clientRepeat = true))
        assertEquals(original.frequency, repeating.preRepeatFrequency)
        assertEquals(original.bandwidth, repeating.preRepeatBandwidth)
        assertEquals(original.spreadingFactor, repeating.preRepeatSpreadingFactor)
        assertEquals(original.codingRate, repeating.preRepeatCodingRate)

        val back = repeating.withManualRadioSettings(settings(clientRepeat = false))
        assertNull(back.preRepeatFrequency)
        assertNull(back.preRepeatCodingRate)
    }

    @Test
    fun `preset id survives when RF still matches it and is dropped otherwise`() {
        val onPreset = device().copy(appliedRadioPresetID = preset.id)
        val same = onPreset.withManualRadioSettings(
            settings(
                frequencyKHz = (preset.frequencyMHz * 1000).toUInt(),
                bandwidthHz = (preset.bandwidthKHz * 1000).toUInt(),
                spreadingFactor = preset.spreadingFactor,
                codingRate = preset.codingRate,
            ),
        )
        assertEquals(preset.id, same.appliedRadioPresetID)

        val custom = onPreset.withManualRadioSettings(settings(frequencyKHz = 433_100u, bandwidthHz = 7_800u))
        assertNull(custom.appliedRadioPresetID)
    }

    private fun settings(
        frequencyKHz: UInt = 915_000u,
        bandwidthHz: UInt = 250_000u,
        spreadingFactor: UByte = 11u,
        codingRate: UByte = 5u,
        clientRepeat: Boolean = false,
        pathHashMode: UByte? = null,
    ) = ManualRadioSettings(frequencyKHz, bandwidthHz, spreadingFactor, codingRate, txPower = 17, clientRepeat, pathHashMode)

    private fun device(firmwareVersion: UByte = 10u) = DeviceDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = ByteArray(32),
        nodeName = "Node",
        firmwareVersion = firmwareVersion,
        firmwareVersionString = "v1.16",
        manufacturerName = "Test",
        buildDate = "2026-01-01",
        maxContacts = 100u,
        maxChannels = 10u,
        frequency = 915_000u,
        bandwidth = 250_000u,
        spreadingFactor = 10u,
        codingRate = 5u,
        txPower = 20,
        maxTxPower = 22,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0u,
        lastConnected = Instant.EPOCH,
        lastContactSync = 0u,
        isActive = true,
        ocvPreset = null,
        customOCVArrayString = null,
    )
}
