// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Capsule badge for labeling node types (room, etc.). Ported from `NodeKindBadge`. */
@Composable
fun NodeKindBadge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), shape = CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
