// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.ui.i18n.UiText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.services.remotenode.OCVPreset
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Battery curve configuration section content — a trimmed port of `BatteryCurveSection.swift`/
 * `BatteryCurveChart.swift`, shared by [RoomStatusScreen]/[RepeaterStatusScreen] (the way Swift
 * shares one `BatteryCurveSection` view between `RoomStatusView`/`RepeaterStatusView` through
 * `NodeBatteryCurveDisclosureSection`).
 *
 * Simplified relative to Swift:
 * - The 11-value editor commits with one explicit "Save Curve" button rather than per-field
 *   commit-on-focus-loss (`VoltageField`'s `@FocusState`/`onSubmit` machinery) — same effect
 *   (a custom curve is validated then saved as one unit), fewer moving parts.
 * - [BatteryCurveChart] draws straight segments between the 11 points on a plain
 *   [androidx.compose.foundation.Canvas] instead of Swift Charts' monotone-interpolated
 *   `AreaMark`/`LineMark` — no charting library exists in this port (see PLAN.md's
 *   deltas/history deferral for the same reason), and this one duplicate-free custom curve
 *   doesn't warrant introducing one.
 * - No connection-state gating: unlike Swift's `isDisabled: connectionState != .ready`, nothing
 *   else on this screen (reload buttons included) is gated on connection readiness yet.
 */
@Composable
fun BatteryCurveSectionContent(
    availablePresets: List<OCVPreset>,
    selectedPreset: OCVPreset,
    voltageValues: List<Int>,
    onSelectPreset: (OCVPreset) -> Unit,
    onCommitCustomValues: (List<Int>) -> Unit,
    error: UiText?,
) {
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var editingValues by remember { mutableStateOf(false) }
    var localValues by remember(voltageValues) { mutableStateOf(voltageValues) }
    var validationError by remember(voltageValues) { mutableStateOf<UiText?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { presetMenuExpanded = true }) { Text(selectedPreset.displayName) }
            DropdownMenu(expanded = presetMenuExpanded, onDismissRequest = { presetMenuExpanded = false }) {
                availablePresets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.displayName) },
                        onClick = {
                            presetMenuExpanded = false
                            editingValues = false
                            onSelectPreset(preset)
                        },
                    )
                }
            }
        }

        BatteryCurveChart(ocvArray = voltageValues)

        Row(
            modifier = Modifier.fillMaxWidth().clickable { editingValues = !editingValues },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.battery_edit_values), style = MaterialTheme.typography.bodyMedium)
            Icon(painterResource(if (editingValues) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null)
        }

        if (editingValues) {
            VoltageFieldsGrid(values = localValues, onValueChange = { index, value -> localValues = localValues.toMutableList().also { it[index] = value } })
            TextButton(onClick = {
                val validationMessage = validateVoltageValues(localValues)
                validationError = validationMessage
                if (validationMessage == null && localValues != voltageValues) {
                    onCommitCustomValues(localValues)
                    editingValues = false
                }
            }) { Text(stringResource(R.string.battery_save_curve)) }
        }

        validationError?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        error?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** Ported from `BatteryCurveSection.validateVoltageValues`. */
private fun validateVoltageValues(values: List<Int>): UiText? {
    for ((index, value) in values.withIndex()) {
        if (value !in OCVPreset.VALID_MILLIVOLT_RANGE) {
            val percent = (10 - index) * 10
            return UiText.of(R.string.battery_err_range, "$percent%", OCVPreset.VALID_MILLIVOLT_RANGE.first, OCVPreset.VALID_MILLIVOLT_RANGE.last)
        }
    }
    for (i in 0 until values.size - 1) {
        if (values[i] <= values[i + 1]) return UiText.of(R.string.battery_err_decrease, "100%", "0%")
    }
    return null
}

@Composable
private fun VoltageFieldsGrid(values: List<Int>, onValueChange: (Int, Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().height(220.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(values.size) { index ->
            val percent = (10 - index) * 10
            var text by remember(values[index]) { mutableStateOf(values[index].toString()) }
            OutlinedTextField(
                value = text,
                onValueChange = { newValue ->
                    text = newValue.filter(Char::isDigit)
                    text.toIntOrNull()?.let { onValueChange(index, it) }
                },
                label = { Text("$percent%") },
                suffix = { Text("mV") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Ported from `BatteryCurveChart.swift`, drawn on a plain Canvas — see this file's class doc. */
@Composable
fun BatteryCurveChart(ocvArray: List<Int>, modifier: Modifier = Modifier) {
    val minMv = ocvArray.min()
    val maxMv = ocvArray.max()
    val yAxisMin = floorToTenth((minMv - 100) / 1000.0)
    val yAxisMax = ceilToTenth((maxMv + 100) / 1000.0)
    val lineColor = MaterialTheme.colorScheme.primary
    val areaColor = lineColor.copy(alpha = 0.2f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("%.1fV".format(Locale.US, yAxisMax), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val points = ocvArray.mapIndexed { index, millivolts ->
                val percent = (10 - index) * 10
                val x = size.width * (percent / 100f)
                val voltage = millivolts / 1000.0
                val yFraction = ((voltage - yAxisMin) / (yAxisMax - yAxisMin)).toFloat().coerceIn(0f, 1f)
                Offset(x, size.height * (1f - yFraction))
            }

            val areaPath = Path().apply {
                moveTo(points.first().x, size.height)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, size.height)
                close()
            }
            drawPath(areaPath, color = areaColor)

            for (i in 0 until points.size - 1) {
                drawLine(lineColor, points[i], points[i + 1], strokeWidth = 4f)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0%", style = MaterialTheme.typography.labelSmall)
            Text("%.1fV".format(Locale.US, yAxisMin), style = MaterialTheme.typography.labelSmall)
            Text("100%", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun floorToTenth(value: Double): Double = floor(value * 10) / 10.0
private fun ceilToTenth(value: Double): Double = ceil(value * 10) / 10.0
