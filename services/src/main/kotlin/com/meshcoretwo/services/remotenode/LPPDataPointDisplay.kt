// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.LPPDataPoint
import com.meshcoretwo.protocol.LPPSensorType
import com.meshcoretwo.protocol.LPPValue
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Human-readable telemetry display, ported from `LPPDataPoint+Display.swift`. Trimmed of that
 * file's locale-driven metric/imperial unit conversion (`type.convertedValue`/
 * `localizedUnitSymbol`) — no such unit-system preference exists anywhere in this port yet, so
 * values are always shown in the metric units the radio reports them in (°C, meters).
 */

/** Human-readable type name for the sensor channel. */
val LPPSensorType.typeName: String
    get() = when (this) {
        LPPSensorType.DIGITAL_INPUT -> "Digital Input"
        LPPSensorType.DIGITAL_OUTPUT -> "Digital Output"
        LPPSensorType.ANALOG_INPUT -> "Analog Input"
        LPPSensorType.ANALOG_OUTPUT -> "Analog Output"
        LPPSensorType.GENERIC_SENSOR -> "Sensor"
        LPPSensorType.ILLUMINANCE -> "Illuminance"
        LPPSensorType.PRESENCE -> "Presence"
        LPPSensorType.TEMPERATURE -> "Temperature"
        LPPSensorType.HUMIDITY -> "Humidity"
        LPPSensorType.ACCELEROMETER -> "Accelerometer"
        LPPSensorType.BAROMETER -> "Pressure"
        LPPSensorType.VOLTAGE -> "Voltage"
        LPPSensorType.CURRENT -> "Current"
        LPPSensorType.FREQUENCY -> "Frequency"
        LPPSensorType.PERCENTAGE -> "Percentage"
        LPPSensorType.ALTITUDE -> "Altitude"
        LPPSensorType.LOAD -> "Load"
        LPPSensorType.CONCENTRATION -> "Concentration"
        LPPSensorType.POWER -> "Power"
        LPPSensorType.DISTANCE -> "Distance"
        LPPSensorType.ENERGY -> "Energy"
        LPPSensorType.DIRECTION -> "Direction"
        LPPSensorType.UNIX_TIME -> "Time"
        LPPSensorType.GYROMETER -> "Gyrometer"
        LPPSensorType.COLOUR -> "Colour"
        LPPSensorType.GPS -> "GPS"
        LPPSensorType.SWITCH_VALUE -> "Switch"
    }

/** Human-readable type name for this data point's channel, for both display and persisted snapshots. */
val LPPDataPoint.typeName: String
    get() = type.typeName

/** Formatted value with appropriate unit suffix, matching the wire-reported (metric) unit. */
val LPPDataPoint.formattedValue: String
    get() = when (val v = value) {
        is LPPValue.Digital -> if (v.value) "On" else "Off"
        is LPPValue.Integer -> when (type) {
            LPPSensorType.ILLUMINANCE -> "${v.value} lux"
            LPPSensorType.PERCENTAGE -> "${v.value}%"
            else -> v.value.toString()
        }
        is LPPValue.Float -> when (type) {
            LPPSensorType.VOLTAGE -> "%.3f V".format(Locale.US, v.value)
            LPPSensorType.TEMPERATURE -> "%.1f °C".format(Locale.US, v.value)
            LPPSensorType.HUMIDITY -> "%.1f%%".format(Locale.US, v.value)
            LPPSensorType.BAROMETER -> "%.1f hPa".format(Locale.US, v.value)
            LPPSensorType.CURRENT -> "%.3f A".format(Locale.US, v.value)
            LPPSensorType.POWER -> "%.1f W".format(Locale.US, v.value)
            LPPSensorType.FREQUENCY -> "%.1f Hz".format(Locale.US, v.value)
            LPPSensorType.ALTITUDE, LPPSensorType.DISTANCE -> "%.1f m".format(Locale.US, v.value)
            LPPSensorType.ENERGY -> "%.3f kWh".format(Locale.US, v.value)
            LPPSensorType.DIRECTION -> "%.0f°".format(Locale.US, v.value)
            else -> "%.3f".format(Locale.US, v.value)
        }
        is LPPValue.Vector3 -> "(%.2f, %.2f, %.2f)".format(Locale.US, v.x, v.y, v.z)
        is LPPValue.Gps -> "%.5f, %.5f @ %.1fm".format(Locale.US, v.latitude, v.longitude, v.altitude)
        is LPPValue.Rgb -> "RGB(${v.red}, ${v.green}, ${v.blue})"
        is LPPValue.Timestamp ->
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault())
                .format(v.value)
    }

/**
 * Estimated battery percentage from a voltage reading, using a fixed 3.0V-4.2V Li-Ion range.
 * Ported from `LPPDataPoint.batteryPercentage`, trimmed of the OCV-curve lookup that
 * `BatteryInfo.percentage(using:)` provides on iOS — OCV settings aren't ported yet (see
 * `NodeStatusMetrics`'s doc). Null for non-voltage types or non-float values.
 */
val LPPDataPoint.batteryPercentage: Int?
    get() {
        val value = value
        if (type != LPPSensorType.VOLTAGE || value !is LPPValue.Float) return null
        val percentage = (value.value - 3.0) / (4.2 - 3.0) * 100
        return percentage.roundToInt().coerceIn(0, 100)
    }
