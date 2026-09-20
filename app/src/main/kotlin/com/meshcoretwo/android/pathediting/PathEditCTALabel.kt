// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/**
 * Center-aligned glyph+text label for the Add Hop / routing CTA buttons, shared by the (once
 * ported) contact path editor and the trace path builder so the two read identically. Ported from
 * `PathEditCTALabel`, with a plain text [glyph] standing in for the SF Symbol — this port has no
 * Material Icons Extended dependency (see `LineOfSightComponents.kt`'s class doc for why).
 */
@Composable
fun PathEditCTALabel(title: String, glyph: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PathEditMetrics.ctaIconSpacing, Alignment.CenterHorizontally),
    ) {
        Text(glyph, modifier = Modifier.size(PathEditMetrics.ctaIconSize), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
    }
}
