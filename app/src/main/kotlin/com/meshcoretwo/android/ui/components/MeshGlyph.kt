// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * A small hub-and-spoke mesh graph with one accent node animating along an edge — a simplified
 * stand-in for `MeshAnimationView`'s full node-graph traveling-message simulation
 * (`WelcomeView.swift`). Promoted out of [com.meshcoretwo.android.onboarding.WelcomeScreen] (Phase
 * 15, its original call site) on its second real consumer (Phase 18's shared "connecting to
 * device" state) — same "promote on the second consumer" precedent as [GhostButton]/[QRSharePanel].
 */
@Composable
fun MeshGlyph(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "mesh-pulse")
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "travel",
    )
    val accent = MaterialTheme.colorScheme.primary
    val nodeColor = MaterialTheme.colorScheme.onSurface
    val edgeColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val hub = Offset(size.width * 0.5f, size.height * 0.5f)
        val outerNodes = listOf(
            Offset(size.width * 0.20f, size.height * 0.28f),
            Offset(size.width * 0.80f, size.height * 0.25f),
            Offset(size.width * 0.25f, size.height * 0.77f),
            Offset(size.width * 0.77f, size.height * 0.73f),
            Offset(size.width * 0.50f, size.height * 0.13f),
        )
        val edgeWidth = size.width * 0.017f
        outerNodes.forEach { node -> drawLine(color = edgeColor, start = hub, end = node, strokeWidth = edgeWidth) }
        drawCircle(color = accent, radius = size.width * 0.075f, center = hub)
        outerNodes.forEach { node -> drawCircle(color = nodeColor, radius = size.width * 0.042f, center = node) }

        val travelTarget = outerNodes.first()
        val travelDot = Offset(
            x = hub.x + (travelTarget.x - hub.x) * travel,
            y = hub.y + (travelTarget.y - hub.y) * travel,
        )
        drawCircle(color = accent, radius = size.width * 0.03f, center = travelDot)
    }
}
