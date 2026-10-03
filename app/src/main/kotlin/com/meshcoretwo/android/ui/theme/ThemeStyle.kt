// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Decorative layer painted on the app canvas behind every screen (see `accentBackdrop`). */
enum class BackdropPattern { NONE, GRID, SCANLINES, MESH, CONTOUR, HORIZON, HAZARD, RULED, HALFTONE, STARS }

/** Corner language of a theme: cards, pills, chat bubbles, text fields. */
enum class CornerStyle { ROUNDED, SHARP, SQUARE }

/**
 * Everything a theme changes beyond color — the "new look" of the experimental themes
 * ([ExperimentalThemes]). No iOS counterpart: MeshCore One themes are color-only. The five classic
 * themes use [Classic], which renders exactly as the app did before this type existed.
 *
 * Fonts are platform families only ([FontFamily.Monospace], [FontFamily.Serif]) — nothing is
 * bundled, so no font license enters the APK.
 */
data class ThemeStyle(
    val pattern: BackdropPattern = BackdropPattern.NONE,
    val patternColor: ThemeColor? = null,
    /** Second pattern color: Neon's sun, Notebook's margin line, Starmap's constellation lines. */
    val patternAccent: ThemeColor? = null,
    /** Paint the soft accent wash at the top of the canvas (`accentBackdrop`). */
    val accentWash: Boolean = true,
    /** Hairline around cards, pills and incoming bubbles; `null` = none. */
    val outline: ThemeColor? = null,
    val outlineWidth: Dp = 1.dp,
    /** Drop shadow under floating cards and the nav bar. */
    val shadow: Boolean = true,
    /** Colored glow in place of the drop shadow; a fully transparent side keeps the plain shadow. */
    val glow: ThemeColor? = null,
    val corners: CornerStyle = CornerStyle.ROUNDED,
    /** Font for display/headline/title styles; `null` = [bodyFont], then the app default. */
    val titleFont: FontFamily? = null,
    /** Font for body/label styles; `null` = the app default. */
    val bodyFont: FontFamily? = null,
    /** Soft glow behind all text (Phosphor's CRT bloom). */
    val textGlow: ThemeColor? = null,
    /** Glow behind display/headline/title text only (Neon's lit signs); wins over [textGlow] there. */
    val titleGlow: ThemeColor? = null,
    /** Extra-bold display/headline/title text (Hazard). */
    val boldTitles: Boolean = false,
    /** Body text one weight heavier (Daylight). */
    val mediumBody: Boolean = false,
    /** Flat outgoing bubble fill instead of the primary gradient (Night keeps bubbles dark). */
    val outgoingBubble: ThemeColor? = null,
    /** Text on [outgoingBubble]; `null` = `onPrimary`. */
    val outgoingBubbleText: ThemeColor? = null,
    /** Render maps luminance-inverted in red (Night) — see `rememberMapViewWithLifecycle`. */
    val nightMap: Boolean = false,
) {
    companion object {
        val Classic = ThemeStyle()
    }
}

private val SharpShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

private val SquareShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

/** The Material [Shapes] scale for this corner style. */
val CornerStyle.shapes: Shapes
    get() = when (this) {
        CornerStyle.ROUNDED -> MeshCoreTwoShapes
        CornerStyle.SHARP -> SharpShapes
        CornerStyle.SQUARE -> SquareShapes
    }

/** Pill-like controls: the nav bar and its tabs, search field, chips, the send button. */
val CornerStyle.pill: Shape
    get() = when (this) {
        CornerStyle.ROUNDED -> CircleShape
        CornerStyle.SHARP -> RoundedCornerShape(6.dp)
        CornerStyle.SQUARE -> RoundedCornerShape(2.dp)
    }

/** Corner radius of the chat input field. */
val CornerStyle.fieldRadius: Dp
    get() = when (this) {
        CornerStyle.ROUNDED -> 24.dp
        CornerStyle.SHARP -> 6.dp
        CornerStyle.SQUARE -> 2.dp
    }

/** Chat bubble: large corners with a small "tail" corner on the sender's side. */
fun CornerStyle.bubble(isOutgoing: Boolean): Shape {
    val (corner, tail) = when (this) {
        CornerStyle.ROUNDED -> 19.dp to 6.dp
        CornerStyle.SHARP -> 6.dp to 2.dp
        CornerStyle.SQUARE -> 2.dp to 2.dp
    }
    return if (isOutgoing) {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = corner, bottomEnd = tail)
    } else {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = tail, bottomEnd = corner)
    }
}

/** [base] with this style's fonts, text glow and body weight applied; [base] itself for [ThemeStyle.Classic]. */
fun ThemeStyle.typography(base: Typography, isDark: Boolean): Typography {
    if (titleFont == null && bodyFont == null && textGlow == null && titleGlow == null && !mediumBody && !boldTitles) return base
    val glow = textGlow?.resolve(isDark)?.let { Shadow(color = it, blurRadius = TEXT_GLOW_BLUR) }
    val headingGlow = titleGlow?.resolve(isDark)?.let { Shadow(color = it, blurRadius = TEXT_GLOW_BLUR) } ?: glow
    val titleFamily = titleFont ?: bodyFont
    fun TextStyle.title() = copy(
        fontFamily = titleFamily ?: fontFamily,
        shadow = headingGlow ?: shadow,
        fontWeight = if (boldTitles) FontWeight.ExtraBold else fontWeight,
    )
    fun TextStyle.body(heavier: Boolean) = copy(
        fontFamily = bodyFont ?: fontFamily,
        shadow = glow ?: shadow,
        fontWeight = if (heavier && mediumBody) FontWeight.Medium else fontWeight,
    )
    return base.copy(
        displayLarge = base.displayLarge.title(),
        displayMedium = base.displayMedium.title(),
        displaySmall = base.displaySmall.title(),
        headlineLarge = base.headlineLarge.title(),
        headlineMedium = base.headlineMedium.title(),
        headlineSmall = base.headlineSmall.title(),
        titleLarge = base.titleLarge.title(),
        titleMedium = base.titleMedium.title(),
        titleSmall = base.titleSmall.title(),
        bodyLarge = base.bodyLarge.body(heavier = true),
        bodyMedium = base.bodyMedium.body(heavier = true),
        bodySmall = base.bodySmall.body(heavier = true),
        labelLarge = base.labelLarge.body(heavier = false),
        labelMedium = base.labelMedium.body(heavier = false),
        labelSmall = base.labelSmall.body(heavier = false),
    )
}

private const val TEXT_GLOW_BLUR = 12f
