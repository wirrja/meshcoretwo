// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.region.RadioOptions
import com.meshcoretwo.services.region.RadioPresets
import com.meshcoretwo.services.settings.SettingsService
import java.util.Locale
import kotlin.math.roundToLong

/** One validated set of manual radio parameters, as entered in [ManualRadioSettingsDialog]. */
internal data class ManualRadioSettings(
    val frequencyKHz: UInt,
    val bandwidthHz: UInt,
    val spreadingFactor: UByte,
    val codingRate: UByte,
    val txPower: Byte,
    val clientRepeat: Boolean,
    /** `null` when the firmware has no path hash mode ([DeviceDto.supportsPathHashMode] false). */
    val pathHashMode: UByte?,
)

/**
 * Writes [settings] to the radio with verification. Path hash mode is written only when supported
 * and actually changed, so a pre-v10 firmware never sees the command.
 */
internal suspend fun SettingsService.writeManualRadioSettings(settings: ManualRadioSettings, device: DeviceDto) {
    setRadioParamsVerified(
        frequencyKHz = settings.frequencyKHz,
        bandwidthKHz = settings.bandwidthHz,
        spreadingFactor = settings.spreadingFactor,
        codingRate = settings.codingRate,
        clientRepeat = settings.clientRepeat,
    )
    setTxPowerVerified(settings.txPower)
    val pathHashMode = settings.pathHashMode
    if (pathHashMode != null && device.supportsPathHashMode && pathHashMode != device.pathHashMode) {
        setPathHashModeVerified(pathHashMode)
    }
}

/**
 * The device record after [writeManualRadioSettings]. Turning repeat mode on saves the previous
 * radio settings as pre-repeat settings (restored on toggle-off) and clears them when it's turned
 * off. A catalog preset id that no longer matches the live RF is dropped, except in repeat mode —
 * same rule as `SettingsViewModel.patchDevice`.
 */
internal fun DeviceDto.withManualRadioSettings(settings: ManualRadioSettings): DeviceDto {
    val wasRepeat = clientRepeat
    val enteringRepeat = !wasRepeat && settings.clientRepeat
    val leavingRepeat = wasRepeat && !settings.clientRepeat
    val updated = copy(
        frequency = settings.frequencyKHz,
        bandwidth = settings.bandwidthHz,
        spreadingFactor = settings.spreadingFactor,
        codingRate = settings.codingRate,
        txPower = settings.txPower,
        clientRepeat = settings.clientRepeat,
        pathHashMode = if (supportsPathHashMode) settings.pathHashMode ?: pathHashMode else pathHashMode,
        preRepeatFrequency = when {
            enteringRepeat -> frequency
            leavingRepeat -> null
            else -> preRepeatFrequency
        },
        preRepeatBandwidth = when {
            enteringRepeat -> bandwidth
            leavingRepeat -> null
            else -> preRepeatBandwidth
        },
        preRepeatSpreadingFactor = when {
            enteringRepeat -> spreadingFactor
            leavingRepeat -> null
            else -> preRepeatSpreadingFactor
        },
        preRepeatCodingRate = when {
            enteringRepeat -> codingRate
            leavingRepeat -> null
            else -> preRepeatCodingRate
        },
    )
    val applied = updated.appliedRadioPresetID ?: return updated
    val stillMatches = RadioPresets.matchingPresets(updated.frequency, updated.bandwidth, updated.spreadingFactor, updated.codingRate)
        .any { it.id == applied }
    return if (updated.clientRepeat || stillMatches) updated else updated.copy(appliedRadioPresetID = null)
}

/**
 * Manual radio parameter entry, ported from `AdvancedRadioSection.swift`, plus a path hash picker
 * (`PathHashModeSection.swift`) when the firmware supports it. Shared by Settings' Radio
 * subsection ("Manual settings") and onboarding's Region step, which has no iOS counterpart — it
 * lets a user skip the preset list and enter their own parameters on first connect.
 *
 * Frequency/TX power are free-text, validated against [PacketBuilder.FREQUENCY_RANGE_KHZ]/
 * [PacketBuilder.TX_POWER_FLOOR] before enabling Apply — the same non-trapping-conversion checks
 * `AdvancedRadioSection.applySettings()` does. Toggling repeat mode snaps/restores the frequency
 * field the same way Swift's `snapFrequencyForRepeat`/`restoreFrequencyAfterRepeat` do.
 */
@Composable
internal fun ManualRadioSettingsDialog(
    device: DeviceDto,
    onDismiss: () -> Unit,
    onApply: (ManualRadioSettings) -> Unit,
) {
    var frequencyInput by remember { mutableStateOf(String.format(Locale.US, "%.3f", device.frequency.toDouble() / 1000.0)) }
    var bandwidth by remember { mutableStateOf(RadioOptions.nearestBandwidth(device.bandwidth)) }
    var spreadingFactor by remember { mutableStateOf(device.spreadingFactor.toInt()) }
    var codingRate by remember { mutableStateOf(device.codingRate.toInt()) }
    var txPowerInput by remember { mutableStateOf(device.txPower.toString()) }
    var clientRepeat by remember { mutableStateOf(device.clientRepeat) }
    var pathHashMode by remember { mutableStateOf(device.pathHashMode.toInt().coerceIn(0, PATH_HASH_LABELS.lastIndex)) }

    val scaledFreqKHz = frequencyInput.toDoubleOrNull()?.let { (it * 1000).roundToLong() }
    val freqValid = scaledFreqKHz != null && scaledFreqKHz in 0..UInt.MAX_VALUE.toLong() &&
        PacketBuilder.FREQUENCY_RANGE_KHZ.contains(scaledFreqKHz.toUInt())
    val txPower = txPowerInput.toIntOrNull()
    val txPowerValid = txPower != null && txPower >= PacketBuilder.TX_POWER_FLOOR && txPower <= device.maxTxPower

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_radio_config)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = frequencyInput,
                    onValueChange = { frequencyInput = it },
                    label = { Text(stringResource(R.string.rf_frequency_mhz)) },
                    singleLine = true,
                    isError = !freqValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                RadioParamPicker(
                    label = "Bandwidth (kHz)",
                    valueText = RadioOptions.formatBandwidth(bandwidth),
                    options = RadioOptions.bandwidthsHz.map { it to RadioOptions.formatBandwidth(it) },
                    onSelect = { bandwidth = it },
                )
                RadioParamPicker(
                    label = "Spreading Factor",
                    valueText = spreadingFactor.toString(),
                    options = RadioOptions.spreadingFactors.map { it to it.toString() },
                    onSelect = { spreadingFactor = it },
                )
                RadioParamPicker(
                    label = "Coding Rate",
                    valueText = codingRate.toString(),
                    options = RadioOptions.codingRates.map { it to it.toString() },
                    onSelect = { codingRate = it },
                )
                if (device.supportsPathHashMode) {
                    RadioParamPicker(
                        label = "Path hash",
                        valueText = PATH_HASH_LABELS[pathHashMode],
                        options = PATH_HASH_LABELS.mapIndexed { index, label -> index to label },
                        onSelect = { pathHashMode = it },
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = txPowerInput,
                    onValueChange = { input -> txPowerInput = input.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) } },
                    label = { Text("TX Power (dBm)") },
                    singleLine = true,
                    isError = !txPowerValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (device.supportsClientRepeat) {
                    Spacer(modifier = Modifier.size(8.dp))
                    SettingsListRow(
                        title = stringResource(R.string.settings_repeat_mode_label),
                        value = stringResource(R.string.settings_repeat_mode_desc),
                        singleLineValue = false,
                        trailing = {
                            Switch(
                                checked = clientRepeat,
                                onCheckedChange = { enabling ->
                                    clientRepeat = enabling
                                    if (enabling) {
                                        val currentKHz = scaledFreqKHz?.toUInt()
                                        val nearest = currentKHz?.let {
                                            if (RadioPresets.matchingRepeatPreset(it) != null) null else RadioPresets.nearestRepeatPreset(it)
                                        }
                                        if (nearest != null) frequencyInput = String.format(Locale.US, "%.3f", nearest.frequencyMHz)
                                    } else {
                                        val restoredKHz = device.preRepeatFrequency ?: device.frequency
                                        frequencyInput = String.format(Locale.US, "%.3f", restoredKHz.toDouble() / 1000.0)
                                    }
                                },
                            )
                        },
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    stringResource(R.string.settings_radio_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = freqValid && txPowerValid,
                onClick = {
                    onApply(
                        ManualRadioSettings(
                            frequencyKHz = scaledFreqKHz!!.toUInt(),
                            bandwidthHz = bandwidth,
                            spreadingFactor = spreadingFactor.toUByte(),
                            codingRate = codingRate.toUByte(),
                            txPower = txPower!!.toByte(),
                            clientRepeat = clientRepeat,
                            pathHashMode = if (device.supportsPathHashMode) pathHashMode.toUByte() else null,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Labels for path hash mode 0/1/2, shared with Settings' standalone path hash row. */
internal val PATH_HASH_LABELS = listOf("1 byte", "2 bytes", "3 bytes")

@Composable
private fun <T> RadioParamPicker(label: String, valueText: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { expanded = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}
