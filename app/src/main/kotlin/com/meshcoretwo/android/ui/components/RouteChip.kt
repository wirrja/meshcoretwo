// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Small tonal chip for a contact's route label ("Direct"/"N hops"/"Flood") — replaces the plain
 * inline text from the MeshCore Two Redesign mockup's Contacts row, same "chips, not tabs" tonal
 * language as [FilterChipRow] but non-interactive, so it uses a plain [Surface] rather than
 * `AssistChip`/`SuggestionChip` (both draw an outline this mockup doesn't call for). Passing [onClick]
 * makes it tappable (the ambiguous-region chip opens an explanation).
 */
@Composable
fun RouteChip(label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
    if (onClick == null) {
        Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer, content = content)
    } else {
        Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer, content = content)
    }
}
