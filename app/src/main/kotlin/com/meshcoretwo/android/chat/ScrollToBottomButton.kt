// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import kotlinx.coroutines.launch

/**
 * Round "jump to the newest message" button, shown once the user has scrolled away from the bottom
 * of a `reverseLayout` message list (index 0 is the newest message). Ported from
 * `ScrollToBottomButton.swift`, without its unread badge. Meant to sit bottom-end over the list,
 * directly above [ChatInputBar]'s send button: the end padding centers this 44dp circle over the
 * bar's 48dp button (12dp bar padding + 2dp).
 */
@Composable
internal fun ScrollToBottomButton(listState: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val isAwayFromBottom by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    // Animated by hand rather than with AnimatedVisibility: its fade renders into an offscreen layer
    // that clips the button's shadow to a visible square mid-animation. ModulateAlpha applies the
    // alpha per draw call instead, so the round shadow stays intact.
    val progress by animateFloatAsState(if (isAwayFromBottom) 1f else 0f, tween(200), label = "scrollToBottom")
    if (progress == 0f) return

    SmallFloatingActionButton(
        onClick = { scope.launch { listState.animateScrollToItem(0) } },
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(end = 14.dp, bottom = 8.dp)
            .graphicsLayer {
                alpha = progress
                scaleX = 0.5f + 0.5f * progress
                scaleY = 0.5f + 0.5f * progress
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .size(44.dp),
    ) {
        Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = stringResource(R.string.chat_scroll_to_bottom))
    }
}
