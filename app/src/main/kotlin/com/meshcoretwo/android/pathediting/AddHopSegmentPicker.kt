// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Filter row for [AddHopPickerScreen], pinned above the results list. Ported from
 * `AddHopSegmentPicker`, minus the Swift `isSearching`-mute behavior (reading
 * `@Environment(\.isSearching)` from a `.searchable` descendant has no Compose equivalent, and
 * nothing else in this port mutes a filter row while its search field is focused either).
 */
@Composable
fun AddHopSegmentPicker(selection: AddHopFilter, onSelectionChange: (AddHopFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AddHopFilter.entries.forEach { filter ->
            FilterChip(selected = filter == selection, onClick = { onSelectionChange(filter) }, label = { Text(stringResource(filter.labelRes)) })
        }
    }
}
