// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import java.time.Instant

/**
 * [InitialsAvatar] with a small presence-dot overlay derived from [lastHeard] via [presenceLevel] —
 * "presence over timestamps" from the MeshCore Two Redesign mockup: a status dot tells you who's
 * around right now instead of making you read a relative timestamp on every row. Pass
 * `lastHeard = null` (e.g. for channels/rooms, which have no single "last heard" instant) to hide
 * the dot entirely — same as [PresenceLevel.NONE].
 */
@Composable
fun PresenceAvatar(
    name: String,
    lastHeard: Instant?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    category: AvatarCategory? = null,
    isPublicChannel: Boolean = false,
) {
    val level = presenceLevel(lastHeard)
    Box(modifier = modifier) {
        InitialsAvatar(name = name, size = size, category = category, isPublicChannel = isPublicChannel)
        if (level != PresenceLevel.NONE) {
            val extended = LocalMeshExtendedColors.current
            val dotColor = if (level == PresenceLevel.ACTIVE) extended.success else extended.caution
            Surface(
                shape = CircleShape,
                color = dotColor,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                modifier = Modifier.align(Alignment.BottomEnd).size(size * 0.27f),
            ) {}
        }
    }
}
