// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.ExperimentalThemes
import com.meshcoretwo.android.ui.theme.LocalIsDarkTheme
import com.meshcoretwo.android.ui.theme.Theme

/** A few experimental accents dotted across the tile, hinting at what's behind it. */
private val PreviewThemes = listOf(ExperimentalThemes.Night, ExperimentalThemes.Taiga, ExperimentalThemes.Blueprint, ExperimentalThemes.Phosphor)

/**
 * Appearance's sixth tile — the way into the experimental themes, next to the five classic ones.
 * Same footprint as a [ThemeSelectionCard]: a square with an arrow and "Experimental themes" on it.
 * When an experimental theme is active ([selected]) the tile takes the selected fill and check badge
 * and names that theme underneath, so Appearance always shows which theme is on. Not a port —
 * MeshCore One has no experimental themes.
 */
@Composable
fun ExperimentalThemesCard(selected: Theme?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isSelected = selected != null
    val isDark = LocalIsDarkTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ThemeTileCornerRadius))
            .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(ThemeTilePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    PreviewThemes.forEach { theme ->
                        Box(Modifier.size(12.dp).clip(CircleShape).background(theme.accentColor.resolve(isDark)))
                    }
                }
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    // The back arrow mirrored — no separate forward-arrow asset needed.
                    Icon(
                        painterResource(R.drawable.ic_arrow_back),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp).graphicsLayer { scaleX = -1f },
                    )
                }
                WholeWordsText(
                    stringResource(R.string.appearance_experimental_title),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (isSelected) SelectedCheckBadge(Modifier.align(Alignment.TopEnd))
        }
        if (selected != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                selected.displayName,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * Centered text that never breaks inside a word: it shrinks (down to [minFontSize]) until its
 * longest word fits the width, then wraps only between words. "Экспериментальные" is wider than
 * the tile on a narrow phone, and plain [Text] split it mid-word ("Экспериментальн / ые темы").
 * A script without spaces (Chinese) is one "word" and falls back to ordinary wrapping at the floor.
 */
@Composable
private fun WholeWordsText(text: String, style: TextStyle, color: Color, minFontSize: TextUnit = 10.sp) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxWidth = constraints.maxWidth
        val fitted = remember(text, style, maxWidth) {
            val words = text.split(' ').filter { it.isNotEmpty() }
            fun fits(size: TextUnit) = words.all { word ->
                measurer.measure(word, style.copy(fontSize = size), softWrap = false, maxLines = 1).size.width <= maxWidth
            }
            var size = if (style.fontSize.isSpecified) style.fontSize else 14.sp
            while (size.value > minFontSize.value && !fits(size)) size = (size.value - FONT_STEP).sp
            style.copy(fontSize = size, lineHeight = (size.value * LINE_HEIGHT_FACTOR).sp)
        }
        Text(text, style = fitted, color = color, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

private const val FONT_STEP = 0.5f
private const val LINE_HEIGHT_FACTOR = 1.2f
