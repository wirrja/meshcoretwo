// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A label/value row (label at `onSurfaceVariant`, value plain), duplicated identically across
 * `RepeaterStatusScreen`/`RoomStatusScreen`/`SharedNodeSettingsSections`/`ContactDetailScreen`
 * (as `InfoRow`), plus a denser variant (smaller monospace value) used two ways: single-line with
 * ellipsis in `RxLogScreen`'s packed packet-detail sheet, wrapping (no truncation) in
 * `SettingsScreen`'s device-info rows (as `InfoRow`) where a full public key needs to stay
 * readable. [dense]/[singleLine] reproduce those existing variants rather than inventing new ones.
 */
@Composable
fun DetailRow(label: String, value: String, dense: Boolean = false, singleLine: Boolean = dense) {
    if (dense) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
            )
        }
    } else {
        // bodyMedium (14sp), not the default 16sp: long translated labels ("Последний раз слышно")
        // beside values ("Никогда", "Вчера") looked oversized. The label may shrink and wrap; the
        // value keeps its natural width, right-aligned.
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp),
            )
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
        }
    }
}
