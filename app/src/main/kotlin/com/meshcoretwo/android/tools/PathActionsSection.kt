// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R

/**
 * Path configuration toggles, hash-size override, and the copy/clear actions. Ported from
 * `PathActionsSectionView.swift`, only shown by [TracePathListScreen] while the path is non-empty
 * (same as Swift's `if !viewModel.outboundPath.isEmpty` guard around the whole section body). The
 * hash-size `Picker` becomes a [FilterChip] row (same convention as `AddHopSegmentPicker`) instead
 * of a dropdown menu — this port doesn't use `DropdownMenu` for single-choice pickers elsewhere.
 */
@Composable
fun PathActionsSection(
    state: TracePathUiState,
    showHashSizeOverride: Boolean,
    onAutoReturnChange: (Boolean) -> Unit,
    onBatchEnabledChange: (Boolean) -> Unit,
    onBatchSizeChange: (Int) -> Unit,
    onHashModeChange: (UByte) -> Unit,
    onCopyPath: () -> Unit,
    onClearPath: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToggleRow(
            title = stringResource(R.string.trace_auto_return),
            description = stringResource(R.string.trace_auto_return_desc),
            checked = state.autoReturnPath,
            onCheckedChange = onAutoReturnChange,
        )

        ToggleRow(
            title = stringResource(R.string.trace_batch),
            description = stringResource(R.string.trace_batch_desc),
            checked = state.batchEnabled,
            onCheckedChange = onBatchEnabledChange,
        )

        if (state.batchEnabled) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trace_traces_label), color = MaterialTheme.colorScheme.onSurfaceVariant)
                BatchSizeChip(size = 3, selectedSize = state.batchSize, onSelect = onBatchSizeChange)
                BatchSizeChip(size = 5, selectedSize = state.batchSize, onSelect = onBatchSizeChange)
            }
        }

        if (showHashSizeOverride) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.trace_hash_size), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HashSizeChip(label = "1 Byte", mode = 0u, selected = state.effectiveTraceMode, onSelect = onHashModeChange)
                    HashSizeChip(label = "2 Bytes", mode = 1u, selected = state.effectiveTraceMode, onSelect = onHashModeChange)
                    HashSizeChip(label = "4 Bytes", mode = 2u, selected = state.effectiveTraceMode, onSelect = onHashModeChange)
                }
                if (state.effectiveTraceMode > 0u) {
                    Text(
                        stringResource(R.string.trace_hash_warn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider()

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.fullPathString,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopyPath) { Text(stringResource(R.string.common_copy)) }
        }

        Text(
            stringResource(R.string.trace_range_warn),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TextButton(onClick = onClearPath) { Text(stringResource(R.string.common_clear_path), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HashSizeChip(label: String, mode: UByte, selected: UByte, onSelect: (UByte) -> Unit) {
    FilterChip(selected = selected == mode, onClick = { onSelect(mode) }, label = { Text(label) })
}
