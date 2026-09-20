// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.about.AppLinks
import com.meshcoretwo.services.rf.ElevationSample
import com.meshcoretwo.services.rf.ProfileSample
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private val chartHeight = 200.dp

/**
 * Canvas-based terrain profile visualization with Fresnel zone. Ported from
 * `TerrainProfileCanvas.swift` — the largest, most self-contained piece of the Line of Sight tool
 * (see [LineOfSightViewModel]'s class doc for the overall 9/10/11/12/13 slice breakdown; this is
 * slice 12, following 11's non-canvas sheet components). Not wired to any screen yet — same
 * "build ahead of its caller" pattern as [RFCalculator]/[LineOfSightViewModel] before it.
 *
 * Compose's `DrawScope` has no built-in text drawing (unlike SwiftUI's `GraphicsContext.draw`), so
 * axis/endpoint labels go through [rememberTextMeasurer] + [drawText] with a small anchor helper
 * ([drawAnchoredText]) standing in for Swift's `anchor: .trailing`/`.top`/`.center`. The repeater
 * marker's drop shadow is approximated with a single offset translucent circle rather than porting
 * `GraphicsContext`'s `.shadow` filter (no direct Compose `DrawScope` equivalent) — a cosmetic
 * simplification, not a functional one.
 */
@Composable
fun TerrainProfileCanvas(
    elevationProfile: List<ElevationSample>,
    profileSamples: List<ProfileSample>,
    modifier: Modifier = Modifier,
    profileSamplesRB: List<ProfileSample> = emptyList(),
    repeaterPathFraction: Double? = null,
    repeaterHeight: Double? = null,
    onRepeaterDrag: ((Double) -> Unit)? = null,
    onRepeaterMarkerPosition: ((Offset) -> Unit)? = null,
    segmentARDistanceMeters: Double? = null,
    segmentRBDistanceMeters: Double? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (elevationProfile.isEmpty()) {
            TerrainEmptyState()
        } else {
            TerrainChart(
                elevationProfile = elevationProfile,
                profileSamples = profileSamples,
                profileSamplesRB = profileSamplesRB,
                repeaterPathFraction = repeaterPathFraction,
                repeaterHeight = repeaterHeight,
                onRepeaterDrag = onRepeaterDrag,
                onRepeaterMarkerPosition = onRepeaterMarkerPosition,
                segmentARDistanceMeters = segmentARDistanceMeters,
                segmentRBDistanceMeters = segmentRBDistanceMeters,
            )
            TerrainLegend(showRepeater = repeaterPathFraction != null)
            if (segmentARDistanceMeters != null && segmentRBDistanceMeters != null) {
                Text(
                    stringResource(R.string.los_indirect),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                buildAnnotatedString {
                    append("Elevation data: ")
                    val linkStart = length
                    append("Copernicus DEM GLO-90")
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), linkStart, length)
                    addLink(LinkAnnotation.Url(AppLinks.COPERNICUS_DEM), linkStart, length)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun TerrainEmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().height(chartHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.los_no_data), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.los_select_two), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TerrainLegend(showRepeater: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem(terrainStrokeColor, stringResource(R.string.los_legend_terrain))
        LegendItem(MaterialTheme.colorScheme.onSurface, "LOS")
        LegendItem(fresnelInnerColor, stringResource(R.string.los_clear))
        LegendItem(fresnelObstructedColor, stringResource(R.string.los_legend_obstructed))
        if (showRepeater) LegendItem(repeaterMarkerColor, stringResource(R.string.los_repeater))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// MARK: - Colorblind-safe palette (ported from TerrainProfileCanvas.swift's color constants)

private val terrainFillColor = Color(0xFFC2B399)
private val terrainStrokeColor = Color(0xFF8C785C)
private val tealBase = Color(0xFF30B0C7)
private val fresnelOuterColor = tealBase.copy(alpha = 0.25f)
private val fresnelInnerColor = tealBase.copy(alpha = 0.50f)
private val fresnelBoundaryColor = tealBase.copy(alpha = 0.6f)
private val fresnelObstructedColor = Color.Red.copy(alpha = 0.7f)
private val gridColor = Color.Gray.copy(alpha = 0.3f)
private val repeaterMarkerColor = Color(0xFF6A1B9A)
private val pointAMarkerColor = Color(0xFF1976D2)
private val pointBMarkerColor = Color(0xFF2E7D32)

@Composable
private fun TerrainChart(
    elevationProfile: List<ElevationSample>,
    profileSamples: List<ProfileSample>,
    profileSamplesRB: List<ProfileSample>,
    repeaterPathFraction: Double?,
    repeaterHeight: Double?,
    onRepeaterDrag: ((Double) -> Unit)?,
    onRepeaterMarkerPosition: ((Offset) -> Unit)?,
    segmentARDistanceMeters: Double?,
    segmentRBDistanceMeters: Double?,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val losColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val chartPadding = remember(density) {
        with(density) { ChartPadding(top = 24.dp.toPx(), start = 45.dp.toPx(), end = 16.dp.toPx(), bottom = 28.dp.toPx()) }
    }

    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var hasAnimatedNudge by remember { mutableStateOf(false) }
    var previousHasRepeater by remember { mutableStateOf(repeaterPathFraction != null) }
    val nudgeOffset = remember { Animatable(0f) }

    val isOffPath = segmentARDistanceMeters != null && segmentRBDistanceMeters != null

    // One-time nudge animation hinting at draggability, only for on-path repeaters — mirrors the
    // Swift `.onChange(of: repeaterPathFraction, initial: true)` guard (oldValue nil, newValue non-nil).
    LaunchedEffect(repeaterPathFraction != null) {
        val hasRepeaterNow = repeaterPathFraction != null
        if (!previousHasRepeater && hasRepeaterNow && !hasAnimatedNudge && !isOffPath) {
            hasAnimatedNudge = true
            nudgeOffset.animateTo(4f, tween(150))
            nudgeOffset.animateTo(0f, tween(200))
        }
        previousHasRepeater = hasRepeaterNow
    }

    val xRange = remember(elevationProfile) {
        val last = elevationProfile.lastOrNull()
        0.0..(if (last != null) maxOf(1.0, last.distanceFromAMeters) else 1.0)
    }

    val yRange = remember(profileSamples, profileSamplesRB) {
        val all = profileSamples + profileSamplesRB
        if (all.isEmpty()) {
            0.0..100.0
        } else {
            val minY = all.minOf { it.yTerrain }
            val maxY = all.maxOf { it.yTop }
            if (!maxY.isFinite() || !minY.isFinite() || maxY <= minY) {
                0.0..100.0
            } else {
                val range = maxY - minY
                (minY - range * 0.1)..(maxY + range * 0.2)
            }
        }
    }

    val markerCenter: Offset? = if (canvasSize != Size.Zero && profileSamples.isNotEmpty() && repeaterPathFraction != null) {
        val coords = ChartCoordinateSpace(canvasSize, chartPadding, xRange, yRange)
        val junction = profileSamples.last()
        val base = coords.point(junction.x, junction.yLOS)
        Offset(base.x + nudgeOffset.value, base.y)
    } else {
        null
    }

    LaunchedEffect(markerCenter) {
        markerCenter?.let { onRepeaterMarkerPosition?.invoke(it) }
    }

    val updatedOnRepeaterDrag = rememberUpdatedState(onRepeaterDrag)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .then(
                if (onRepeaterDrag != null) {
                    Modifier.pointerInputDrag(repeaterPathFraction != null, chartPadding, updatedOnRepeaterDrag)
                } else {
                    Modifier
                },
            ),
    ) {
        val coords = ChartCoordinateSpace(size, chartPadding, xRange, yRange)

        drawTerrainGrid(coords, xRange, yRange, textMeasurer, gridColor, labelColor)

        drawFresnelFill(coords, profileSamples, fresnelOuterColor, { it.yTop }, { it.yVisibleBottom })
        if (profileSamplesRB.isNotEmpty()) drawFresnelFill(coords, profileSamplesRB, fresnelOuterColor, { it.yTop }, { it.yVisibleBottom })
        drawFresnelFill(coords, profileSamples, fresnelInnerColor, { it.yTop60 }, { it.yVisibleBottom60 })
        if (profileSamplesRB.isNotEmpty()) drawFresnelFill(coords, profileSamplesRB, fresnelInnerColor, { it.yTop60 }, { it.yVisibleBottom60 })

        drawFresnelBoundary(coords, profileSamples, fresnelBoundaryColor)
        if (profileSamplesRB.isNotEmpty()) drawFresnelBoundary(coords, profileSamplesRB, fresnelBoundaryColor)

        val terrainSamples = if (profileSamplesRB.isEmpty()) profileSamples else profileSamples + profileSamplesRB.drop(1)
        drawTerrain(coords, terrainSamples, xRange, yRange, terrainFillColor, terrainStrokeColor)

        drawObstructionOverlay(coords, profileSamples, yRange, fresnelObstructedColor)
        if (profileSamplesRB.isNotEmpty()) drawObstructionOverlay(coords, profileSamplesRB, yRange, fresnelObstructedColor)

        drawLosSegment(coords, profileSamples, losColor)
        if (profileSamplesRB.isNotEmpty()) drawLosSegment(coords, profileSamplesRB, losColor)

        drawEndpointMarkers(coords, profileSamples, profileSamplesRB, textMeasurer)

        if (segmentARDistanceMeters != null && isOffPath) {
            drawJunctionSeparator(coords, segmentARDistanceMeters, yRange, losColor.copy(alpha = 0.35f))
        }

        if (repeaterPathFraction != null && repeaterHeight != null && elevationProfile.size >= 2) {
            drawRepeaterMarker(coords, profileSamples, nudgeOffset.value, repeaterMarkerColor, textMeasurer)
        }
    }
}

/** Converts a horizontal drag on the chart into a path fraction (0.05-0.95), invoking [onDrag]. */
private fun Modifier.pointerInputDrag(
    key: Any?,
    padding: ChartPadding,
    onDrag: State<((Double) -> Unit)?>,
): Modifier = pointerInput(key) {
    detectDragGestures(onDrag = { change, _ ->
        change.consume()
        val chartWidth = size.width.toFloat() - padding.start - padding.end
        if (chartWidth <= 0f) return@detectDragGestures
        val relativeX = (change.position.x - padding.start) / chartWidth
        val fraction = relativeX.toDouble().coerceIn(0.05, 0.95)
        onDrag.value?.invoke(fraction)
    })
}

// MARK: - Axis tick helpers (pure math, ported from TerrainProfileCanvas.swift's Axis Helpers extension)

/** A "nice" step value for axis ticks (10, 25, 50, 100, ...). */
private fun niceStep(range: Double, targetDivisions: Int): Double {
    if (range <= 0 || targetDivisions <= 0) return 1.0
    val roughStep = range / targetDivisions
    val magnitude = 10.0.pow(floor(log10(roughStep)))
    val normalized = roughStep / magnitude
    val niceNormalized = when {
        normalized <= 1 -> 1.0
        normalized <= 2 -> 2.0
        normalized <= 2.5 -> 2.5
        normalized <= 5 -> 5.0
        else -> 10.0
    }
    return niceNormalized * magnitude
}

/** Tick values starting at a nice boundary within [range]. */
private fun tickValues(range: ClosedFloatingPointRange<Double>, step: Double): List<Double> {
    if (step <= 0) return emptyList()
    val start = ceil(range.start / step) * step
    val ticks = mutableListOf<Double>()
    var current = start
    while (current <= range.endInclusive) {
        ticks.add(current)
        current += step
    }
    return ticks
}

// MARK: - Text anchoring (stands in for GraphicsContext.draw's `anchor:` parameter)

private enum class HAnchor { START, CENTER, END }
private enum class VAnchor { TOP, CENTER, BOTTOM }

private fun DrawScope.drawAnchoredText(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    position: Offset,
    horizontal: HAnchor,
    vertical: VAnchor,
) {
    val layout = textMeasurer.measure(text, style)
    val x = when (horizontal) {
        HAnchor.START -> position.x
        HAnchor.CENTER -> position.x - layout.size.width / 2f
        HAnchor.END -> position.x - layout.size.width
    }
    val y = when (vertical) {
        VAnchor.TOP -> position.y
        VAnchor.CENTER -> position.y - layout.size.height / 2f
        VAnchor.BOTTOM -> position.y - layout.size.height
    }
    drawText(layout, topLeft = Offset(x, y))
}

// MARK: - Draw functions (ported from TerrainProfileCanvas.swift's Draw Functions extension)

private fun DrawScope.drawTerrainGrid(
    coords: ChartCoordinateSpace,
    xRange: ClosedFloatingPointRange<Double>,
    yRange: ClosedFloatingPointRange<Double>,
    textMeasurer: TextMeasurer,
    gridColor: Color,
    labelColor: Color,
) {
    val yStep = niceStep(yRange.endInclusive - yRange.start, 4)
    val xStep = niceStep(xRange.endInclusive - xRange.start, 5)
    val yTicks = tickValues(yRange, yStep)
    val xTicks = tickValues(xRange, xStep)
    val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)

    for (y in yTicks) {
        drawLine(
            color = gridColor,
            start = coords.point(xRange.start, y),
            end = coords.point(xRange.endInclusive, y),
            strokeWidth = 1f,
            pathEffect = dash,
        )
    }

    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)
    yTicks.forEachIndexed { index, y ->
        val labelPoint = coords.point(xRange.start, y)
        val text = if (index == yTicks.lastIndex) "${y.toInt()} m" else "${y.toInt()}"
        drawAnchoredText(textMeasurer, text, labelStyle, Offset(labelPoint.x - 8f, labelPoint.y), HAnchor.END, VAnchor.CENTER)
    }

    xTicks.forEachIndexed { index, x ->
        val labelPoint = coords.point(x, yRange.start)
        val kmValue = x / 1000
        val isLast = index == xTicks.lastIndex
        val text = if (xStep >= 1000 && kmValue % 1.0 == 0.0) {
            if (isLast) "${kmValue.toInt()} km" else "${kmValue.toInt()}"
        } else {
            val formatted = String.format(Locale.ROOT, "%.1f", kmValue)
            if (isLast) "$formatted km" else formatted
        }
        drawAnchoredText(textMeasurer, text, labelStyle, Offset(labelPoint.x, labelPoint.y + 10f), HAnchor.CENTER, VAnchor.TOP)
    }
}

private fun DrawScope.drawFresnelFill(
    coords: ChartCoordinateSpace,
    samples: List<ProfileSample>,
    color: Color,
    top: (ProfileSample) -> Double,
    bottom: (ProfileSample) -> Double,
) {
    if (samples.size < 2) return
    val path = Path()
    val firstTop = coords.point(samples.first().x, top(samples.first()))
    path.moveTo(firstTop.x, firstTop.y)
    for (sample in samples.drop(1)) {
        val p = coords.point(sample.x, top(sample))
        path.lineTo(p.x, p.y)
    }
    for (sample in samples.asReversed()) {
        val p = coords.point(sample.x, bottom(sample))
        path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, color = color)
}

private fun DrawScope.drawFresnelBoundary(coords: ChartCoordinateSpace, samples: List<ProfileSample>, color: Color) {
    if (samples.size < 2) return
    val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 3f), 0f)

    val topPath = Path()
    val firstTop = coords.point(samples.first().x, samples.first().yTop)
    topPath.moveTo(firstTop.x, firstTop.y)
    for (sample in samples.drop(1)) {
        val p = coords.point(sample.x, sample.yTop)
        topPath.lineTo(p.x, p.y)
    }

    val bottomPath = Path()
    val firstBottom = coords.point(samples.first().x, samples.first().yBottom)
    bottomPath.moveTo(firstBottom.x, firstBottom.y)
    for (sample in samples.drop(1)) {
        val p = coords.point(sample.x, sample.yBottom)
        bottomPath.lineTo(p.x, p.y)
    }

    drawPath(topPath, color = color, style = Stroke(width = 1f, pathEffect = dash))
    drawPath(bottomPath, color = color, style = Stroke(width = 1f, pathEffect = dash))
}

private fun DrawScope.drawObstructionOverlay(
    coords: ChartCoordinateSpace,
    samples: List<ProfileSample>,
    yRange: ClosedFloatingPointRange<Double>,
    color: Color,
) {
    var inRegion = false
    var regionStart = 0

    samples.forEachIndexed { index, sample ->
        if (sample.isObstructed && !inRegion) {
            inRegion = true
            regionStart = index
        } else if (!sample.isObstructed && inRegion) {
            drawObstructedRegion(coords, samples, regionStart, index - 1, yRange, color)
            inRegion = false
        }
    }
    if (inRegion) drawObstructedRegion(coords, samples, regionStart, samples.lastIndex, yRange, color)
}

private fun DrawScope.drawObstructedRegion(
    coords: ChartCoordinateSpace,
    samples: List<ProfileSample>,
    startIndex: Int,
    endIndex: Int,
    yRange: ClosedFloatingPointRange<Double>,
    color: Color,
) {
    if (startIndex > endIndex) return
    val first = samples[startIndex]
    val last = samples[endIndex]

    val minWidth = 4f
    var leftX = coords.xPixel(first.x)
    var rightX = coords.xPixel(last.x)
    if (rightX - leftX < minWidth) {
        val center = (leftX + rightX) / 2f
        leftX = center - minWidth / 2f
        rightX = center + minWidth / 2f
    }

    val topY = coords.yPixel(yRange.endInclusive)
    val bottomY = coords.yPixel(yRange.start)
    drawRect(color = color, topLeft = Offset(leftX, topY), size = Size(rightX - leftX, bottomY - topY))
}

private fun DrawScope.drawTerrain(
    coords: ChartCoordinateSpace,
    samples: List<ProfileSample>,
    xRange: ClosedFloatingPointRange<Double>,
    yRange: ClosedFloatingPointRange<Double>,
    fillColor: Color,
    strokeColor: Color,
) {
    if (samples.size < 2) return

    val fillPath = Path()
    val bottomLeft = coords.point(xRange.start, yRange.start)
    fillPath.moveTo(bottomLeft.x, bottomLeft.y)
    for (sample in samples) {
        val p = coords.point(sample.x, sample.yTerrain)
        fillPath.lineTo(p.x, p.y)
    }
    val bottomRight = coords.point(xRange.endInclusive, yRange.start)
    fillPath.lineTo(bottomRight.x, bottomRight.y)
    fillPath.close()
    drawPath(fillPath, color = fillColor)

    val strokePath = Path()
    val first = coords.point(samples.first().x, samples.first().yTerrain)
    strokePath.moveTo(first.x, first.y)
    for (sample in samples.drop(1)) {
        val p = coords.point(sample.x, sample.yTerrain)
        strokePath.lineTo(p.x, p.y)
    }
    drawPath(strokePath, color = strokeColor, style = Stroke(width = 1.5f))
}

private fun DrawScope.drawLosSegment(coords: ChartCoordinateSpace, samples: List<ProfileSample>, color: Color) {
    val first = samples.firstOrNull() ?: return
    val last = samples.lastOrNull() ?: return
    drawLine(color = color, start = coords.point(first.x, first.yLOS), end = coords.point(last.x, last.yLOS), strokeWidth = 2f)
}

private fun DrawScope.drawEndpointMarkers(
    coords: ChartCoordinateSpace,
    profileSamples: List<ProfileSample>,
    profileSamplesRB: List<ProfileSample>,
    textMeasurer: TextMeasurer,
) {
    val sampleA = profileSamples.firstOrNull() ?: return
    val sampleB = profileSamplesRB.lastOrNull() ?: profileSamples.lastOrNull() ?: return

    val radius = 6f
    val pointA = coords.point(sampleA.x, sampleA.yLOS)
    val pointB = coords.point(sampleB.x, sampleB.yLOS)

    drawCircle(color = pointAMarkerColor, radius = radius, center = pointA)
    drawCircle(color = pointBMarkerColor, radius = radius, center = pointB)

    val labelStyle = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White)
    drawAnchoredText(textMeasurer, "A", labelStyle, pointA, HAnchor.CENTER, VAnchor.CENTER)
    drawAnchoredText(textMeasurer, "B", labelStyle, pointB, HAnchor.CENTER, VAnchor.CENTER)
}

private fun DrawScope.drawRepeaterMarker(
    coords: ChartCoordinateSpace,
    profileSamples: List<ProfileSample>,
    nudgeOffsetPx: Float,
    color: Color,
    textMeasurer: TextMeasurer,
) {
    val junction = profileSamples.lastOrNull() ?: return

    val groundPoint = coords.point(junction.x, junction.yTerrain)
    val losPoint = coords.point(junction.x, junction.yLOS)
    val nudgedGround = Offset(groundPoint.x + nudgeOffsetPx, groundPoint.y)
    val nudgedLos = Offset(losPoint.x + nudgeOffsetPx, losPoint.y)

    drawLine(color = color, start = nudgedGround, end = nudgedLos, strokeWidth = 2f)

    val markerRadius = 16f
    // Approximates GraphicsContext's `.shadow` filter with a single offset translucent circle — see class doc.
    drawCircle(color = Color.Black.copy(alpha = 0.25f), radius = markerRadius, center = nudgedLos + Offset(0f, 2f))
    drawCircle(color = color, radius = markerRadius, center = nudgedLos)

    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
    drawAnchoredText(textMeasurer, "R", labelStyle, nudgedLos, HAnchor.CENTER, VAnchor.CENTER)
}

private fun DrawScope.drawJunctionSeparator(
    coords: ChartCoordinateSpace,
    distanceMeters: Double,
    yRange: ClosedFloatingPointRange<Double>,
    color: Color,
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
    drawLine(
        color = color,
        start = coords.point(distanceMeters, yRange.endInclusive),
        end = coords.point(distanceMeters, yRange.start),
        strokeWidth = 1.5f,
        pathEffect = dash,
    )
}

// MARK: - Terrain Profile Section (ported from TerrainProfileSectionView.swift)

/**
 * Header + [TerrainProfileCanvas] + one-time drag-hint tooltip, ported from
 * `TerrainProfileSectionView.swift`. [showDragHint]/[markerCenter] are owned by the caller (the
 * future Line of Sight screen), the same as Swift's `@Binding`s here — this view doesn't decide
 * *when* the hint should show (that's an `AppStorage`-backed one-time-ever flag plus a 5s timer at
 * the screen level, not yet built, see [LineOfSightViewModel]'s class doc), only how to render it
 * once told to. The tooltip's horizontal centering is approximated with a fixed offset rather than
 * measuring the label first — decorative, one-time UI, not worth the extra layout pass.
 */
@Composable
fun TerrainProfileSectionView(
    state: LineOfSightUiState,
    viewModel: LineOfSightViewModel,
    showDragHint: Boolean,
    markerCenter: Offset?,
    onMarkerCenterChange: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.los_terrain_profile), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.los_earth_curve, LOSFormatters.formatKFactor(state.refractionK)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box {
            TerrainProfileCanvas(
                elevationProfile = state.terrainElevationProfile,
                profileSamples = state.profileSamples,
                profileSamplesRB = state.profileSamplesRB,
                repeaterPathFraction = state.repeaterVisualizationPathFraction,
                repeaterHeight = state.repeaterPoint?.additionalHeight,
                onRepeaterDrag = if (state.repeaterPoint?.isOnPath == true) {
                    { fraction -> viewModel.updateRepeaterPosition(fraction); viewModel.analyzeWithRepeater() }
                } else {
                    null
                },
                onRepeaterMarkerPosition = onMarkerCenterChange,
                segmentARDistanceMeters = state.segmentARDistanceMeters,
                segmentRBDistanceMeters = state.segmentRBDistanceMeters,
            )

            if (showDragHint && markerCenter != null) {
                Text(
                    stringResource(R.string.los_drag),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                x = (markerCenter.x - with(density) { 40.dp.toPx() }).roundToInt(),
                                y = (markerCenter.y + 30f).roundToInt(),
                            )
                        }
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}
