// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.protocol.LPPDataPoint
import com.meshcoretwo.protocol.LPPSensorType
import com.meshcoretwo.protocol.LPPValue
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.remotenode.OCVPreset
import java.util.Locale

/**
 * Display formatters shared by [RoomStatusViewModel] and `RepeaterStatusViewModel` — the part of
 * Swift's `NodeStatusViewModel` helper that doesn't vary by role. Split out in the
 * "RepeaterStatusScreen" slice, once a second consumer existed to justify it (see
 * `RoomStatusViewModel`'s original class doc, which deferred this extraction for "RoomStatusScreen"
 * alone). Role-only formatters (`postsReceivedDisplay`/`postsPushedDisplay` for Room,
 * `receiveErrorsDisplay` for Repeater) stay in each view model's own companion.
 */
object NodeStatusDisplay {
    const val EM_DASH = "—"
    private const val SECONDS_PER_MINUTE = 60u
    private const val SECONDS_PER_HOUR = 3600u
    private const val SECONDS_PER_DAY = 86400u

    fun uptimeDisplay(status: StatusResponse?): String = status?.let { formatDuration(it.uptime) } ?: EM_DASH

    fun airtimeDisplay(status: StatusResponse?): String {
        status ?: return EM_DASH
        return "TX ${formatDuration(status.airtime)} / RX ${formatDuration(status.rxAirtime)}"
    }

    fun airtimePercentDisplay(status: StatusResponse?): String {
        if (status == null || status.uptime == 0u) return EM_DASH
        val denom = status.uptime.toDouble()
        val txPercent = status.airtime.toDouble() / denom * 100
        val rxPercent = status.rxAirtime.toDouble() / denom * 100
        return "TX ${formatPercent(txPercent)} / RX ${formatPercent(rxPercent)}"
    }

    fun batteryDisplay(status: StatusResponse?): String {
        status ?: return EM_DASH
        val volts = status.battery / 1000.0
        return "%.3fV".format(Locale.US, volts)
    }

    fun lastRSSIDisplay(status: StatusResponse?): String = status?.let { "${it.lastRSSI} dBm" } ?: EM_DASH

    fun lastSNRDisplay(status: StatusResponse?): String = status?.let { "%.1f dB".format(Locale.US, it.lastSNR) } ?: EM_DASH

    fun noiseFloorDisplay(status: StatusResponse?): String = status?.let { "${it.noiseFloor} dBm" } ?: EM_DASH

    fun duplicatesDisplay(status: StatusResponse?): String =
        status?.let { (it.directDuplicates + it.floodDuplicates).toString() } ?: EM_DASH

    /**
     * Resolves the [OCVPreset] a contact's stored OCV fields select, matching the branching in
     * [ContactDto.activeOCVArray] (`Contact.activeOCVArray`, Swift) — used alongside that property
     * so the battery-curve picker's selection and the array it displays never disagree. Ported from
     * the resolution `NodeStatusViewModel.loadOCVSettings` (Swift) performs inline.
     */
    fun resolveOCVPreset(contact: ContactDto): OCVPreset {
        val presetName = contact.ocvPreset ?: return OCVPreset.LI_ION
        if (presetName == OCVPreset.CUSTOM.rawValue) {
            val parsed = contact.customOCVArrayString?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            if (parsed?.size == 11) return OCVPreset.CUSTOM
        }
        return OCVPreset.fromRawValue(presetName) ?: OCVPreset.LI_ION
    }

    /**
     * Battery percentage for a voltage telemetry data point using OCV-array lookup with linear
     * interpolation, shown alongside [com.meshcoretwo.services.remotenode.formattedValue] in the
     * telemetry section (unlike [com.meshcoretwo.services.remotenode.batteryPercentage]'s fixed
     * 3.0-4.2V range, used elsewhere as the OCV-unaware fallback). Ported from
     * `BatteryInfo.percentage(using:)` (`BatteryInfo+Display.swift`). Returns `null` for non-voltage
     * data points or an [ocvArray] not carrying exactly 11 values.
     */
    fun ocvBatteryPercentage(dataPoint: LPPDataPoint, ocvArray: List<Int>): Int? {
        if (dataPoint.type != LPPSensorType.VOLTAGE) return null
        val voltage = (dataPoint.value as? LPPValue.Float)?.value ?: return null
        if (ocvArray.size != 11) return null
        val millivolts = (voltage * 1000).toInt()

        if (millivolts >= ocvArray[0]) return 100
        if (millivolts <= ocvArray[10]) return 0

        for (index in 0 until 10) {
            val upperV = ocvArray[index]
            val lowerV = ocvArray[index + 1]
            if (millivolts >= lowerV) {
                val segmentPercent = (millivolts - lowerV).toDouble() / (upperV - lowerV)
                val basePercent = (10 - index - 1) * 10
                return basePercent + (segmentPercent * 10).let { Math.round(it).toInt() }
            }
        }
        return 0
    }

    private fun formatPercent(value: Double): String = "%.1f%%".format(Locale.US, value)

    fun formatDuration(seconds: UInt): String {
        val days = seconds / SECONDS_PER_DAY
        val hours = (seconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
        val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE

        return when {
            days > 0u -> "${days}d ${hours}h ${minutes}m"
            hours > 0u -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}
