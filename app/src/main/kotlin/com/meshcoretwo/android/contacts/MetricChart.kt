// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.annotation.StringRes
import com.meshcoretwo.android.ui.i18n.DatePatterns
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/** One plotted sample. Ported from `MetricChartView.DataPoint` (`MetricChartView.swift`). */
data class MetricDataPoint(val id: UUID, val timestamp: Instant, val value: Double)

/** One plotted line. Ported from `MetricChartView.Series`. */
data class MetricSeries(val name: String, val color: Color, val dataPoints: List<MetricDataPoint>)

/**
 * A chart's full description, built away from Compose by [radioMetricCharts] so the whole
 * chart-selection logic is unit-testable. Swift builds `MetricChartView` values in the same
 * "describe now, render later" way (`RadioMetricCharts.swift` passes them to a host-supplied
 * container), so this is the same split, not an invention of this port.
 */
data class MetricChartSpec(
    val title: String,
    val unit: String,
    val series: List<MetricSeries>,
    val yAxisDomain: ClosedFloatingPointRange<Double>? = null,
    /** Localized title; [title] stays the English key (tests, series legend). Null keeps [title] as shown. */
    @StringRes val titleRes: Int? = null,
) {
    /** Series that carry points; empty ones would draw nothing and pollute the legend. */
    val drawnSeries: List<MetricSeries> get() = series.filter { it.dataPoints.isNotEmpty() }

    val isMultiSeries: Boolean get() = series.size > 1

    /** Two points are the minimum for a line; below that the chart shows a single number instead. */
    val hasEnoughData: Boolean get() = series.any { it.dataPoints.size >= 2 }

    /**
     * The number shown in place of a line, summed across series so an overlaid Direct/Flood chart
     * reports the total rather than just Direct. Null when nothing was captured at all.
     */
    val emptyStateValue: Double?
        get() = drawnSeries.mapNotNull { it.dataPoints.firstOrNull()?.value }.takeIf { it.isNotEmpty() }?.sum()
}

/**
 * A common Y-axis domain of `0 .. max * 1.05` across several point arrays, or null when there is no
 * positive maximum. Ported from `[MetricChartView.DataPoint].sharedDomain(for:)`; the packet charts
 * share one so a glance shows which counter climbs fastest.
 */
fun sharedMetricDomain(pointArrays: List<List<MetricDataPoint>>): ClosedFloatingPointRange<Double>? {
    val maxValue = pointArrays.flatten().maxOfOrNull { it.value } ?: return null
    if (maxValue <= 0) return null
    return 0.0..maxValue * 1.05
}

/**
 * A voltage domain (in volts) spanning the OCV curve plus a ±[bufferMillivolts] margin, unioned
 * with the plotted data so outliers are never clipped. Ported from `[Int].voltageChartDomain`.
 */
fun voltageChartDomain(
    ocvArray: List<Int>,
    dataPoints: List<MetricDataPoint>,
    bufferMillivolts: Int = 500,
): ClosedFloatingPointRange<Double>? {
    val ocvMin = ocvArray.minOrNull() ?: return null
    val ocvMax = ocvArray.maxOrNull() ?: return null
    var low = ocvMin / 1000.0
    var high = ocvMax / 1000.0
    dataPoints.minOfOrNull { it.value }?.let { low = minOf(low, it) }
    dataPoints.maxOfOrNull { it.value }?.let { high = maxOf(high, it) }
    val buffer = bufferMillivolts / 1000.0
    return maxOf(0.0, low - buffer)..high + buffer
}

/**
 * The point closest in time to [target], for the scrub readout. Ported from the `min(by:)`
 * comparisons `MetricChartView` uses for `scrubDate`/`selections`, which snap the readout and the
 * rule line onto real samples rather than arbitrary times between them.
 */
fun nearestMetricPoint(points: List<MetricDataPoint>, target: Instant): MetricDataPoint? =
    points.minByOrNull { abs(it.timestamp.toEpochMilli() - target.toEpochMilli()) }

private fun readoutFormat(): DateTimeFormatter = DatePatterns.monthDayTime()
private fun axisDateFormat(): DateTimeFormatter = DatePatterns.monthDay()

private const val CHART_HEIGHT_DP = 160
private const val EMPTY_STATE_HEIGHT_DP = 80
private const val Y_AXIS_LABEL_WIDTH_DP = 46

/**
 * A mini time-series chart for one or more metrics, drawn on a plain
 * [androidx.compose.foundation.Canvas]. Ported from `MetricChartView.swift` (Swift Charts).
 *
 * Long-pressing then dragging scrubs, exactly as in Swift: the rule line and the header readout
 * snap to the nearest real sample. Swift needs a UIKit `UILongPressGestureRecognizer` to keep the
 * enclosing `List` from stealing the gesture; on Compose
 * [detectDragGesturesAfterLongPress] already gives the long press priority over the parent
 * scroll, so the port needs no equivalent of `chartScrubbingScrollLock()`.
 *
 * Trimmed relative to Swift Charts, which supplies these for free and none of which change what
 * the chart says:
 * - Axes are the domain's end labels (min/max on the left, first/last date underneath) rather than
 *   Swift's automatic tick marks and grid lines.
 * - Lines are straight segments between samples (Swift asks for `.linear` interpolation too) with
 *   a dot per sample; there is no area fill and no haptic feedback on scrub start.
 */
@Composable
fun MetricChart(spec: MetricChartSpec, modifier: Modifier = Modifier) {
    val drawnSeries = spec.drawnSeries
    val allPoints = drawnSeries.flatMap { it.dataPoints }
    var scrubTarget by remember(spec) { mutableStateOf<Instant?>(null) }
    val scrubPoint = scrubTarget?.let { nearestMetricPoint(allPoints, it) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MetricChartHeader(spec = spec, drawnSeries = drawnSeries, scrubTimestamp = scrubPoint?.timestamp)

        if (!spec.hasEnoughData) {
            MetricChartEmptyState(value = spec.emptyStateValue, unit = spec.unit)
            return@Column
        }

        val startMillis = allPoints.minOf { it.timestamp.toEpochMilli() }
        val endMillis = allPoints.maxOf { it.timestamp.toEpochMilli() }
        val (yMin, yMax) = spec.yAxisDomain?.let { it.start to it.endInclusive } ?: run {
            val dataMin = allPoints.minOf { it.value }
            val dataMax = allPoints.maxOf { it.value }
            if (dataMin == dataMax) (dataMin - 1) to (dataMax + 1) else dataMin to dataMax
        }
        val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

        Row(modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT_DP.dp)) {
            Column(
                modifier = Modifier.fillMaxHeight().width(Y_AXIS_LABEL_WIDTH_DP.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatAxisValue(yMax), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatAxisValue(yMin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(spec) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset -> scrubTarget = timestampAt(offset.x, size.width.toFloat(), startMillis, endMillis) },
                            onDrag = { change, _ ->
                                change.consume()
                                scrubTarget = timestampAt(change.position.x, size.width.toFloat(), startMillis, endMillis)
                            },
                            onDragEnd = { scrubTarget = null },
                            onDragCancel = { scrubTarget = null },
                        )
                    },
            ) {
                fun xOf(point: MetricDataPoint): Float {
                    if (endMillis == startMillis) return size.width / 2f
                    val fraction = (point.timestamp.toEpochMilli() - startMillis).toDouble() / (endMillis - startMillis)
                    return (size.width * fraction).toFloat()
                }

                fun yOf(point: MetricDataPoint): Float {
                    val fraction = if (yMax == yMin) 0.5 else (point.value - yMin) / (yMax - yMin)
                    return (size.height * (1.0 - fraction.coerceIn(0.0, 1.0))).toFloat()
                }

                scrubPoint?.let { point ->
                    val x = xOf(point)
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)),
                    )
                }

                drawnSeries.forEach { series ->
                    val offsets = series.dataPoints.map { Offset(xOf(it), yOf(it)) }
                    for (index in 0 until offsets.size - 1) {
                        drawLine(series.color.copy(alpha = 0.6f), offsets[index], offsets[index + 1], strokeWidth = 3f)
                    }
                    offsets.forEach { drawCircle(series.color, radius = 3f, center = it) }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                axisDateFormat().format(Instant.ofEpochMilli(startMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                axisDateFormat().format(Instant.ofEpochMilli(endMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (spec.isMultiSeries) {
            MetricChartLegend(drawnSeries)
        }
    }
}

/** Maps a touch's x position onto the chart's time domain. */
private fun timestampAt(x: Float, width: Float, startMillis: Long, endMillis: Long): Instant {
    if (width <= 0f) return Instant.ofEpochMilli(startMillis)
    val fraction = (x / width).coerceIn(0f, 1f)
    return Instant.ofEpochMilli(startMillis + ((endMillis - startMillis) * fraction).toLong())
}

/** Title plus, while scrubbing, the nearest sample of each series and the shared timestamp. */
@Composable
private fun MetricChartHeader(spec: MetricChartSpec, drawnSeries: List<MetricSeries>, scrubTimestamp: Instant?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(spec.titleRes?.let { stringResource(it) } ?: spec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

        if (scrubTimestamp == null) return@Row
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            drawnSeries.forEach { series ->
                val point = nearestMetricPoint(series.dataPoints, scrubTimestamp) ?: return@forEach
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (spec.isMultiSeries) {
                        Text(series.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        formatReadoutValue(point.value, spec.unit),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = series.color,
                    )
                }
            }
            Text(
                readoutFormat().format(scrubTimestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricChartLegend(series: List<MetricSeries>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        series.forEach {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(it.color))
                Text(it.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Ported from `MetricChartEmptyState`: the lone captured value, if any, plus the "check back" hint. */
@Composable
private fun MetricChartEmptyState(value: Double?, unit: String) {
    Column(
        modifier = Modifier.fillMaxWidth().height(EMPTY_STATE_HEIGHT_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (value != null) {
            Text(formatReadoutValue(value, unit), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(R.string.metric_snapshot_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatReadoutValue(value: Double, unit: String): String {
    val formatted = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(Locale.US, value)
    return if (unit.isEmpty()) formatted else "$formatted $unit"
}

/** Axis end labels: whole numbers for wide domains (packet counts), one decimal for tight ones (volts). */
private fun formatAxisValue(value: Double): String =
    if (abs(value) >= 100) "%.0f".format(Locale.US, value) else "%.1f".format(Locale.US, value)
