// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.Theme

internal val ThemeTileCornerRadius = 14.dp
internal val ThemeTilePadding = 8.dp
private val BadgeSize = 20.dp

/**
 * A theme on the Appearance screen: swatch + name, selection shown as a tonal fill + a check badge
 * on the swatch corner (Phase 12 redesign). The fill is `secondaryContainer` with its paired
 * `onSecondaryContainer` name: `primaryContainer` is the saturated accent itself in the generated
 * palettes, and the name used to keep the screen's content color on it — unreadable on dark Aurora.
 * Every built-in theme is selectable here — no owned/locked split, since this port has no
 * monetization (project constraints); iOS's `ThemeSelectionCard` renders only owned themes for the
 * same reason a sideload build unlocks every theme (`ThemeService.isAccessible`'s `#if SIDELOAD`
 * branch).
 *
 * Ported from `ThemeSelectionCard.swift`.
 */
@Composable
fun ThemeSelectionCard(theme: Theme, isSelected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(ThemeTileCornerRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .then(if (isSelected) Modifier else Modifier.clickable(onClick = onSelect))
            .padding(ThemeTilePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            ThemePaletteSwatch(theme = theme, modifier = Modifier.fillMaxWidth())
            if (isSelected) SelectedCheckBadge(Modifier.align(Alignment.TopEnd))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            theme.displayName,
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else Color.Unspecified,
        )
    }
}

/** The check badge on a selected theme tile's swatch corner. */
@Composable
internal fun SelectedCheckBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(BadgeSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = stringResource(R.string.common_selected),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(12.dp),
        )
    }
}
