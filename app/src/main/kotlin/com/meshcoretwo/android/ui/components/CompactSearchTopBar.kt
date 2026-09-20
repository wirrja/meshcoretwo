// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.services.connection.ConnectionManager

/**
 * Fixed-height top header for Chats/Contacts/Tools/Settings — the MeshCore Two Redesign mockup's
 * "one bar, not two states" principle: the list starts higher and nothing jumps as you scroll.
 * Visually matches the floating bottom bar: a bold left-aligned title, the [RadioStatusIcon] in a
 * round tonal chip, and [actions] as round tonal buttons ([HeaderIconButton]) instead of a flat
 * Material `TopAppBar` row. Two optional slots: an inline [SearchPillField]
 * ([searchQuery]/[onSearchQueryChange], both null hides it) and a [filters] row ([FilterChipRow]).
 */
@Composable
fun CompactSearchTopBar(
    title: String,
    connectionManager: ConnectionManager,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = stringResource(R.string.common_search),
    filters: (@Composable () -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // One line, shrinking to fit: with three action buttons a long translation
                // ("Yhteystiedot") would otherwise break mid-word.
                var titleScale by remember(title) { mutableFloatStateOf(1f) }
                val baseSize = MaterialTheme.typography.headlineSmall.fontSize
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontSize = baseSize * titleScale,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    onTextLayout = { if (it.hasVisualOverflow && titleScale > 0.6f) titleScale *= 0.92f },
                    modifier = Modifier.weight(1f),
                )
                actions()
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { RadioStatusIcon(connectionManager) }
                }
            }
            if (searchQuery != null && onSearchQueryChange != null) {
                SearchPillField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = searchPlaceholder,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (filters != null) {
                Column(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) { filters() }
            }
        }
    }
}

/** Round tonal 40dp icon button for [CompactSearchTopBar]'s `actions`. */
@Composable
fun HeaderIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { content() }
}
