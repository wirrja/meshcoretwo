// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.AppMark

/**
 * Ported from `WelcomeView.swift`. The hero animation is a simplified stand-in for
 * `MeshAnimationView` (full node-graph traveling-message simulation) — decorative only, no state
 * to port. The hero is the static app mark ([AppMark], the launcher icon); the animated hub-and-spoke
 * [com.meshcoretwo.android.ui.components.MeshGlyph] this screen used to show now lives on the shared
 * "connecting to device" state, where motion signals waiting.
 */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reveal by animateFloatAsState(if (shown) 1f else 0f, tween(600), label = "welcomeReveal")
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * 24.dp.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppMark(size = 128.dp)
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                stringResource(R.string.onboarding_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            // Unofficial port (README.md): say so up front rather than pass as the upstream iOS app.
            Text(
                stringResource(R.string.onboarding_welcome_disclaimer),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.onboarding_welcome_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(onClick = onGetStarted, modifier = Modifier.fillMaxWidth().height(56.dp), shape = CircleShape) {
                Text(stringResource(R.string.onboarding_get_started), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
