// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * A cosmetic theme: accent + chat colors + optional surfaces + optional forced color scheme, plus
 * (unlike iOS) a full baked [ColorScheme] per appearance — see `ThemePalettes.kt`'s doc comment for
 * why Compose needs one and iOS doesn't. Built-in themes are [companion object] constants, not
 * subtypes, same as Swift's static factory values.
 *
 * Ported from `Theme.swift`. `productID`/`chromeTint` have no counterpart — there is no monetization
 * in this port (project constraints) so every theme is unconditionally accessible ([ThemeService] never
 * throws), and Compose's [MaterialTheme] already tints chrome from `colorScheme.primary`
 * everywhere, which [lightScheme]/[darkScheme] derive from [accentColor] — no separate tint knob
 * needed.
 */
data class Theme(
    val id: String,
    val displayName: String,
    val accentColor: ThemeColor,
    val outgoingTextColor: ThemeColor,
    val hashtagColor: ThemeColor,
    /** Forces one appearance regardless of [AppColorSchemePreference]; no built-in theme sets it. */
    val forcedDark: Boolean?,
    val surfaces: Surfaces?,
    /** The theme's identity-color space for contact avatars, channel sender names, and mentions. */
    val identityGamut: IdentityGamut,
    /** Set only by [Default] to pin channel / repeater / room avatars to fixed legacy colors. */
    val categoryAvatarOverride: CategoryAvatarColors?,
    /** Curated category avatar hues for a gamut theme; `null` falls back to a distinct auto-pick. */
    val categoryHues: CategoryHues?,
    val lightScheme: ColorScheme,
    val darkScheme: ColorScheme,
) {
    fun colorScheme(isDark: Boolean): ColorScheme = if (isDark) darkScheme else lightScheme

    companion object {
        val Default = Theme(
            id = "default",
            displayName = "Default",
            accentColor = ThemeColor(Color(0xFF2463EB), Color(0xFF2463EB)),
            outgoingTextColor = ThemeColor(Color.White, Color.White),
            hashtagColor = ThemeColor(Color(0xFF007A8F), Color(0xFF5AD2EA)),
            forcedDark = null,
            surfaces = null,
            identityGamut = IdentityGamut(DefaultIdentityHueAnchors, DefaultIdentitySaturation),
            categoryAvatarOverride = CategoryAvatarColors(CategoryChannel, CategoryRepeaterNode, CategoryRoom),
            categoryHues = null,
            lightScheme = DefaultLightColors,
            darkScheme = DefaultDarkColors,
        )

        /** Cool polar-lights teal on a mint-white / deep sea-green canvas. */
        val Aurora = Theme(
            id = "aurora",
            displayName = "Aurora",
            accentColor = ThemeColor(Color(0xFF0B7A72), Color(0xFF4FE0D0)),
            outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
            hashtagColor = ThemeColor(Color(0xFF2B5FD0), Color(0xFF8FB4FF)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFF0F8F7), Color(0xFF071416)),
                card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF12262A)),
            ),
            identityGamut = IdentityGamut(listOf(160.0, 175.0, 190.0, 205.0, 225.0, 250.0, 275.0), 0.5..0.85),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 190.0, repeater = 160.0, room = 275.0),
            lightScheme = AuroraLightColors,
            darkScheme = AuroraDarkColors,
        )

        /** Warm coral and peach on a cream / dark-plum canvas. */
        val Sunrise = Theme(
            id = "sunrise",
            displayName = "Sunrise",
            accentColor = ThemeColor(Color(0xFFC93C1A), Color(0xFFFF8A66)),
            outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
            hashtagColor = ThemeColor(Color(0xFF9A5A00), Color(0xFFFFC46B)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFFFF6EF), Color(0xFF1A0F12)),
                card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF2A1A1E)),
            ),
            identityGamut = IdentityGamut(listOf(8.0, 18.0, 28.0, 38.0, 48.0, 340.0, 352.0), 0.7..0.95),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 18.0, repeater = 38.0, room = 340.0),
            lightScheme = SunriseLightColors,
            darkScheme = SunriseDarkColors,
        )

        /** Near-monochrome minimalism: ink accent on light grey, white accent on true black (OLED). */
        val Graphite = Theme(
            id = "graphite",
            displayName = "Graphite",
            accentColor = ThemeColor(Color(0xFF1C1C22), Color(0xFFF2F2F5)),
            outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
            hashtagColor = ThemeColor(Color(0xFF2F5DA8), Color(0xFF9DB8F0)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFF4F4F5), Color(0xFF000000)),
                card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF161618)),
            ),
            identityGamut = IdentityGamut(listOf(210.0, 230.0, 250.0, 20.0, 40.0, 160.0), 0.2..0.45),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 210.0, repeater = 160.0, room = 20.0),
            lightScheme = GraphiteLightColors,
            darkScheme = GraphiteDarkColors,
        )

        /** Vivid indigo-to-magenta on a lavender / night-indigo canvas. */
        val Ultraviolet = Theme(
            id = "ultraviolet",
            displayName = "Ultraviolet",
            accentColor = ThemeColor(Color(0xFF6A4CFF), Color(0xFFA99BFF)),
            outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
            hashtagColor = ThemeColor(Color(0xFFB0157A), Color(0xFFFF9AD5)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFF5F2FF), Color(0xFF0C0A1C)),
                card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF1B1738)),
            ),
            identityGamut = IdentityGamut(listOf(225.0, 255.0, 270.0, 285.0, 300.0, 315.0, 330.0, 345.0), 0.45..0.8),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 270.0, repeater = 225.0, room = 315.0),
            lightScheme = UltravioletLightColors,
            darkScheme = UltravioletDarkColors,
        )
    }
}

/** A color that differs between light and dark appearance. Ported ad hoc — Swift's asset-catalog
 * `Color("...")` resolves this automatically from the current trait collection; Compose has no
 * asset-driven equivalent, so themes carry both values explicitly and callers resolve one. */
data class ThemeColor(val light: Color, val dark: Color) {
    fun resolve(isDark: Boolean): Color = if (isDark) dark else light
}

/**
 * Canvas (system grouped replacement) + card (secondary system grouped replacement) for a themed
 * surface. `card == null` means "paint the canvas but leave card rows on the system tier" (Ember).
 *
 * Ported from `Theme.Surfaces`. Only [canvas] is wired into [ColorScheme] directly
 * (`background`/`surface`, see `ThemePalettes.kt`'s doc comment); [card] is read directly by
 * [incomingBubbleColor] and by [com.meshcoretwo.android.ui.components.SectionCard] (Phase 6 slice
 * 5's shared "grouped list" container), both falling back to `surfaceVariant`/`surfaceContainer`
 * for themes with no explicit card tier (Default, Ember).
 */
data class Surfaces(val canvas: ThemeColor, val card: ThemeColor?)

/** The active cosmetic theme, resolved for the current appearance. Set once at the Compose root
 * by [MeshCoreTwoTheme] from [ThemeService.current]; read by identity/category avatar colors and
 * (from Phase 6 slice 5/6 onward) themed surfaces. Defaults to [Theme.Default] so previews and any
 * subtree outside the root injection still resolve a valid theme — mirrors
 * `EnvironmentValues+AppTheme.swift`. */
val LocalAppTheme = staticCompositionLocalOf { Theme.Default }

/** Whether [LocalAppTheme]'s current theme is rendering in its dark appearance right now — the
 * resolved outcome of [Theme.forcedDark], [AppColorSchemePreference], and the system setting (see
 * [MeshCoreTwoTheme]). Lets a caller resolve a [ThemeColor] (e.g. [Theme.surfaces]'s `card`) without
 * re-deriving that precedence itself. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * Whether the app should render dark right now: [theme]'s [Theme.forcedDark] wins over
 * [preference], which in turn wins over [systemDark]. Ported from
 * `ThemeService.effectiveColorScheme`'s precedence ("Theme-forced override wins; otherwise the
 * user's global preference applies"), pulled out of [MeshCoreTwoTheme] as a pure function so the
 * precedence itself is unit-testable without a Compose test harness.
 */
fun resolveIsDark(theme: Theme, preference: AppColorSchemePreference, systemDark: Boolean): Boolean =
    theme.forcedDark ?: preference.forcedDark ?: systemDark

/**
 * App-wide Material3 theme, driven by [themeService]'s selected [Theme] and
 * [AppColorSchemePreference] — a theme's [Theme.forcedDark] wins over the preference, mirroring
 * `ThemeService.effectiveColorScheme`. Dynamic color (Android 12+ wallpaper-derived palette) is
 * deliberately not used — target devices are non-GMS Huawei/OEM hardware (see project constraints) where its
 * behavior is inconsistent, and a fixed palette keeps the look identical across devices. Also
 * provides [LocalMeshExtendedColors] (status/radio colors) and [LocalAppTheme] (the active theme).
 */
@Composable
fun MeshCoreTwoTheme(themeService: ThemeService, content: @Composable () -> Unit) {
    val current by themeService.current.collectAsStateWithLifecycle()
    val preference by themeService.colorSchemePreference.collectAsStateWithLifecycle()
    val isDark = resolveIsDark(current, preference, systemDark = isSystemInDarkTheme())
    val extendedColors = if (isDark) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(
        LocalMeshExtendedColors provides extendedColors,
        LocalAppTheme provides current,
        LocalIsDarkTheme provides isDark,
    ) {
        MaterialTheme(
            colorScheme = current.colorScheme(isDark),
            typography = MeshCoreTwoTypography,
            shapes = MeshCoreTwoShapes,
            content = content,
        )
    }
}

private val DefaultLightColors = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    inversePrimary = md_theme_light_inversePrimary,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    surfaceTint = md_theme_light_surfaceTint,
    inverseSurface = md_theme_light_inverseSurface,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    outline = md_theme_light_outline,
    outlineVariant = md_theme_light_outlineVariant,
    scrim = md_theme_light_scrim,
    surfaceBright = md_theme_light_surfaceBright,
    surfaceDim = md_theme_light_surfaceDim,
    surfaceContainer = md_theme_light_surfaceContainer,
    surfaceContainerHigh = md_theme_light_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_light_surfaceContainerHighest,
    surfaceContainerLow = md_theme_light_surfaceContainerLow,
    surfaceContainerLowest = md_theme_light_surfaceContainerLowest,
)

private val DefaultDarkColors = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    inversePrimary = md_theme_dark_inversePrimary,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    surfaceTint = md_theme_dark_surfaceTint,
    inverseSurface = md_theme_dark_inverseSurface,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    outline = md_theme_dark_outline,
    outlineVariant = md_theme_dark_outlineVariant,
    scrim = md_theme_dark_scrim,
    surfaceBright = md_theme_dark_surfaceBright,
    surfaceDim = md_theme_dark_surfaceDim,
    surfaceContainer = md_theme_dark_surfaceContainer,
    surfaceContainerHigh = md_theme_dark_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_dark_surfaceContainerHighest,
    surfaceContainerLow = md_theme_dark_surfaceContainerLow,
    surfaceContainerLowest = md_theme_dark_surfaceContainerLowest,
)
