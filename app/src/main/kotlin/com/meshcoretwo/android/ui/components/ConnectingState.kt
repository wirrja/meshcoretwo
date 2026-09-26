// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.R
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState

/**
 * Shared "waiting for the device" full-screen state — replaces four independent copies of a bare
 * `CircularProgressIndicator` + "Connecting to device…" (`ChatsListScreen`/`ContactsListScreen`/
 * `DiscoveryScreen`/`RegionManagementScreen`, all byte-identical) with the animated [MeshGlyph]
 * "searching the mesh" motif. Phase 18. (`WelcomeScreen` originally shared the glyph; it now shows
 * the static [AppMark] instead.) After a user-chosen disconnect it shows "Radio not connected" with
 * a Connect button instead, since nothing is being connected.
 */
@Composable
fun ConnectingState(connectionManager: ConnectionManager, modifier: Modifier = Modifier) {
    val connectionState by connectionManager.connectionStateEvents.collectAsStateWithLifecycle()
    val intent by connectionManager.connectionIntentEvents.collectAsStateWithLifecycle()
    // "Connecting" only while a link is actually being set up or brought back after a drop.
    // Disconnected with no wish to connect (the user tapped Disconnect) is a resting state.
    val isConnecting = connectionState != DeviceConnectionState.DISCONNECTED || intent.wantsConnection
    if (!isConnecting) {
        val openDeviceSelection = LocalOpenDeviceSelection.current
        EmptyState(
            icon = R.drawable.ic_link_off,
            title = stringResource(R.string.radio_not_connected),
            description = stringResource(R.string.radio_not_connected_desc),
            modifier = modifier,
            action = { Button(onClick = openDeviceSelection) { Text(stringResource(R.string.common_connect)) } },
        )
        return
    }
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MeshGlyph(modifier = Modifier.size(96.dp))
        Spacer(modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.connecting_to_device), style = MaterialTheme.typography.bodyLarge)
    }
}
