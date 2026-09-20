// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.meshcoretwo.android.R

/**
 * Total path distance (repeater-to-repeater, or full path when device location is available) with
 * an info-button entry point into [DistanceInfoScreen]. Ported from `TotalDistanceRow.swift`, minus
 * the row's own `.sheet` — [TracePathResultsScreen] owns showing/hiding the info screen, same as
 * [TracePathListScreen] owns the Add-Hop picker's visibility.
 */
@Composable
fun TotalDistanceRow(distanceMeters: Double?, isDistanceUsingFallback: Boolean, onShowDistanceInfo: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.dist_total), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (distanceMeters != null) {
                Text(LOSFormatters.formatDistance(distanceMeters))
                if (isDistanceUsingFallback) {
                    IconButton(onClick = onShowDistanceInfo) { Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.dist_info_cd)) }
                }
            } else {
                Text(stringResource(R.string.dist_unavailable_short), color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onShowDistanceInfo) { Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.dist_info_cd)) }
            }
        }
    }
}
