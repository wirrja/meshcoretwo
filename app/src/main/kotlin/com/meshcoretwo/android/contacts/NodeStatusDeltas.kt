// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.ui.i18n.DatePatterns
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.ui.components.DetailRow
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.protocol.LPPDataPoint
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.remotenode.formattedValue
import com.meshcoretwo.services.remotenode.typeName
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Status deltas — each metric's change against the previous stored snapshot, plus the "since last
 * visit" caption naming how old that baseline is. Ported from `StatusDeltaView.swift` and the delta
 * properties of `NodeStatusViewModel.swift` (`batteryDeltaMV`/`snrDelta`/`rssiDelta`/
 * `noiseFloorDelta`/`previousSnapshotTimestamp`), shared by [RoomStatusScreen]/[RepeaterStatusScreen]
 * the same way [NodeStatusDisplay] shares the role-independent formatters. [ErrorText]/
 * [TelemetryRows] live here too for the same reason — both screens' sections rendered
 * byte-identical private copies before being deduped onto these.
 *
 * The baseline comes from the already-ported
 * [com.meshcoretwo.services.nodesnapshot.NodeSnapshotService.previousStatusSnapshot], captured by
 * each view model's `handleStatusResponse` *before* it records the fresh reading — same ordering as
 * Swift, so the delta compares against history rather than against the row just written.
 *
 * The history drill-downs Swift reaches from the same sections (`NodeStatusHistoryView`/
 * `TelemetryHistoryView`) came as later slices on a Canvas chart: [NodeStatusHistoryScreen],
 * [TelemetryHistoryScreen].
 */
object NodeStatusDeltas {
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86400L
    private const val SECONDS_PER_MINUTE = 60L

    /** Below this the delta is rendered neutral rather than as an improvement/degradation, as in Swift's `deltaColor`. */
    private const val NEGLIGIBLE_DELTA = 0.01

    /** Swift's `.dateTime.month().day()` — a locale-formatted month/day with no year, e.g. "Sep 3". */
    private fun baselineDateFormat(): DateTimeFormatter = DatePatterns.monthDay()

    fun batteryDeltaMillivolts(status: StatusResponse?, previous: NodeStatusSnapshotDto?): Int? {
        val current = status?.battery ?: return null
        val baseline = previous?.batteryMillivolts ?: return null
        return current - baseline.toInt()
    }

    fun snrDelta(status: StatusResponse?, previous: NodeStatusSnapshotDto?): Double? {
        val current = status?.lastSNR ?: return null
        val baseline = previous?.lastSNR ?: return null
        return current - baseline
    }

    fun rssiDelta(status: StatusResponse?, previous: NodeStatusSnapshotDto?): Int? {
        val current = status?.lastRSSI ?: return null
        val baseline = previous?.lastRSSI ?: return null
        return current - baseline.toInt()
    }

    fun noiseFloorDelta(status: StatusResponse?, previous: NodeStatusSnapshotDto?): Int? {
        val current = status?.noiseFloor ?: return null
        val baseline = previous?.noiseFloor ?: return null
        return current - baseline.toInt()
    }

    /**
     * How old the baseline reading is, as the caption under the status rows ("Since last visit
     * (12m ago)" / "(3h ago)" / "Since Sep 3"). Null when there is no baseline — the node's first
     * ever status reading has nothing to compare against. A baseline timestamped in the future
     * (clock skew between captures) clamps to zero rather than counting backwards.
     */
    fun previousSnapshotTimestamp(previous: NodeStatusSnapshotDto?, now: Instant = Instant.now()): UiText? {
        val timestamp = previous?.timestamp ?: return null
        val seconds = Duration.between(timestamp, now).seconds.coerceAtLeast(0)
        return when {
            seconds < SECONDS_PER_HOUR -> UiText.of(R.string.status_since_last_min, (seconds / SECONDS_PER_MINUTE).toInt())
            seconds < SECONDS_PER_DAY -> UiText.of(R.string.status_since_last_hour, (seconds / SECONDS_PER_HOUR).toInt())
            else -> UiText.of(R.string.status_since_date, baselineDateFormat().format(timestamp))
        }
    }

    /** The delta's magnitude with its unit, e.g. `0.005 V` — the arrow carries the sign. */
    fun deltaMagnitude(delta: Double, unit: String, fractionDigits: Int): String =
        "%.${fractionDigits}f%s".format(Locale.US, abs(delta), unit)

    /** Whether the change is in the metric's good direction, for the delta's color. */
    fun isImprovement(delta: Double, higherIsBetter: Boolean): Boolean = if (higherIsBetter) delta > 0 else delta < 0

    fun isNegligible(delta: Double): Boolean = abs(delta) < NEGLIGIBLE_DELTA
}

/**
 * A trend arrow plus the delta's magnitude, colored by whether the change is an improvement.
 * Ported from `StatusDeltaView.swift`; the SF Symbol arrows become the same "▲"/"▼" glyphs
 * [com.meshcoretwo.android.tools.ComparisonRow] already uses for a trend in this port. Uses
 * [com.meshcoretwo.android.ui.theme.MeshExtendedColors]'s success/warning tokens, matching the
 * palette of `LineOfSightFormatting`/`SavedPathRow`.
 */
@Composable
fun StatusDeltaLabel(delta: Double, higherIsBetter: Boolean, unit: String, fractionDigits: Int) {
    val extended = LocalMeshExtendedColors.current
    val color = when {
        NodeStatusDeltas.isNegligible(delta) -> MaterialTheme.colorScheme.onSurfaceVariant
        NodeStatusDeltas.isImprovement(delta, higherIsBetter) -> extended.success
        else -> extended.warning
    }
    // `delta > 0 ? up : down` (Swift's own `StatusDeltaView` logic, ported as-is) always chose "▼"
    // for delta == 0 — an unchanged reading then read as "decreased by 0", which is what a reading
    // that hasn't moved since the last snapshot looks like on every single refresh. Neutral here
    // instead, matching the row's own neutral color for the same negligible-delta case just above.
    val arrow = when {
        NodeStatusDeltas.isNegligible(delta) -> "–"
        delta > 0 -> "▲"
        else -> "▼"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(arrow, style = MaterialTheme.typography.bodySmall, color = color)
        Text(NodeStatusDeltas.deltaMagnitude(delta, unit, fractionDigits), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * A status row whose value carries an optional delta underneath it. Ported from `NodeMetricRow`
 * (`SharedNodeStatusViews.swift`); rows without a delta stay on the screens' own plain `DetailRow`.
 */
@Composable
fun NodeMetricRow(label: String, value: String, delta: Double?, higherIsBetter: Boolean, unit: String, fractionDigits: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(horizontalAlignment = Alignment.End) {
            Text(value)
            if (delta != null) {
                StatusDeltaLabel(delta = delta, higherIsBetter = higherIsBetter, unit = unit, fractionDigits = fractionDigits)
            }
        }
    }
}

/** A section's inline error message, no retry affordance — [ErrorRetryRow] covers the "Try Again" case. */
@Composable
fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error)
}

/** The Settings/CLI top-bar action pair, admin sessions only — byte-identical in both screens' `TopAppBar`. */
@Composable
fun NodeAdminTopBarActions(isAdmin: Boolean, onOpenSettings: () -> Unit, onOpenCLI: () -> Unit) {
    if (isAdmin) {
        IconButton(onClick = onOpenSettings) { Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.common_settings)) }
        IconButton(onClick = onOpenCLI) { Icon(painterResource(R.drawable.ic_code), contentDescription = "CLI") }
    }
}

/** LPP sensor readings, one [DetailRow] per point, battery points annotated with their OCV-curve percentage. */
@Composable
fun TelemetryRows(dataPoints: List<LPPDataPoint>, ocvArray: List<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        dataPoints.forEach { dp ->
            val ocvPercent = NodeStatusDisplay.ocvBatteryPercentage(dp, ocvArray)
            if (ocvPercent != null) {
                DetailRow(dp.typeName, "${dp.formattedValue} ($ocvPercent%)")
            } else {
                DetailRow(dp.typeName, dp.formattedValue)
            }
        }
    }
}
