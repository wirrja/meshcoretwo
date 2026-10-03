// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.ui.theme.BackdropPattern
import kotlin.random.Random

/**
 * Paints a theme's [BackdropPattern] behind the content, over the canvas fill. Grid, scanlines,
 * ruling and stars cover the whole area; the mesh, contour and halftone drawings sit at the top and
 * the horizon and hazard band at the bottom, so they decorate the edges and stay out of the way
 * behind the list. [accent] is the second color some patterns use (sun, margin, constellations).
 * Many-dot patterns are baked into one [Path] per size, so a redraw is a single draw call.
 */
internal fun Modifier.backdropPattern(pattern: BackdropPattern, color: Color, accent: Color?): Modifier = when (pattern) {
    BackdropPattern.NONE -> this
    BackdropPattern.GRID -> drawWithCache {
        val step = GRID_STEP.toPx()
        onDrawBehind { drawGrid(step, color) }
    }
    BackdropPattern.SCANLINES -> drawWithCache {
        val step = SCANLINE_STEP.toPx()
        val thickness = 1.dp.toPx()
        onDrawBehind {
            var y = 0f
            while (y < size.height) {
                drawRect(color, topLeft = Offset(0f, y), size = Size(size.width, thickness))
                y += step
            }
        }
    }
    BackdropPattern.MESH -> drawWithCache {
        val scale = size.width / MESH_VIEWBOX_WIDTH
        val stroke = 1.dp.toPx()
        onDrawBehind {
            for ((a, b) in MeshEdges) {
                drawLine(color, MeshNodes[a].scaled(scale), MeshNodes[b].scaled(scale), strokeWidth = stroke)
            }
            MeshNodes.forEachIndexed { index, node ->
                val radius = (if (index in MeshHubs) 3.2f else 2.5f) * scale
                drawCircle(color, radius = radius, center = node.scaled(scale))
            }
        }
    }
    BackdropPattern.CONTOUR -> drawWithCache {
        val scale = size.width / CONTOUR_VIEWBOX_WIDTH
        val paths = ContourRings.map { ring -> ring.toPath(scale) }
        val stroke = Stroke(width = 1.dp.toPx())
        onDrawBehind { paths.forEach { drawPath(it, color, style = stroke) } }
    }
    BackdropPattern.HORIZON -> drawWithCache {
        val horizon = size.height * HORIZON_LEVEL
        val stroke = 1.dp.toPx()
        val sunRadius = size.width * SUN_RADIUS_FRACTION
        val sunCenter = Offset(size.width / 2, horizon)
        val sunBand = sunRadius / SUN_BANDS
        onDrawBehind {
            if (accent != null) {
                // Synthwave sun: a disc sitting on the horizon, cut by gaps that widen toward its base.
                for (i in 0 until SUN_BANDS) {
                    val top = horizon - sunRadius + i * sunBand
                    val gap = sunBand * i / (SUN_BANDS + 3f)
                    clipRect(top = top, bottom = top + sunBand - gap) { drawCircle(accent, sunRadius, sunCenter) }
                }
            }
            // Floor grid: rows crowd toward the horizon, columns converge on its center.
            drawLine(color, Offset(0f, horizon), Offset(size.width, horizon), strokeWidth = stroke)
            for (i in 1..HORIZON_ROWS) {
                val t = i.toFloat() / HORIZON_ROWS
                val y = horizon + (size.height - horizon) * t * t
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }
            val spread = size.width / HORIZON_COLUMN_SPREAD
            for (k in -HORIZON_COLUMNS..HORIZON_COLUMNS) {
                drawLine(color, sunCenter, Offset(size.width / 2 + k * spread, size.height), strokeWidth = stroke)
            }
        }
    }
    BackdropPattern.HAZARD -> drawWithCache {
        val band = HAZARD_BAND.toPx()
        val stripe = HAZARD_STRIPE.toPx()
        val top = size.height - band
        val stripes = Path().apply {
            var x = -band
            while (x < size.width + band) {
                moveTo(x, size.height)
                lineTo(x + stripe, size.height)
                lineTo(x + stripe + band, top)
                lineTo(x + band, top)
                close()
                x += stripe * 2
            }
        }
        onDrawBehind { clipRect(top = top) { drawPath(stripes, color) } }
    }
    BackdropPattern.RULED -> drawWithCache {
        val step = RULED_STEP.toPx()
        val margin = RULED_MARGIN.toPx()
        val stroke = 1.dp.toPx()
        onDrawBehind {
            var y = step * 2
            while (y < size.height) {
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                y += step
            }
            if (accent != null) drawLine(accent, Offset(margin, 0f), Offset(margin, size.height), strokeWidth = stroke)
        }
    }
    BackdropPattern.HALFTONE -> drawWithCache {
        val step = HALFTONE_STEP.toPx()
        val maxRadius = HALFTONE_DOT.toPx()
        val fadeEnd = size.height * HALFTONE_FADE
        val dots = Path()
        var row = 0
        var y = step / 2
        while (y < fadeEnd) {
            val radius = maxRadius * (1f - y / fadeEnd)
            var x = if (row % 2 == 0) step / 2 else step
            while (x < size.width) {
                dots.addOval(Rect(center = Offset(x, y), radius = radius))
                x += step
            }
            y += step * ROW_HEIGHT_FACTOR
            row++
        }
        onDrawBehind { drawPath(dots, color) }
    }
    BackdropPattern.STARS -> drawWithCache {
        val unit = 1.dp.toPx()
        val bright = Path()
        val faint = Path()
        for (star in Stars) {
            val target = if (star.bright) bright else faint
            target.addOval(Rect(center = Offset(star.x * size.width, star.y * size.height), radius = star.radius * unit))
        }
        val scale = size.width / CONSTELLATION_VIEWBOX_WIDTH
        onDrawBehind {
            drawPath(faint, color.copy(alpha = color.alpha * FAINT_STAR_ALPHA))
            drawPath(bright, color)
            for (constellation in Constellations) {
                val points = constellation.map { it.scaled(scale) }
                if (accent != null) {
                    points.zipWithNext { a, b -> drawLine(accent, a, b, strokeWidth = unit) }
                }
                points.forEach { drawCircle(color, radius = CONSTELLATION_STAR * unit, center = it) }
            }
        }
    }
}

private fun DrawScope.drawGrid(step: Float, color: Color) {
    var x = 0f
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

private val GRID_STEP = 14.dp
private val SCANLINE_STEP = 3.dp

// Horizon: where the floor meets the sky, as a fraction of the height, and the grid's density.
private const val HORIZON_LEVEL = 0.62f
private const val HORIZON_ROWS = 10
private const val HORIZON_COLUMNS = 7
private const val HORIZON_COLUMN_SPREAD = 4f
private const val SUN_RADIUS_FRACTION = 0.26f
private const val SUN_BANDS = 9

private val HAZARD_BAND = 88.dp
private val HAZARD_STRIPE = 18.dp

private val RULED_STEP = 28.dp
private val RULED_MARGIN = 36.dp

private val HALFTONE_STEP = 9.dp
private val HALFTONE_DOT = 2.4.dp
private const val HALFTONE_FADE = 0.5f
private const val ROW_HEIGHT_FACTOR = 0.866f

// Starfield: a fixed scatter (same sky on every launch), positions as fractions of the area.
private class Star(val x: Float, val y: Float, val radius: Float, val bright: Boolean)

private val Stars: List<Star> = Random(STAR_SEED).let { random ->
    List(STAR_COUNT) { Star(random.nextFloat(), random.nextFloat(), 0.5f + random.nextFloat() * 1.1f, random.nextFloat() < 0.35f) }
}
private const val STAR_SEED = 53
private const val STAR_COUNT = 140
private const val FAINT_STAR_ALPHA = 0.45f
private const val CONSTELLATION_STAR = 1.8f

// Constellations, in the same 214-unit-wide view box as the mesh: made-up figures, not real ones.
private const val CONSTELLATION_VIEWBOX_WIDTH = 214f
private val Constellations = listOf(
    listOf(Offset(24f, 28f), Offset(46f, 22f), Offset(66f, 30f), Offset(82f, 44f), Offset(104f, 46f), Offset(112f, 64f), Offset(92f, 72f), Offset(82f, 44f)),
    listOf(Offset(150f, 20f), Offset(166f, 38f), Offset(182f, 30f), Offset(196f, 48f), Offset(176f, 62f)),
    listOf(Offset(130f, 110f), Offset(146f, 98f), Offset(160f, 112f), Offset(176f, 104f)),
)

// Mesh drawing, in a 214-unit-wide view box: a small network of nodes and links.
private const val MESH_VIEWBOX_WIDTH = 214f
private val MeshNodes = listOf(
    Offset(20f, 30f), Offset(70f, 18f), Offset(120f, 40f), Offset(175f, 22f), Offset(200f, 70f), Offset(150f, 85f),
    Offset(95f, 95f), Offset(40f, 80f), Offset(10f, 130f), Offset(182f, 132f), Offset(120f, 150f),
)
private val MeshHubs = setOf(1, 4, 6)
private val MeshEdges = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 2, 5 to 6, 6 to 2,
    6 to 7, 7 to 0, 7 to 1, 7 to 8, 5 to 9, 9 to 4, 6 to 10,
)

private fun Offset.scaled(scale: Float) = Offset(x * scale, y * scale)

// Contour drawing, same 214-unit view box: two hills of nested closed curves.
private const val CONTOUR_VIEWBOX_WIDTH = 214f

/** A closed curve: a start point and cubic segments of (control 1, control 2, end). */
private class Ring(val start: Offset, val segments: List<Triple<Offset, Offset, Offset>>) {
    fun toPath(scale: Float) = Path().apply {
        moveTo(start.x * scale, start.y * scale)
        for ((c1, c2, end) in segments) {
            cubicTo(c1.x * scale, c1.y * scale, c2.x * scale, c2.y * scale, end.x * scale, end.y * scale)
        }
        close()
    }
}

/** Builds a [Ring] from a flat list: start x, y, then six numbers per cubic segment. */
private fun ring(vararg v: Float): Ring {
    val segments = (2 until v.size step 6).map { i ->
        Triple(Offset(v[i], v[i + 1]), Offset(v[i + 2], v[i + 3]), Offset(v[i + 4], v[i + 5]))
    }
    return Ring(Offset(v[0], v[1]), segments)
}

private val ContourRings = listOf(
    ring(150f, 40f, 175f, 38f, 190f, 55f, 186f, 72f, 182f, 92f, 160f, 100f, 142f, 95f, 122f, 90f, 118f, 72f, 124f, 58f, 130f, 46f, 140f, 41f, 150f, 40f),
    ring(150f, 22f, 190f, 18f, 212f, 48f, 206f, 78f, 200f, 110f, 165f, 122f, 136f, 114f, 108f, 106f, 100f, 78f, 108f, 56f, 116f, 36f, 132f, 23f, 150f, 22f),
    ring(148f, 4f, 200f, 0f, 232f, 40f, 226f, 84f, 220f, 128f, 172f, 142f, 128f, 132f, 90f, 124f, 80f, 88f, 88f, 56f, 96f, 26f, 118f, 6f, 148f, 4f),
    ring(146f, -14f, 214f, -18f, 252f, 36f, 244f, 92f, 236f, 146f, 176f, 162f, 120f, 150f, 72f, 140f, 58f, 96f, 66f, 54f, 74f, 14f, 104f, -12f, 146f, -14f),
    ring(40f, 175f, 58f, 170f, 70f, 182f, 66f, 196f, 62f, 210f, 44f, 214f, 32f, 206f, 20f, 198f, 22f, 180f, 40f, 175f),
    ring(40f, 155f, 70f, 150f, 90f, 172f, 84f, 198f, 78f, 224f, 50f, 232f, 28f, 222f, 6f, 212f, 4f, 184f, 16f, 168f, 22f, 160f, 30f, 156f, 40f, 155f),
    ring(38f, 134f, 84f, 128f, 110f, 160f, 102f, 200f, 94f, 240f, 52f, 252f, 20f, 240f, -12f, 228f, -14f, 186f, 2f, 160f, 12f, 144f, 24f, 136f, 38f, 134f),
)
