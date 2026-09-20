// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/**
 * Tiny RTT-history line chart, ported from `MiniSparkline.swift`'s `GeometryReader`+`Path` drawing
 * to a Compose [Canvas]. A flat line for constant/single-value input mirrors Swift's fallback when
 * `max == min`.
 */
@Composable
fun MiniSparkline(values: List<Int>, modifier: Modifier = Modifier) {
    val strokeColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val minVal = values.minOrNull()
        val maxVal = values.maxOrNull()
        val strokeWidth = 1.5.dp.toPx()
        if (minVal != null && maxVal != null && maxVal > minVal) {
            val range = (maxVal - minVal).toFloat()
            val stepX = size.width / (values.size - 1).coerceAtLeast(1)
            var previous: Offset? = null
            values.forEachIndexed { index, value ->
                val x = index * stepX
                val y = size.height - ((value - minVal) / range * size.height)
                val point = Offset(x, y)
                previous?.let { drawLine(strokeColor, it, point, strokeWidth = strokeWidth) }
                previous = point
            }
        } else {
            val y = size.height / 2
            drawLine(strokeColor, Offset(0f, y), Offset(size.width, y), strokeWidth = strokeWidth)
        }
    }
}
