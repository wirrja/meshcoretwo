// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * A screen's whole-body "still loading its own data" state — a bare centered spinner, distinct
 * from [ConnectingState]'s "waiting for the device" motif. Replaces four independent byte-identical
 * copies (`BlockedContactsScreen`/`ContactDetailScreen`/`ChannelInfoScreen`/
 * `BlockedChannelSendersScreen`). Phase 22.
 */
@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}
