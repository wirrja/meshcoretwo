// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Ported from iOS's `ContentUnavailableView(title, systemImage:, description:)` — a centered icon
 * above a title and description, used throughout the app for empty lists/search results/disabled
 * states. Compose has no built-in equivalent, so this is the shared stand-in every call site should
 * use instead of a title/description pair with no icon.
 *
 * The icon sits in a tonal `secondaryContainer` badge (Phase 17) rather than a flat
 * `onSurfaceVariant` glyph — the same "hero icon" language [PairScreen] uses, in a circle since this
 * sits mid-list/mid-screen rather than as a one-off hero. `secondaryContainer` specifically (not
 * `primaryContainer`, [com.meshcoretwo.android.ui.components.SettingsListRow]'s token) because
 * Phase 15 found `primaryContainer` mirrors `primary` in this app's `ThemePalettes.kt` on every
 * theme, while `secondaryContainer` is the one that's genuinely pale everywhere.
 */
@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
