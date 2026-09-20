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
    /** Forces one appearance regardless of [AppColorSchemePreference] — only [Ember] sets this. */
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

        val Ember = Theme(
            id = "ember",
            displayName = "Ember",
            accentColor = ThemeColor(Color(0xFFB81A1A), Color(0xFFB81A1A)),
            outgoingTextColor = ThemeColor(Color(0xFFF2D9D9), Color(0xFFF2D9D9)),
            hashtagColor = ThemeColor(Color(0xFFFFBF4C), Color(0xFFFFBF4C)),
            forcedDark = true,
            surfaces = Surfaces(canvas = ThemeColor(Color.Black, Color.Black), card = null),
            identityGamut = IdentityGamut(listOf(0.0, 8.0, 12.0, 24.0, 18.0, 345.0), 0.50..0.82),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 0.0, repeater = 24.0, room = 345.0),
            lightScheme = EmberLightColors,
            darkScheme = EmberDarkColors,
        )

        val Fern = Theme(
            id = "fern",
            displayName = "Fern",
            accentColor = ThemeColor(Color(0xFF2E662E), Color(0xFF8CCC80)),
            outgoingTextColor = ThemeColor(Color.White, Color.Black),
            hashtagColor = ThemeColor(Color(0xFF2E6B1F), Color(0xFF9ED973)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFF1F4EE), Color(0xFF050A06)),
                card = ThemeColor(Color(0xFFFCFDFC), Color(0xFF151F18)),
            ),
            identityGamut = IdentityGamut(listOf(72.0, 90.0, 108.0, 128.0, 150.0, 165.0), 0.35..0.65),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 90.0, repeater = 128.0, room = 165.0),
            lightScheme = FernLightColors,
            darkScheme = FernDarkColors,
        )

        val Marine = Theme(
            id = "marine",
            displayName = "Marine",
            accentColor = ThemeColor(Color(0xFF1A408C), Color(0xFF385EBD)),
            outgoingTextColor = ThemeColor(Color.White, Color.White),
            hashtagColor = ThemeColor(Color(0xFF0F6B8F), Color(0xFF8CD9FF)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFF0F4F8), Color(0xFF040C16)),
                card = ThemeColor(Color(0xFFFCFCFD), Color(0xFF101926)),
            ),
            identityGamut = IdentityGamut(listOf(178.0, 188.0, 200.0, 215.0, 232.0, 245.0), 0.40..0.72),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 215.0, repeater = 188.0, room = 245.0),
            lightScheme = MarineLightColors,
            darkScheme = MarineDarkColors,
        )

        val Olive = Theme(
            id = "olive",
            displayName = "Olive",
            accentColor = ThemeColor(Color(0xFF4C662E), Color(0xFF8CA64C)),
            outgoingTextColor = ThemeColor(Color.White, Color.Black),
            hashtagColor = ThemeColor(Color(0xFF3D7024), Color(0xFF8CF28C)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFF4F3EC), Color(0xFF090902)),
                card = ThemeColor(Color(0xFFFDFDFB), Color(0xFF1B1A11)),
            ),
            identityGamut = IdentityGamut(listOf(45.0, 55.0, 70.0, 85.0, 100.0, 112.0), 0.35..0.62),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 85.0, repeater = 112.0, room = 70.0),
            lightScheme = OliveLightColors,
            darkScheme = OliveDarkColors,
        )

        val Lavender = Theme(
            id = "lavender",
            displayName = "Lavender",
            accentColor = ThemeColor(Color(0xFF6B47AD), Color(0xFFC7A6F2)),
            outgoingTextColor = ThemeColor(Color.White, Color.Black),
            hashtagColor = ThemeColor(Color(0xFF85529E), Color(0xFFE0B2F2)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFF4F1F8), Color(0xFF0B0512)),
                card = ThemeColor(Color(0xFFFDFCFD), Color(0xFF1D1329)),
            ),
            identityGamut = IdentityGamut(listOf(222.0, 238.0, 248.0, 265.0, 285.0, 302.0), 0.35..0.62),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 265.0, repeater = 222.0, room = 302.0),
            lightScheme = LavenderLightColors,
            darkScheme = LavenderDarkColors,
        )

        /** Proper noun (romanized Japanese), never translated — see `Theme+LocalizedName.swift`. */
        val Sakura = Theme(
            id = "sakura",
            displayName = "Sakura",
            accentColor = ThemeColor(Color(0xFF8C3373), Color(0xFFF2B2D1)),
            outgoingTextColor = ThemeColor(Color.White, Color.Black),
            hashtagColor = ThemeColor(Color(0xFF9E5C2E), Color(0xFFFFCC8C)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFF8F2F4), Color(0xFF10060A)),
                card = ThemeColor(Color(0xFFFDFCFD), Color(0xFF22131A)),
            ),
            identityGamut = IdentityGamut(listOf(290.0, 305.0, 320.0, 335.0, 350.0, 8.0), 0.40..0.70),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 320.0, repeater = 290.0, room = 350.0),
            lightScheme = SakuraLightColors,
            darkScheme = SakuraDarkColors,
        )

        /** Proper noun — name of the upstream MIT-licensed palette (`THIRD_PARTY_NOTICES.md`). */
        val Solarized = Theme(
            id = "solarized",
            displayName = "Solarized",
            accentColor = ThemeColor(Color(0xFF1E77B5), Color(0xFF1E77B5)),
            outgoingTextColor = ThemeColor(Color.White, Color.White),
            hashtagColor = ThemeColor(Color(0xFF207B74), Color(0xFF31BCB2)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFEEE8D5), Color(0xFF001E26)),
                card = ThemeColor(Color(0xFFFDF6E3), Color(0xFF0C4250)),
            ),
            identityGamut = IdentityGamut(listOf(1.0, 18.0, 45.0, 68.0, 175.0, 205.0, 237.0, 331.0), 0.50..0.80),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 205.0, repeater = 175.0, room = 331.0),
            lightScheme = SolarizedLightColors,
            darkScheme = SolarizedDarkColors,
        )

        /** Proper noun — name of the upstream MIT-licensed palette (`THIRD_PARTY_NOTICES.md`). */
        val Nord = Theme(
            id = "nord",
            displayName = "Nord",
            accentColor = ThemeColor(Color(0xFF4C6F9E), Color(0xFFA0D0DE)),
            outgoingTextColor = ThemeColor(Color.White, Color.Black),
            hashtagColor = ThemeColor(Color(0xFF567983), Color(0xFFE2B3DA)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFECEFF4), Color(0xFF262B35)),
                card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF454D60)),
            ),
            identityGamut = IdentityGamut(
                listOf(14.0, 40.0, 92.0, 178.0, 193.0, 210.0, 213.0, 240.0, 280.0, 311.0, 354.0),
                0.25..0.52,
            ),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 210.0, repeater = 193.0, room = 280.0),
            lightScheme = NordLightColors,
            darkScheme = NordDarkColors,
        )

        /** Proper noun — name of the upstream MIT-licensed palette (`THIRD_PARTY_NOTICES.md`). */
        val Catppuccin = Theme(
            id = "catppuccin",
            displayName = "Catppuccin",
            accentColor = ThemeColor(Color(0xFF8839EF), Color(0xFFD9C0FB)),
            outgoingTextColor = ThemeColor(Color.White, Color(0xFF0B0B14)),
            hashtagColor = ThemeColor(Color(0xFF174FBE), Color(0xFFA6C8FF)),
            forcedDark = null,
            surfaces = Surfaces(
                canvas = ThemeColor(Color(0xFFEFF1F5), Color(0xFF11111B)),
                card = ThemeColor(Color(0xFFCCD0DA), Color(0xFF45475A)),
            ),
            identityGamut = IdentityGamut(
                listOf(0.0, 10.0, 23.0, 41.0, 115.0, 170.0, 189.0, 199.0, 217.0, 232.0, 267.0, 316.0, 343.0, 351.0),
                0.40..0.70,
            ),
            categoryAvatarOverride = null,
            categoryHues = CategoryHues(channel = 267.0, repeater = 217.0, room = 316.0),
            lightScheme = CatppuccinLightColors,
            darkScheme = CatppuccinDarkColors,
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
