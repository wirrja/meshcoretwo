// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import com.meshcoretwo.services.persistence.DeviceDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class DeviceSelectionFilterTest {
    private fun deviceDto(
        bleAddress: String? = null,
        wifiHost: String? = null,
        isGhost: Boolean = false,
    ) = DeviceDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = ByteArray(32),
        nodeName = "Radio",
        firmwareVersion = 16u,
        firmwareVersionString = "v1.16.0",
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
        bleAddress = bleAddress,
        wifiHost = wifiHost,
        isGhost = isGhost,
    )

    @Test
    fun `ble-reachable device is selectable`() {
        assertTrue(DeviceSelectionFilter.isSelectable(deviceDto(bleAddress = "AA:BB:CC:DD:EE:FF")))
    }

    @Test
    fun `wifi-reachable device is selectable`() {
        assertTrue(DeviceSelectionFilter.isSelectable(deviceDto(wifiHost = "192.168.1.5")))
    }

    @Test
    fun `device with both connection methods is selectable`() {
        assertTrue(DeviceSelectionFilter.isSelectable(deviceDto(bleAddress = "AA:BB:CC:DD:EE:FF", wifiHost = "192.168.1.5")))
    }

    @Test
    fun `device with no connection method is not selectable`() {
        assertFalse(DeviceSelectionFilter.isSelectable(deviceDto()))
    }

    @Test
    fun `ghost device is never selectable, even with a stale connection method`() {
        assertFalse(DeviceSelectionFilter.isSelectable(deviceDto(bleAddress = "AA:BB:CC:DD:EE:FF", isGhost = true)))
    }
}
