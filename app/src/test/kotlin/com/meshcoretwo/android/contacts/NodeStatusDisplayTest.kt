// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.LPPDataPoint
import com.meshcoretwo.protocol.LPPSensorType
import com.meshcoretwo.protocol.LPPValue
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.remotenode.OCVPreset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [NodeStatusDisplay] — the role-independent formatters shared by
 * `RoomStatusViewModelCompanionTest` and `RepeaterStatusViewModelCompanionTest` before this slice,
 * split out here once both consumers existed. Same fixture-builder pattern as
 * `BinaryProtocolServiceTest`'s `statusResponse()`.
 */
class NodeStatusDisplayTest {
    private fun status(
        battery: Int = 3700,
        noiseFloor: Int = -100,
        lastRSSI: Int = -80,
        lastSNR: Double = 5.0,
        uptime: UInt = 3661u,
        airtime: UInt = 30u,
        rxAirtime: UInt = 15u,
        directDuplicates: Int = 1,
        floodDuplicates: Int = 2,
    ) = StatusResponse(
        publicKeyPrefix = ByteArray(6),
        battery = battery,
        txQueueLength = 0,
        noiseFloor = noiseFloor,
        lastRSSI = lastRSSI,
        packetsReceived = 1u,
        packetsSent = 2u,
        airtime = airtime,
        uptime = uptime,
        sentFlood = 5u,
        sentDirect = 6u,
        receivedFlood = 7u,
        receivedDirect = 8u,
        fullEvents = 0,
        lastSNR = lastSNR,
        directDuplicates = directDuplicates,
        floodDuplicates = floodDuplicates,
        rxAirtime = rxAirtime,
    )

    @Test
    fun `formatDuration under an hour shows only minutes`() {
        assertEquals("5m", NodeStatusDisplay.formatDuration(300u))
    }

    @Test
    fun `formatDuration under a day shows hours and minutes`() {
        assertEquals("1h 1m", NodeStatusDisplay.formatDuration(3661u))
    }

    @Test
    fun `formatDuration over a day shows days hours and minutes`() {
        assertEquals("2d 1h 1m", NodeStatusDisplay.formatDuration(2u * 86400u + 3661u))
    }

    @Test
    fun `uptimeDisplay is em dash when status is null`() {
        assertEquals("—", NodeStatusDisplay.uptimeDisplay(null))
    }

    @Test
    fun `uptimeDisplay formats the status uptime`() {
        assertEquals("1h 1m", NodeStatusDisplay.uptimeDisplay(status(uptime = 3661u)))
    }

    @Test
    fun `airtimeDisplay shows TX and RX durations`() {
        val display = NodeStatusDisplay.airtimeDisplay(status(airtime = 125u, rxAirtime = 65u))
        assertEquals("TX 2m / RX 1m", display)
    }

    @Test
    fun `airtimePercentDisplay is em dash when uptime is zero`() {
        assertEquals("—", NodeStatusDisplay.airtimePercentDisplay(status(uptime = 0u)))
    }

    @Test
    fun `airtimePercentDisplay computes tx and rx percentages`() {
        val display = NodeStatusDisplay.airtimePercentDisplay(status(uptime = 100u, airtime = 10u, rxAirtime = 5u))
        assertEquals("TX 10.0% / RX 5.0%", display)
    }

    @Test
    fun `batteryDisplay formats millivolts as volts`() {
        assertEquals("3.700V", NodeStatusDisplay.batteryDisplay(status(battery = 3700)))
    }

    @Test
    fun `lastRSSIDisplay formats dBm`() {
        assertEquals("-80 dBm", NodeStatusDisplay.lastRSSIDisplay(status(lastRSSI = -80)))
    }

    @Test
    fun `lastSNRDisplay formats one decimal`() {
        assertEquals("5.0 dB", NodeStatusDisplay.lastSNRDisplay(status(lastSNR = 5.0)))
    }

    @Test
    fun `noiseFloorDisplay formats dBm`() {
        assertEquals("-100 dBm", NodeStatusDisplay.noiseFloorDisplay(status(noiseFloor = -100)))
    }

    @Test
    fun `duplicatesDisplay sums direct and flood`() {
        assertEquals("3", NodeStatusDisplay.duplicatesDisplay(status(directDuplicates = 1, floodDuplicates = 2)))
    }

    private fun contactDto(ocvPreset: String?, customOCVArrayString: String? = null) = ContactDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = ByteArray(32),
        name = "Test",
        typeRawValue = ContactType.REPEATER.value,
        flags = 0u,
        outPathLength = 0xFFu,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0u,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0u,
        lastHeardTimestamp = 0u,
        nickname = null,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        lastMessageDate = null,
        unreadCount = 0,
        unreadMentionCount = 0,
        ocvPreset = ocvPreset,
        customOCVArrayString = customOCVArrayString,
        avatarImageData = null,
    )

    @Test
    fun `resolveOCVPreset defaults to Li-Ion when unset`() {
        assertEquals(OCVPreset.LI_ION, NodeStatusDisplay.resolveOCVPreset(contactDto(ocvPreset = null)))
    }

    @Test
    fun `resolveOCVPreset resolves a stored preset`() {
        assertEquals(OCVPreset.LI_FE_PO4, NodeStatusDisplay.resolveOCVPreset(contactDto(ocvPreset = OCVPreset.LI_FE_PO4.rawValue)))
    }

    @Test
    fun `resolveOCVPreset resolves custom when the array is valid`() {
        val contact = contactDto(ocvPreset = OCVPreset.CUSTOM.rawValue, customOCVArrayString = "4200,4100,4000,3900,3800,3700,3600,3500,3400,3300,3200")

        assertEquals(OCVPreset.CUSTOM, NodeStatusDisplay.resolveOCVPreset(contact))
    }

    @Test
    fun `resolveOCVPreset still resolves custom when the array is invalid`() {
        assertEquals(OCVPreset.CUSTOM, NodeStatusDisplay.resolveOCVPreset(contactDto(ocvPreset = OCVPreset.CUSTOM.rawValue, customOCVArrayString = "invalid")))
    }

    @Test
    fun `resolveOCVPreset falls back to Li-Ion for an unrecognized raw value`() {
        assertEquals(OCVPreset.LI_ION, NodeStatusDisplay.resolveOCVPreset(contactDto(ocvPreset = "not-a-real-preset")))
    }

    private fun voltageDataPoint(volts: Double) = LPPDataPoint(channel = 1u, type = LPPSensorType.VOLTAGE, value = LPPValue.Float(volts))

    @Test
    fun `ocvBatteryPercentage is null for non-voltage data points`() {
        val dataPoint = LPPDataPoint(channel = 1u, type = LPPSensorType.TEMPERATURE, value = LPPValue.Float(20.0))
        assertNull(NodeStatusDisplay.ocvBatteryPercentage(dataPoint, OCVPreset.LI_ION.ocvArray))
    }

    @Test
    fun `ocvBatteryPercentage is null when the OCV array doesn't have 11 values`() {
        assertNull(NodeStatusDisplay.ocvBatteryPercentage(voltageDataPoint(3.7), listOf(4200, 3000)))
    }

    @Test
    fun `ocvBatteryPercentage clamps at 100 above the max voltage`() {
        assertEquals(100, NodeStatusDisplay.ocvBatteryPercentage(voltageDataPoint(4.3), OCVPreset.LI_ION.ocvArray))
    }

    @Test
    fun `ocvBatteryPercentage clamps at 0 below the min voltage`() {
        assertEquals(0, NodeStatusDisplay.ocvBatteryPercentage(voltageDataPoint(2.9), OCVPreset.LI_ION.ocvArray))
    }

    @Test
    fun `ocvBatteryPercentage interpolates within a segment`() {
        // Li-Ion array: index 8 = 3420mV (20%), index 9 = 3300mV (10%). Midpoint should land near 15%.
        val percent = NodeStatusDisplay.ocvBatteryPercentage(voltageDataPoint(3.360), OCVPreset.LI_ION.ocvArray)
        assertEquals(15, percent)
    }
}
