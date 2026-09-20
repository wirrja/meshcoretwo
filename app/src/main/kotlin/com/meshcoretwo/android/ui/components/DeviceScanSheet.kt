// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.startBLEScanning
import com.meshcoretwo.services.transport.DiscoveredDevice

/**
 * In-app BLE device picker bottom sheet, backed by [ConnectionManager.startBLEScanning]. Mirrors
 * `DeviceScannerSheet.swift` — the in-app scan picker iOS only shows on its macOS "no system
 * pairing registry" path, used here as Android's *primary* pairing UI since there's no
 * AccessorySetupKit-equivalent system picker (see `ConnectionManagerPairing.kt`'s class doc).
 *
 * Extracted from [com.meshcoretwo.android.onboarding.PairScreen] (where it was first written) on
 * its second call site, [com.meshcoretwo.android.settings.DeviceSelectionScreen]'s "Scan for
 * devices" — same "pull out on the second real consumer" precedent this port already applies
 * elsewhere (`EmptyState`, `SnrColors`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScanSheet(
    connectionManager: ConnectionManager,
    onDismiss: () -> Unit,
    onSelect: (DiscoveredDevice) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val discovered = remember { mutableStateMapOf<String, DiscoveredDevice>() }

    // Collecting this flow drives scanning; leaving composition cancels collection, which stops
    // scanning automatically (see `startBLEScanning`'s doc).
    LaunchedEffect(Unit) {
        connectionManager.startBLEScanning().collect { device -> discovered[device.id] = device }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.scan_nearby_devices), style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            if (discovered.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.scan_searching), modifier = Modifier.padding(start = 12.dp))
                }
            } else {
                val sorted = discovered.values.sortedWith(compareBy({ it.name == null }, { it.name ?: it.id }))
                LazyColumn {
                    itemsIndexed(sorted, key = { _, device -> device.id }) { index, device ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.name ?: device.id, style = MaterialTheme.typography.labelLarge)
                                Text("${device.rssi} dBm", style = MaterialTheme.typography.bodyLarge)
                            }
                            TextButton(onClick = { onSelect(device) }) { Text(stringResource(R.string.common_connect)) }
                        }
                        if (index < sorted.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}
