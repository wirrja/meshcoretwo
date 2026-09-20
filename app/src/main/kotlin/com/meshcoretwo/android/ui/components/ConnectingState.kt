// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R

/**
 * Shared "waiting for the device" full-screen state — replaces four independent copies of a bare
 * `CircularProgressIndicator` + "Connecting to device…" (`ChatsListScreen`/`ContactsListScreen`/
 * `DiscoveryScreen`/`RegionManagementScreen`, all byte-identical) with the animated [MeshGlyph]
 * "searching the mesh" motif. Phase 18. (`WelcomeScreen` originally shared the glyph; it now shows
 * the static [AppMark] instead.)
 */
@Composable
fun ConnectingState(modifier: Modifier = Modifier) {
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
