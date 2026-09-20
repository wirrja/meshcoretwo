// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.pathediting.PathEditMetrics
import com.meshcoretwo.android.pathediting.PathHop

/**
 * One row of [TracePathListScreen]'s outbound-path list. Ported from `TracePathHopRow.swift`,
 * with move-up/move-down text buttons standing in for SwiftUI's drag-handle `.onMove` — this port
 * has no drag-reorder library or gesture wired up for `LazyColumn` yet, and a small path (the
 * common case) reorders just as easily one step at a time. Delete is a direct button rather than
 * `.onDelete`'s swipe-to-delete, for the same reason. [hopNumber] was accessibility-label-only in
 * Swift (this port has no accessibility-announcement layer — see `AddHopPickerScreen`'s doc); shown
 * visibly here instead since a numbered hop list is otherwise useful on its own.
 */
@Composable
fun TracePathHopRow(
    hop: PathHop,
    hopNumber: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = PathEditMetrics.tapTarget).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("$hopNumber.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
            Column {
                val name = hop.resolvedName
                if (name != null) {
                    Text(name)
                    Text(hop.hashHex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(hop.hashHex, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = stringResource(R.string.trace_move_up)) }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = stringResource(R.string.trace_move_down)) }
            IconButton(onClick = onDelete) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error) }
        }
    }
}
