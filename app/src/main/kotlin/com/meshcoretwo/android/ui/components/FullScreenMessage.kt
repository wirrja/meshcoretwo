// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * A screen's whole-body plain-text state ("This X is no longer available.") — the [Text] sibling
 * of [LoadingScreen], for a load that finished but found nothing. Replaces four independent
 * byte-equivalent copies, two written as `Column(CenterHorizontally, Center)` (`ContactDetailScreen`/
 * `ChannelInfoScreen`), two as `Box(contentAlignment = Center)` (`ChatConversationScreen`/
 * `RoomConversationScreen`) — both center a single child identically, so the wrapper choice was
 * incidental, not a real variant. Phase 23.
 */
@Composable
fun FullScreenMessage(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text)
    }
}
