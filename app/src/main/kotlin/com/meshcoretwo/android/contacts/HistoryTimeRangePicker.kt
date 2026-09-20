// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Time range for filtering the history charts. Ported from `HistoryTimeRange`
 * (`HistoryTimeRangePicker.swift`).
 *
 * Swift's `Calendar.current.date(byAdding: .month, value: -1)` becomes a plain
 * [ChronoUnit.DAYS] subtraction here (30/90 days): `Instant` has no calendar-aware month
 * arithmetic, and a chart cutoff is not a date the user reads back, so calendar-exact months
 * would buy nothing.
 */
enum class HistoryTimeRange(val label: UiText, private val days: Long?) {
    WEEK(UiText.Plain("1W"), 7),
    MONTH(UiText.Plain("1M"), 30),
    THREE_MONTHS(UiText.Plain("3M"), 90),
    ALL(UiText.of(R.string.common_all), null),
    ;

    /** The cutoff below which snapshots are hidden, or null for [ALL]. */
    fun startInstant(now: Instant = Instant.now()): Instant? = days?.let { now.minus(it, ChronoUnit.DAYS) }

    /** Ported from `NodeStatusHistoryView.filteredSnapshots`. */
    fun filter(snapshots: List<NodeStatusSnapshotDto>, now: Instant = Instant.now()): List<NodeStatusSnapshotDto> {
        val start = startInstant(now) ?: return snapshots
        return snapshots.filter { !it.timestamp.isBefore(start) }
    }

    companion object {
        val DEFAULT = MONTH
    }
}

/**
 * Range selector for the history charts. A [FilterChip] row rather than Swift's segmented
 * `Picker` — Material3's `SegmentedButton` is still experimental in the Compose BOM this project
 * pins, and nothing else in the port uses it.
 */
@Composable
fun HistoryTimeRangePicker(selection: HistoryTimeRange, onSelect: (HistoryTimeRange) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryTimeRange.entries.forEach { range ->
            FilterChip(
                selected = range == selection,
                onClick = { onSelect(range) },
                label = { Text(range.label.asString()) },
            )
        }
    }
}
