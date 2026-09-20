// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.android.ui.theme.avatarGlyphColor
import com.meshcoretwo.android.ui.theme.categoryAvatarColor
import com.meshcoretwo.android.ui.theme.identityColor

/**
 * A circular avatar, shared by every list row that names a contact/channel/conversation but has no
 * photo to show (no avatar image support ported yet). Extracted once the chat list and contacts
 * list both needed the identical circle — see [InitialsAvatar]'s call sites for the list screens
 * that use it.
 *
 * A regular contact (`category == null`) shows its initial letter tinted by [identityColor] —
 * ported from `Theme.identityColor`. A channel/repeater/room (`category != null`) shows a fixed
 * per-category color and icon instead of a letter — ported from `NodeAvatar.swift`/
 * `ChannelAvatar.swift`, [category]'s [AvatarCategory.iconRes] doc comment has the icon mapping.
 */
@Composable
fun InitialsAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    category: AvatarCategory? = null,
    isPublicChannel: Boolean = false,
) {
    val fill = category?.let { categoryAvatarColor(it) } ?: identityColor(name)
    val glyphColor = avatarGlyphColor(fill, usesCategoryOverride = category != null)
    Surface(shape = CircleShape, color = fill, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            if (category != null) {
                Icon(
                    painter = painterResource(category.iconRes(isPublicChannel = isPublicChannel, channelName = name)),
                    contentDescription = null,
                    tint = glyphColor,
                    modifier = Modifier.size(size * 0.45f),
                )
            } else {
                Text(
                    text = name.trim().take(1).uppercase().ifEmpty { "?" },
                    color = glyphColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
