// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Status colors outside Material3's baseline `ColorScheme` roles — success/caution/warning/danger/
 * info (theme-adaptive) and the fixed BLE/radio status hues (neither adaptive). Unlike the identity
 * gamut and category avatar colors (Phase 6 slice 3 moved those onto [Theme] — they vary per
 * *selected theme*, not per appearance), every field here is the same across all 10 built-in
 * themes, matching iOS where `ClearanceStatus`/`SNRQuality`/`RxLogView`'s status colors and
 * `AppColors.Radio` are system-adaptive/fixed constants independent of `Theme`. See `Color.kt`'s
 * doc comment for how each value was derived and which iOS source each maps to.
 */
data class MeshExtendedColors(
    val success: Color,
    val caution: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val radioReady: Color,
    val radioConnecting: Color,
    val radioRepeatMode: Color,
)

val LightExtendedColors = MeshExtendedColors(
    success = md_theme_light_success,
    caution = md_theme_light_caution,
    warning = md_theme_light_warning,
    danger = md_theme_light_danger,
    info = md_theme_light_info,
    radioReady = RadioReady,
    radioConnecting = RadioConnecting,
    radioRepeatMode = RadioRepeatMode,
)

val DarkExtendedColors = MeshExtendedColors(
    success = md_theme_dark_success,
    caution = md_theme_dark_caution,
    warning = md_theme_dark_warning,
    danger = md_theme_dark_danger,
    info = md_theme_dark_info,
    radioReady = RadioReady,
    radioConnecting = RadioConnecting,
    radioRepeatMode = RadioRepeatMode,
)

val LocalMeshExtendedColors = staticCompositionLocalOf { LightExtendedColors }
