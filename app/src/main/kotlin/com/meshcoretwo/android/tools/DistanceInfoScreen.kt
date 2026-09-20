// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R

/**
 * Explains why the total-distance figure is partial or missing. Ported from
 * `DistanceInfoSheetView.swift` as a full-screen overlay rather than a sheet, matching this port's
 * established convention (see [com.meshcoretwo.android.pathediting.AddHopPickerScreen]) instead of
 * introducing a nested-modal library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistanceInfoScreen(state: TracePathUiState, onDismiss: () -> Unit) {
    val result = state.result
    val repeaterCount = result?.hops?.count { !it.isStartNode && !it.isEndNode } ?: 0
    val title = stringResource(if (state.isDistanceUsingFallback) R.string.dist_info else R.string.dist_unavailable)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(title) },
                actions = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            when {
                state.isDistanceUsingFallback -> {
                    item {
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            Text(stringResource(R.string.dist_partial), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.dist_partial_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    item {
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            Text(stringResource(R.string.dist_include_full), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.dist_include_full_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                repeaterCount < 2 -> {
                    item {
                        Text(
                            stringResource(R.string.dist_needs_two),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                state.repeatersWithoutLocation.isEmpty() -> {
                    item {
                        Text(
                            stringResource(R.string.dist_error),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                else -> {
                    item {
                        Text(
                            stringResource(R.string.dist_missing),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    item { Text(stringResource(R.string.dist_without_locations), style = MaterialTheme.typography.titleSmall) }
                    items(state.repeatersWithoutLocation) { name ->
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }
}
