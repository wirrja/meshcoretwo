// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.ui.graphics.Color

// Full Material3 tonal palette generated from the iOS accent color (`AppColors.Message
// .outgoingBubble`, `#2463EB`) as the seed, via the Material color-utilities "Fidelity" scheme
// variant (Hct(seed) tonal spot generation, contrast level 0) — the same algorithm and inputs
// Material Theme Builder (m3.material.io) uses, computed offline with the `materialyoucolor`
// Python port for this port (no such tool ships in this repo or at runtime). "Fidelity" was chosen
// over the default "TonalSpot" because it keeps `primaryContainer` equal to the seed itself and
// `primary` close to its hue/chroma, matching iOS where the accent is used directly rather than
// desaturated — TonalSpot's output read as a generic muted blue, losing the brand color entirely.
// Naming follows Material Theme Builder's own convention (`md_theme_<mode>_<role>`) for the exact
// 35 roles `androidx.compose.material3.ColorScheme`'s constructor takes (Compose BOM 2024.12.01 /
// material3 1.3.x — no `Fixed`/`Dim` roles, those are a later "Expressive" spec revision).

val md_theme_light_primary = Color(0xFF004BC6)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFF2463EB)
val md_theme_light_onPrimaryContainer = Color(0xFFEEEFFF)
val md_theme_light_inversePrimary = Color(0xFFB4C5FF)
val md_theme_light_secondary = Color(0xFF495C95)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFACBFFF)
val md_theme_light_onSecondaryContainer = Color(0xFF394C84)
val md_theme_light_tertiary = Color(0xFF943700)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFBC4800)
val md_theme_light_onTertiaryContainer = Color(0xFFFFEDE6)
val md_theme_light_background = Color(0xFFFAF8FF)
val md_theme_light_onBackground = Color(0xFF191B23)
val md_theme_light_surface = Color(0xFFFAF8FF)
val md_theme_light_onSurface = Color(0xFF191B23)
val md_theme_light_surfaceVariant = Color(0xFFDFE1F4)
val md_theme_light_onSurfaceVariant = Color(0xFF434655)
val md_theme_light_surfaceTint = Color(0xFF0053DA)
val md_theme_light_inverseSurface = Color(0xFF2E3039)
val md_theme_light_inverseOnSurface = Color(0xFFF0F0FB)
val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onErrorContainer = Color(0xFF93000A)
val md_theme_light_outline = Color(0xFF737686)
val md_theme_light_outlineVariant = Color(0xFFC3C6D7)
val md_theme_light_scrim = Color(0xFF000000)
val md_theme_light_surfaceBright = Color(0xFFFAF8FF)
val md_theme_light_surfaceDim = Color(0xFFD9D9E5)
val md_theme_light_surfaceContainer = Color(0xFFEDEDF9)
val md_theme_light_surfaceContainerHigh = Color(0xFFE7E7F3)
val md_theme_light_surfaceContainerHighest = Color(0xFFE1E2ED)
val md_theme_light_surfaceContainerLow = Color(0xFFF3F3FE)
val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF)

val md_theme_dark_primary = Color(0xFFB4C5FF)
val md_theme_dark_onPrimary = Color(0xFF002A77)
val md_theme_dark_primaryContainer = Color(0xFF2463EB)
val md_theme_dark_onPrimaryContainer = Color(0xFFEEEFFF)
val md_theme_dark_inversePrimary = Color(0xFF0053DA)
val md_theme_dark_secondary = Color(0xFFB4C5FF)
val md_theme_dark_onSecondary = Color(0xFF182D63)
val md_theme_dark_secondaryContainer = Color(0xFF33477E)
val md_theme_dark_onSecondaryContainer = Color(0xFFA3B6F5)
val md_theme_dark_tertiary = Color(0xFFFFB596)
val md_theme_dark_onTertiary = Color(0xFF581E00)
val md_theme_dark_tertiaryContainer = Color(0xFFBC4800)
val md_theme_dark_onTertiaryContainer = Color(0xFFFFEDE6)
val md_theme_dark_background = Color(0xFF11131B)
val md_theme_dark_onBackground = Color(0xFFE1E2ED)
val md_theme_dark_surface = Color(0xFF11131B)
val md_theme_dark_onSurface = Color(0xFFE1E2ED)
val md_theme_dark_surfaceVariant = Color(0xFF434655)
val md_theme_dark_onSurfaceVariant = Color(0xFFC3C6D7)
val md_theme_dark_surfaceTint = Color(0xFFB4C5FF)
val md_theme_dark_inverseSurface = Color(0xFFE1E2ED)
val md_theme_dark_inverseOnSurface = Color(0xFF2E3039)
val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_theme_dark_outline = Color(0xFF8D90A0)
val md_theme_dark_outlineVariant = Color(0xFF434655)
val md_theme_dark_scrim = Color(0xFF000000)
val md_theme_dark_surfaceBright = Color(0xFF373942)
val md_theme_dark_surfaceDim = Color(0xFF11131B)
val md_theme_dark_surfaceContainer = Color(0xFF1D1F27)
val md_theme_dark_surfaceContainerHigh = Color(0xFF282A32)
val md_theme_dark_surfaceContainerHighest = Color(0xFF32343D)
val md_theme_dark_surfaceContainerLow = Color(0xFF191B23)
val md_theme_dark_surfaceContainerLowest = Color(0xFF0C0E16)

// Semantic status colors (success/caution/warning/danger/info), not part of Material3's baseline
// `ColorScheme` roles. Ported concept from iOS, which draws these straight from SwiftUI's adaptive
// system colors rather than app-defined constants (`ClearanceStatus+UI.swift`'s `.green`/`.yellow`/
// `.orange`/`.red`, `SNRQuality+Color.swift`'s `.green`/`.yellow`/`.red`, `RxLogView.swift`'s
// `.green`/`.blue`/`.orange`) — four hues cover clearance status (clear/marginal/partial/blocked)
// and RX Log's flood-vs-direct indicator needs a fifth (info/blue), so this splits "warning" into
// "caution" (yellow, marginal signal) and "warning" (orange, partial obstruction) to keep every
// source hue distinct, rather than collapsing to the three names this phase's PLAN.md entry
// suggested. Each role is generated at Material's own tone convention for onSurface-role text/icon
// color (tone 40 light / tone 80 dark, same as `error`/`onError`) from the hue+chroma of the flat
// hex values this port already had scattered across `SavedPathRow.kt`/`LineOfSightFormatting.kt`/
// `ComparisonRow.kt`/`RxLogScreen.kt`/`TraceResultHopRow.kt`/`NeighborSnrMapScreen.kt` before this
// slice, so the light value matches what was already on screen and only the (previously missing)
// dark value is new.
val md_theme_light_success = Color(0xFF1B6D24)
val md_theme_dark_success = Color(0xFF88D982)
val md_theme_light_caution = Color(0xFF835400)
val md_theme_dark_caution = Color(0xFFFFB957)
val md_theme_light_warning = Color(0xFF9C4400)
val md_theme_dark_warning = Color(0xFFFFB68F)
val md_theme_light_danger = Color(0xFFB91D20)
val md_theme_dark_danger = Color(0xFFFFB4AC)
val md_theme_light_info = Color(0xFF005DB7)
val md_theme_dark_info = Color(0xFFA9C7FF)

// Radio/BLE status colors (`AppColors.Radio` on iOS) — unlike the semantic colors above, these are
// fixed hex constants on iOS too (not adaptive system colors), so there is deliberately no dark
// variant here; ported 1:1.
val RadioReady = Color(0xFF34C759)
val RadioConnecting = Color(0xFF007AFF)
val RadioRepeatMode = Color(0xFFFF9500)

// Default theme's identity-color gamut (`Theme.default.identityGamut` on iOS) — the hue anchors
// and saturation band contact avatars, channel sender names, and mentions draw from. Only the
// Default theme's values are ported so far (Phase 6 slice 4); the other 9 themes' gamuts are
// ported in slice 3 alongside the theme system itself.
val DefaultIdentityHueAnchors = listOf(18.0, 25.0, 44.0, 77.0, 120.0, 180.0, 215.0, 255.0, 307.0, 343.0)
val DefaultIdentitySaturation = 0.45..0.70

// Fixed channel/repeater/room avatar colors (`Theme.default.categoryAvatarOverride` on iOS) — the
// Default/System theme pins these to legacy values instead of deriving them from the identity
// gamut, same as the Radio colors above; ported 1:1, no dark variant.
val CategoryChannel = Color(0xFF336688)
val CategoryRepeaterNode = Color(0xFF00AAFF)
val CategoryRoom = Color(0xFFFF8800)
