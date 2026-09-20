// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * App-wide corner scale — softer than Material3's baseline (4/8/12/16/28) so cards, dialogs, sheets,
 * menus and buttons all read as one gentle, rounded family. Wired into [MeshCoreTwoTheme]; anything
 * that takes `MaterialTheme.shapes.*` (cards, dialogs, bottom sheets, dropdowns, text fields)
 * follows automatically.
 */
val MeshCoreTwoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
