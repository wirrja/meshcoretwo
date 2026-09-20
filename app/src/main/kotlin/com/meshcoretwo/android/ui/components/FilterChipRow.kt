// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import com.meshcoretwo.android.ui.theme.LocalIsDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A scrollable row of [FilterChip]s over an enum-like list of segments — replaces `TabRow`/
 * `ScrollableTabRow` segmented tabs from the MeshCore Two Redesign mockup's "chips, not tabs"
 * principle: filters that sit beside search instead of hiding behind it, not fixed modes. Generic
 * over [T] so both Chats' All/Unread/Favorites and Contacts' Favorites/Contacts/Repeaters/Rooms
 * segments can reuse it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> FilterChipRow(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(item) },
                label = { Text(label(item)) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHigh else cardSurfaceColor(),
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = Color.Transparent,
                    selectedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}
