// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.services.rf.ClearanceStatus
import com.meshcoretwo.services.rf.GeoCoordinate
import com.meshcoretwo.services.rf.PathAnalysisResult
import com.meshcoretwo.services.rf.RelayPathAnalysisResult
import com.meshcoretwo.services.rf.SegmentAnalysisResult

/**
 * Sheet-content building blocks for the Line of Sight tool: everything from
 * `LineOfSightView.swift`'s analysis sheet except [TerrainProfileCanvas]/`TerrainProfileSectionView`
 * (a much larger custom-Canvas piece, left for its own future sub-slice) and the screen itself
 * (map integration, sheet chrome, navigation wiring — also deferred, see [LineOfSightViewModel]'s
 * class doc for the overall slice breakdown). Trimmed of liquid-glass styling, `sensoryFeedback`
 * haptics and SF Symbol icons the same way every other Tools screen in this port already is (see
 * [RxLogScreen]) — icon-only buttons use the shared `R.drawable.ic_*` Material Symbols set (PLAN.md's
 * Phase 6 slice 1) rather than SF Symbol equivalents.
 *
 * Every composable here takes [LineOfSightViewModel] directly rather than a bag of per-action
 * callbacks, the same pattern already used for passing `AppViewModel` into onboarding step screens
 * (`RegionStepView`/`PresetStepView`) — natural here too since nearly every row fires one of a
 * dozen distinct viewmodel methods.
 */
private val pointColorA = Color(0xFF1976D2)
private val pointColorB = Color(0xFF2E7D32)
private val repeaterColor = Color(0xFF6A1B9A)

// MARK: - Points Summary

/** Ported from `PointsSummarySectionView.swift`. */
@Composable
fun PointsSummarySectionView(
    state: LineOfSightUiState,
    viewModel: LineOfSightViewModel,
    editingPoint: PointID?,
    onEditingPointChange: (PointID?) -> Unit,
    onRelocate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.los_points), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.weight(1f))
            if (state.relocatingPoint != null) {
                TextButton(onClick = { viewModel.setRelocatingPoint(null) }) { Text(stringResource(R.string.common_cancel)) }
            }
        }

        val relocating = state.relocatingPoint
        if (relocating != null) {
            RelocatingMessage(relocating)
        } else {
            PointRowView(
                point = state.pointA,
                label = "A",
                color = pointColorA,
                pointID = PointID.POINT_A,
                relocatingPoint = state.relocatingPoint,
                viewModel = viewModel,
                editingPoint = editingPoint,
                onEditingPointChange = onEditingPointChange,
                onRelocate = onRelocate,
                onClear = viewModel::clearPointA,
            )

            if (state.repeaterPoint != null) {
                RepeaterRowView(
                    state = state,
                    viewModel = viewModel,
                    editingPoint = editingPoint,
                    onEditingPointChange = onEditingPointChange,
                    onRelocate = onRelocate,
                )
            } else if (state.shouldShowRepeaterPlaceholder) {
                AddRepeaterRowView(onAdd = { viewModel.addRepeater(); viewModel.analyzeWithRepeater() })
            }

            PointRowView(
                point = state.pointB,
                label = "B",
                color = pointColorB,
                pointID = PointID.POINT_B,
                relocatingPoint = state.relocatingPoint,
                viewModel = viewModel,
                editingPoint = editingPoint,
                onEditingPointChange = onEditingPointChange,
                onRelocate = onRelocate,
                onClear = viewModel::clearPointB,
            )

            if (state.pointA == null || state.pointB == null) {
                Text(
                    stringResource(R.string.los_long_press),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.elevationFetchFailed) {
                val warningColor = LocalMeshExtendedColors.current.warning
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = warningColor,
                    )
                    Text(
                        stringResource(R.string.los_no_elevation),
                        style = MaterialTheme.typography.bodySmall,
                        color = warningColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelocatingMessage(pointID: PointID) {
    val name = pointID.displayLabel()
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.los_relocating, name), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.los_tap_to_place),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PointID.displayLabel(): String = stringResource(
    when (this) {
        PointID.POINT_A -> R.string.los_point_a
        PointID.POINT_B -> R.string.los_point_b
        PointID.REPEATER -> R.string.los_the_repeater
    },
)

@Composable
private fun PointMarker(label: String, color: Color) {
    Box(
        modifier = Modifier.size(24.dp).background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** Ported from `PointRowView.swift`. */
@Composable
fun PointRowView(
    point: SelectedPoint?,
    label: String,
    color: Color,
    pointID: PointID,
    relocatingPoint: PointID?,
    viewModel: LineOfSightViewModel,
    editingPoint: PointID?,
    onEditingPointChange: (PointID?) -> Unit,
    onRelocate: () -> Unit,
    onClear: () -> Unit,
) {
    val isEditing = editingPoint == pointID

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PointMarker(label, if (point != null) color else Color.Gray.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.size(12.dp))

            if (point != null) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(point.contact?.displayName ?: stringResource(R.string.los_dropped_pin), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    if (point.isLoadingElevation) {
                        Text(stringResource(R.string.los_loading_elev), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (point.groundElevation != null) {
                        Text(
                            LOSFormatters.formatElevation(point.groundElevation + point.additionalHeight),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PointRowButtonsView(
                    coordinate = point.coordinate,
                    pointID = pointID,
                    isEditing = isEditing,
                    relocatingPoint = relocatingPoint,
                    onRelocateToggle = {
                        viewModel.setRelocatingPoint(if (relocatingPoint == pointID) null else pointID)
                        if (relocatingPoint != pointID) onRelocate()
                    },
                    onEditToggle = { onEditingPointChange(if (isEditing) null else pointID) },
                    onClear = onClear,
                )
            } else {
                Text(stringResource(R.string.los_not_selected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        if (isEditing && point != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            HeightEditorGrid(
                groundElevation = point.groundElevation,
                additionalHeight = point.additionalHeight,
                range = 0.0..200.0,
                onHeightChange = { viewModel.updateAdditionalHeight(pointID, it) },
            )
        }
    }
}

/** Ported from `PointRowButtonsView.swift`. Icon-only actions using plain glyphs, see file doc. */
@Composable
fun PointRowButtonsView(
    coordinate: GeoCoordinate,
    pointID: PointID,
    isEditing: Boolean,
    relocatingPoint: PointID?,
    onRelocateToggle: () -> Unit,
    onEditToggle: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showShareMenu by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { showShareMenu = true }) { Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.common_share)) }
            DropdownMenu(expanded = showShareMenu, onDismissRequest = { showShareMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.contacts_open_maps)) },
                    onClick = {
                        showShareMenu = false
                        val uri = Uri.parse("geo:${coordinate.latitude},${coordinate.longitude}?q=${coordinate.latitude},${coordinate.longitude}")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.los_copy_coords)) },
                    onClick = {
                        showShareMenu = false
                        clipboard.setText(AnnotatedString(LOSFormatters.formatCoordinate(coordinate)))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_share)) },
                    onClick = {
                        showShareMenu = false
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, LOSFormatters.formatCoordinate(coordinate))
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                    },
                )
            }
        }

        IconButton(onClick = onRelocateToggle, enabled = relocatingPoint == null || relocatingPoint == pointID) {
            Icon(painterResource(R.drawable.ic_my_location), contentDescription = stringResource(R.string.los_relocate))
        }

        IconButton(onClick = onEditToggle) {
            Icon(
                painterResource(if (isEditing) R.drawable.ic_check else R.drawable.ic_straighten),
                contentDescription = stringResource(if (isEditing) R.string.los_done_editing else R.string.los_measure),
            )
        }

        IconButton(onClick = onClear) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_clear)) }
    }
}

/** Ported from `RepeaterRowView.swift`. */
@Composable
fun RepeaterRowView(
    state: LineOfSightUiState,
    viewModel: LineOfSightViewModel,
    editingPoint: PointID?,
    onEditingPointChange: (PointID?) -> Unit,
    onRelocate: () -> Unit,
) {
    val repeater = state.repeaterPoint ?: return
    val isEditing = editingPoint == PointID.REPEATER

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PointMarker("R", repeaterColor)
            Spacer(modifier = Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.los_repeater), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                val elevation = state.repeaterGroundElevation
                if (elevation != null) {
                    Text(
                        LOSFormatters.formatElevation(elevation + repeater.additionalHeight),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PointRowButtonsView(
                coordinate = repeater.coordinate,
                pointID = PointID.REPEATER,
                isEditing = isEditing,
                relocatingPoint = state.relocatingPoint,
                onRelocateToggle = {
                    viewModel.setRelocatingPoint(if (state.relocatingPoint == PointID.REPEATER) null else PointID.REPEATER)
                    if (state.relocatingPoint != PointID.REPEATER) onRelocate()
                },
                onEditToggle = { onEditingPointChange(if (isEditing) null else PointID.REPEATER) },
                onClear = viewModel::clearRepeater,
            )
        }

        if (isEditing) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            HeightEditorGrid(
                groundElevation = state.repeaterGroundElevation,
                additionalHeight = repeater.additionalHeight,
                range = 0.0..200.0,
                onHeightChange = { viewModel.updateRepeaterHeight(it) },
                onHeightChanged = { viewModel.analyzeWithRepeater() },
            )
        }
    }
}

/** Ported from `AddRepeaterRowView.swift`. */
@Composable
fun AddRepeaterRowView(onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PointMarker("R", repeaterColor)
        Spacer(modifier = Modifier.size(12.dp))
        Text(stringResource(R.string.los_add_repeater), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = repeaterColor)
    }
}

// MARK: - Height Editor

/**
 * Ground/additional/total height grid for a point or repeater. Ported from `HeightEditorGrid.swift`,
 * minus its imperial-vs-metric step size (0.3048 m / 1 ft when the device locale is non-metric) —
 * this port has no locale measurement-system plumbing anywhere yet, so the step is a flat 1 meter
 * (see [LOSFormatters]'s class doc for the same simplification applied to display formatting).
 */
@Composable
fun HeightEditorGrid(
    groundElevation: Double?,
    additionalHeight: Double,
    range: ClosedFloatingPointRange<Double>,
    onHeightChange: (Double) -> Unit,
    onHeightChanged: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.los_ground_elev), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (groundElevation != null) LOSFormatters.formatElevation(groundElevation) else "…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.los_additional_h), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val next = (additionalHeight - 1.0).coerceIn(range)
                        onHeightChange(next)
                        onHeightChanged?.invoke()
                    },
                    enabled = additionalHeight > range.start,
                ) { Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.los_decrease)) }
                Text(
                    LOSFormatters.formatElevation(additionalHeight),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                TextButton(
                    onClick = {
                        val next = (additionalHeight + 1.0).coerceIn(range)
                        onHeightChange(next)
                        onHeightChanged?.invoke()
                    },
                    enabled = additionalHeight < range.endInclusive,
                ) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.los_increase)) }
            }
        }

        if (groundElevation != null) {
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.los_total_h), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text(
                    LOSFormatters.formatElevation(groundElevation + additionalHeight),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// MARK: - Clearance Status

/** Ported from `Components/ClearanceStatusView.swift`. */
@Composable
fun ClearanceStatusView(status: ClearanceStatus, clearancePercent: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(status.iconRes), contentDescription = stringResource(status.labelRes), tint = status.color)
        Spacer(modifier = Modifier.size(6.dp))
        Text(stringResource(status.labelRes), style = MaterialTheme.typography.titleSmall)
        if (status != ClearanceStatus.BLOCKED) {
            Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${LOSFormatters.formatClearancePercent(clearancePercent)}% clearance",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Analysis Error

/** Ported from `AnalysisErrorView.swift`. */
@Composable
fun AnalysisErrorView(message: String, hasRepeater: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(32.dp), tint = LocalMeshExtendedColors.current.warning)
        Text(stringResource(R.string.los_analysis_failed), style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
    }
}

// MARK: - RF Settings

/** Ported from `RFSettingsSectionView.swift` + `FrequencyInputRow.swift`. */
@Composable
fun RFSettingsSectionView(
    state: LineOfSightUiState,
    viewModel: LineOfSightViewModel,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!isExpanded) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.los_rf_settings), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                painterResource(if (isExpanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isExpanded) {
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var frequencyInput by remember(state.frequencyMHz) {
                    mutableStateOf(viewModel.formatFrequencyForEditing(state.frequencyMHz))
                }
                OutlinedTextField(
                    value = frequencyInput,
                    onValueChange = { frequencyInput = it },
                    label = { Text(stringResource(R.string.rf_frequency_mhz)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = {
                        val parsed = viewModel.parseFrequency(frequencyInput)
                        if (parsed != null && parsed != state.frequencyMHz) {
                            TextButton(onClick = { viewModel.setFrequencyMHz(parsed); viewModel.commitFrequencyChange() }) { Text(stringResource(R.string.common_save)) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.los_refraction), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RefractionChip(stringResource(R.string.los_ref_none), 1.0, state.refractionK, viewModel::setRefractionK)
                        RefractionChip(stringResource(R.string.los_ref_standard), 4.0 / 3.0, state.refractionK, viewModel::setRefractionK)
                        RefractionChip(stringResource(R.string.los_ref_ducting), 4.0, state.refractionK, viewModel::setRefractionK)
                    }
                }
            }
        }
    }
}

@Composable
private fun RefractionChip(label: String, value: Double, selected: Double, onSelect: (Double) -> Unit) {
    FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
}

// MARK: - Results Cards

/** Ported from `Components/ResultsCardView.swift`. */
@Composable
fun ResultsCardView(result: PathAnalysisResult, isExpanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.los_results), style = MaterialTheme.typography.titleMedium)

        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!isExpanded) },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ClearanceStatusView(result.clearanceStatus, result.worstClearancePercent)
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                painterResource(if (isExpanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
                    }

                    if (result.clearanceStatus == ClearanceStatus.BLOCKED) {
                        Text(stringResource(CLEARANCE_BLOCKED_SUBTITLE_RES), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    HorizontalDivider()

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            LOSFormatters.formatDistance(result.distanceMeters),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${LOSFormatters.formatPathLoss(result.totalPathLoss)} loss",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.los_path_loss), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            ResultDetailRow(stringResource(R.string.los_free_space), LOSFormatters.formatPathLoss(result.freeSpacePathLoss))
                            LOSFormatters.formatDiffractionLoss(result.peakDiffractionLoss)?.let {
                                ResultDetailRow(stringResource(R.string.los_diffraction), it)
                            }
                            HorizontalDivider()
                            ResultDetailRow(stringResource(R.string.los_total), LOSFormatters.formatPathLoss(result.totalPathLoss), bold = true)
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.los_clearance), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            ResultDetailRow(stringResource(R.string.los_worst_clearance), "${LOSFormatters.formatClearancePercent(result.worstClearancePercent)}%")
                            ResultDetailRow(stringResource(R.string.los_obstructions), "${result.obstructionPoints.size}")
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                painterResource(R.drawable.ic_info),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                LOSFormatters.formatAssumptions(result.frequencyMHz, result.refractionK),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Ported from `Components/ResultsCardView.swift`'s `RelayResultsCardView`. */
@Composable
fun RelayResultsCardView(result: RelayPathAnalysisResult, isExpanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.los_results), style = MaterialTheme.typography.titleMedium)

        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!isExpanded) },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ClearanceStatusView(
                            result.overallStatus,
                            minOf(result.segmentAR.worstClearancePercent, result.segmentRB.worstClearancePercent),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                painterResource(if (isExpanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
                    }

                    HorizontalDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SegmentSummaryRow(result.segmentAR)
                        SegmentSummaryRow(result.segmentRB)
                    }

                    HorizontalDivider()

                    Text(
                        stringResource(R.string.los_total_dist, LOSFormatters.formatDistance(result.totalDistanceMeters)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SegmentDetail(result.segmentAR)
                        SegmentDetail(result.segmentRB)
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentSummaryRow(segment: SegmentAnalysisResult) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(segment.clearanceStatus.color, CircleShape))
        Spacer(modifier = Modifier.size(6.dp))
        Text("${segment.startLabel} → ${segment.endLabel}", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.size(6.dp))
        Text(stringResource(segment.clearanceStatus.labelRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.weight(1f))
        Text(LOSFormatters.formatDistance(segment.distanceMeters), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SegmentDetail(segment: SegmentAnalysisResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${segment.startLabel} → ${segment.endLabel}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        ResultDetailRow(stringResource(R.string.saved_status), stringResource(segment.clearanceStatus.labelRes))
        ResultDetailRow(stringResource(R.string.los_distance), LOSFormatters.formatDistance(segment.distanceMeters))
        ResultDetailRow(stringResource(R.string.los_worst_clearance), "${LOSFormatters.formatClearancePercent(segment.worstClearancePercent)}%")
    }
}

@Composable
private fun ResultDetailRow(label: String, value: String, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
