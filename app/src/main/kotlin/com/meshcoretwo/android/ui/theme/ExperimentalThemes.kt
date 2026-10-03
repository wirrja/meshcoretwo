// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The experimental themes behind Appearance's sixth tile ("Experimental themes"). Not a port —
 * MeshCore One has no counterpart. The classic five ([Theme.Default] … [Theme.Ultraviolet]) stay
 * exactly as they are; these may change between versions. Palettes are original (see
 * `ExperimentalThemePalettes.kt`); [ThemeStyle] carries what they change beyond color.
 */
object ExperimentalThemes {
    /** Red-only on black: keeps dark adaptation in the field. Maps render inverted in red. */
    val Night = Theme(
        id = "night",
        displayName = "Night",
        accentColor = ThemeColor(Color(0xFFE8291D), Color(0xFFE8291D)),
        outgoingTextColor = ThemeColor(Color(0xFF000000), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFFFF7A2E), Color(0xFFFF7A2E)),
        forcedDark = true,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFF000000), Color(0xFF000000)),
            card = ThemeColor(Color(0xFF2A0806), Color(0xFF2A0806)),
        ),
        identityGamut = IdentityGamut(listOf(0.0, 6.0, 12.0, 18.0), 0.85..1.0, maxJitter = 4.0),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 0.0, repeater = 12.0, room = 18.0),
        lightScheme = NightDarkColors,
        darkScheme = NightDarkColors,
        group = ThemeGroup.FUNCTIONAL,
        style = ThemeStyle(
            outline = ThemeColor(Color(0xFF3A0907), Color(0xFF3A0907)),
            shadow = false,
            outgoingBubble = ThemeColor(Color(0xFF4A0905), Color(0xFF4A0905)),
            outgoingBubbleText = ThemeColor(Color(0xFFFF4234), Color(0xFFFF4234)),
            nightMap = true,
        ),
        extendedColorsOverride = NightExtendedColors,
    )

    /** Maximum contrast for direct sunlight: pure white, black text, outlines instead of shadows. */
    val Daylight = Theme(
        id = "daylight",
        displayName = "Daylight",
        accentColor = ThemeColor(Color(0xFF1F4FD1), Color(0xFF1F4FD1)),
        outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFFFFFFFF)),
        hashtagColor = ThemeColor(Color(0xFF8A2E00), Color(0xFF8A2E00)),
        forcedDark = false,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFFFFFFF), Color(0xFFFFFFFF)),
            card = ThemeColor(Color(0xFFFFFFFF), Color(0xFFFFFFFF)),
        ),
        identityGamut = IdentityGamut(listOf(20.0, 45.0, 150.0, 215.0, 280.0, 330.0), 0.8..1.0),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 215.0, repeater = 150.0, room = 280.0),
        lightScheme = DaylightLightColors,
        darkScheme = DaylightLightColors,
        group = ThemeGroup.FUNCTIONAL,
        style = ThemeStyle(
            accentWash = false,
            outline = ThemeColor(Color(0xFF1A1C22), Color(0xFF1A1C22)),
            outlineWidth = 1.5.dp,
            shadow = false,
            mediumBody = true,
        ),
    )

    /** Moss and pine: calm green on a lichen-white / dark-conifer canvas. */
    val Taiga = Theme(
        id = "taiga",
        displayName = "Taiga",
        accentColor = ThemeColor(Color(0xFF2F6B3A), Color(0xFF7FCB8A)),
        outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFF8A5300), Color(0xFFEBC36F)),
        forcedDark = null,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFF2F6EF), Color(0xFF0A120C)),
            card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF152219)),
        ),
        identityGamut = IdentityGamut(listOf(25.0, 40.0, 95.0, 110.0, 125.0, 140.0, 155.0), 0.45..0.75),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 140.0, repeater = 110.0, room = 40.0),
        lightScheme = TaigaLightColors,
        darkScheme = TaigaDarkColors,
        group = ThemeGroup.COLOR,
    )

    /** Warm honey accent on cream / near-black. */
    val Amber = Theme(
        id = "amber",
        displayName = "Amber",
        accentColor = ThemeColor(Color(0xFF945500), Color(0xFFFFB547)),
        outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFF1D5FB0), Color(0xFF93C2FF)),
        forcedDark = null,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFFFF8EB), Color(0xFF130D05)),
            card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF231A0C)),
        ),
        identityGamut = IdentityGamut(listOf(28.0, 36.0, 44.0, 52.0, 60.0, 200.0, 215.0), 0.7..0.95),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 44.0, repeater = 28.0, room = 215.0),
        lightScheme = AmberLightColors,
        darkScheme = AmberDarkColors,
        group = ThemeGroup.COLOR,
    )

    /** Deep raspberry on a blush / wine canvas. */
    val Garnet = Theme(
        id = "garnet",
        displayName = "Garnet",
        accentColor = ThemeColor(Color(0xFFA8143F), Color(0xFFFF7A98)),
        outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFF5B3FB8), Color(0xFFC3B0FF)),
        forcedDark = null,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFFFF4F6), Color(0xFF15070B)),
            card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF2A1218)),
        ),
        identityGamut = IdentityGamut(listOf(5.0, 15.0, 280.0, 295.0, 335.0, 345.0, 355.0), 0.55..0.85),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 345.0, repeater = 5.0, room = 280.0),
        lightScheme = GarnetLightColors,
        darkScheme = GarnetDarkColors,
        group = ThemeGroup.COLOR,
    )

    /** Dusty pink-lilac, softer and warmer than Ultraviolet. */
    val Orchid = Theme(
        id = "orchid",
        displayName = "Orchid",
        accentColor = ThemeColor(Color(0xFF9A2F86), Color(0xFFF2A0E0)),
        outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFF0F6E8C), Color(0xFF7FD4EE)),
        forcedDark = null,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFFCF3FA), Color(0xFF130A13)),
            card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF241624)),
        ),
        identityGamut = IdentityGamut(listOf(200.0, 290.0, 300.0, 310.0, 320.0, 330.0, 345.0), 0.4..0.7),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 310.0, repeater = 345.0, room = 200.0),
        lightScheme = OrchidLightColors,
        darkScheme = OrchidDarkColors,
        group = ThemeGroup.COLOR,
    )

    /** Electric cyan with a mesh-network pattern behind the header; cards glow in dark. */
    val Mesh = Theme(
        id = "mesh",
        displayName = "Mesh",
        accentColor = ThemeColor(Color(0xFF00708F), Color(0xFF3DD8F5)),
        outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFF8A3FD1), Color(0xFFC49BFF)),
        forcedDark = null,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFF1F6F9), Color(0xFF060B16)),
            card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF0D1626)),
        ),
        identityGamut = IdentityGamut(listOf(165.0, 180.0, 195.0, 210.0, 225.0, 280.0), 0.5..0.85),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 195.0, repeater = 165.0, room = 280.0),
        lightScheme = MeshLightColors,
        darkScheme = MeshDarkColors,
        group = ThemeGroup.CHARACTER,
        style = ThemeStyle(
            pattern = BackdropPattern.MESH,
            patternColor = ThemeColor(Color(0x4D00708F), Color(0x663DD8F5)),
            outline = ThemeColor(Color(0xFFD3E3EC), Color(0xFF1C3350)),
            glow = ThemeColor(Color.Transparent, Color(0x993DD8F5)),
        ),
    )

    /** Blueprint: drafting grid, sharp corners, monospace headings. */
    val Blueprint = Theme(
        id = "blueprint",
        displayName = "Blueprint",
        accentColor = ThemeColor(Color(0xFF1D4E9E), Color(0xFFA9CCFF)),
        outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFFB4470F), Color(0xFFFFC08A)),
        forcedDark = null,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFF3F7FC), Color(0xFF0F2C57)),
            card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF163A6C)),
        ),
        identityGamut = IdentityGamut(listOf(30.0, 190.0, 205.0, 220.0, 235.0, 250.0), 0.35..0.7),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 220.0, repeater = 190.0, room = 30.0),
        lightScheme = BlueprintLightColors,
        darkScheme = BlueprintDarkColors,
        group = ThemeGroup.CHARACTER,
        style = ThemeStyle(
            pattern = BackdropPattern.GRID,
            patternColor = ThemeColor(Color(0x1A1D4E9E), Color(0x14FFFFFF)),
            outline = ThemeColor(Color(0xFFBFD3EE), Color(0x47FFFFFF)),
            shadow = false,
            corners = CornerStyle.SHARP,
            titleFont = FontFamily.Monospace,
        ),
    )

    /** Paper map: relief contour lines and serif headings. */
    val Topo = Theme(
        id = "topo",
        displayName = "Topo",
        accentColor = ThemeColor(Color(0xFF8A4A1F), Color(0xFFE7A877)),
        outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFF1E6A9E), Color(0xFF8CC5EE)),
        forcedDark = null,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFF5EFE2), Color(0xFF14110B)),
            card = ThemeColor(Color(0xFFFFFCF5), Color(0xFF221D14)),
        ),
        identityGamut = IdentityGamut(listOf(20.0, 30.0, 40.0, 90.0, 110.0, 200.0), 0.35..0.65),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 110.0, repeater = 30.0, room = 200.0),
        lightScheme = TopoLightColors,
        darkScheme = TopoDarkColors,
        group = ThemeGroup.CHARACTER,
        style = ThemeStyle(
            pattern = BackdropPattern.CONTOUR,
            patternColor = ThemeColor(Color(0xFFD6C29C), Color(0xFF3E3424)),
            outline = ThemeColor(Color(0xFFE4D8BF), Color(0xFF3A3020)),
            titleFont = FontFamily.Serif,
        ),
    )

    /** Green terminal phosphor: monospace everywhere, scanlines, glowing text. */
    val Phosphor = Theme(
        id = "phosphor",
        displayName = "Phosphor",
        accentColor = ThemeColor(Color(0xFF2BE36A), Color(0xFF2BE36A)),
        outgoingTextColor = ThemeColor(Color(0xFF021006), Color(0xFF021006)),
        hashtagColor = ThemeColor(Color(0xFFFFC24A), Color(0xFFFFC24A)),
        forcedDark = true,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFF020702), Color(0xFF020702)),
            card = ThemeColor(Color(0xFF041004), Color(0xFF041004)),
        ),
        identityGamut = IdentityGamut(listOf(100.0, 115.0, 130.0, 145.0, 160.0), 0.6..0.9, maxJitter = 7.0),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 130.0, repeater = 100.0, room = 160.0),
        lightScheme = PhosphorDarkColors,
        darkScheme = PhosphorDarkColors,
        group = ThemeGroup.CHARACTER,
        style = ThemeStyle(
            pattern = BackdropPattern.SCANLINES,
            patternColor = ThemeColor(Color(0x107DFF9E), Color(0x107DFF9E)),
            outline = ThemeColor(Color(0xFF1E5A2C), Color(0xFF1E5A2C)),
            shadow = false,
            corners = CornerStyle.SQUARE,
            bodyFont = FontFamily.Monospace,
            textGlow = ThemeColor(Color(0x737DFF9E), Color(0x737DFF9E)),
        ),
    )

    /** Synthwave: magenta neon on a purple night, a perspective floor grid under a striped sun. */
    val Neon = Theme(
        id = "neon",
        displayName = "Neon",
        accentColor = ThemeColor(Color(0xFFFF2E97), Color(0xFFFF2E97)),
        outgoingTextColor = ThemeColor(Color(0xFF000000), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFF2DE2E6), Color(0xFF2DE2E6)),
        forcedDark = true,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFF0D0221), Color(0xFF0D0221)),
            card = ThemeColor(Color(0xFF1A0B36), Color(0xFF1A0B36)),
        ),
        identityGamut = IdentityGamut(listOf(55.0, 180.0, 190.0, 300.0, 315.0, 330.0), 0.7..1.0),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 315.0, repeater = 180.0, room = 55.0),
        lightScheme = NeonDarkColors,
        darkScheme = NeonDarkColors,
        group = ThemeGroup.CHARACTER,
        style = ThemeStyle(
            pattern = BackdropPattern.HORIZON,
            patternColor = ThemeColor(Color(0x59FF2E97), Color(0x59FF2E97)),
            patternAccent = ThemeColor(Color(0x40FF8A3D), Color(0x40FF8A3D)),
            outline = ThemeColor(Color(0x80FF2E97), Color(0x80FF2E97)),
            glow = ThemeColor(Color(0x99FF2E97), Color(0x99FF2E97)),
            titleGlow = ThemeColor(Color(0xB3FF2E97), Color(0xB3FF2E97)),
        ),
    )

    /** Safety yellow and black: a hazard-tape band at the bottom, heavy outlines and titles. */
    val Hazard = Theme(
        id = "hazard",
        displayName = "Hazard",
        accentColor = ThemeColor(Color(0xFFFFD400), Color(0xFFFFD400)),
        outgoingTextColor = ThemeColor(Color(0xFF111111), Color(0xFF111111)),
        hashtagColor = ThemeColor(Color(0xFFC2410C), Color(0xFFFF8A4C)),
        forcedDark = null,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFFFF7D1), Color(0xFF0E0E0E)),
            card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF1A1A1A)),
        ),
        identityGamut = IdentityGamut(listOf(0.0, 15.0, 30.0, 210.0, 230.0, 260.0), 0.7..1.0),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 30.0, repeater = 210.0, room = 0.0),
        lightScheme = HazardLightColors,
        darkScheme = HazardDarkColors,
        group = ThemeGroup.CHARACTER,
        style = ThemeStyle(
            pattern = BackdropPattern.HAZARD,
            patternColor = ThemeColor(Color(0xE6111111), Color(0xCCFFD400)),
            outline = ThemeColor(Color(0xFF111111), Color(0x99FFD400)),
            outlineWidth = 2.dp,
            shadow = false,
            corners = CornerStyle.SHARP,
            boldTitles = true,
            mediumBody = true,
            outgoingBubble = ThemeColor(Color(0xFFFFD400), Color(0xFFFFD400)),
            outgoingBubbleText = ThemeColor(Color(0xFF111111), Color(0xFF111111)),
        ),
    )

    /** Ruled notebook paper with a red margin and blue ink; a chalkboard in dark. Handwritten titles. */
    val Notebook = Theme(
        id = "notebook",
        displayName = "Notebook",
        accentColor = ThemeColor(Color(0xFF1F4BA5), Color(0xFFF4E9A3)),
        outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFFB3261E), Color(0xFFF5B0B0)),
        forcedDark = null,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFFBFAF5), Color(0xFF1F2A24)),
            card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF27342D)),
        ),
        identityGamut = IdentityGamut(listOf(0.0, 140.0, 210.0, 225.0, 270.0), 0.6..0.9),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 225.0, repeater = 140.0, room = 0.0),
        lightScheme = NotebookLightColors,
        darkScheme = NotebookDarkColors,
        group = ThemeGroup.CHARACTER,
        style = ThemeStyle(
            pattern = BackdropPattern.RULED,
            patternColor = ThemeColor(Color(0xFFCFE0F5), Color(0x14FFFFFF)),
            patternAccent = ThemeColor(Color(0x99E57373), Color.Transparent),
            corners = CornerStyle.SHARP,
            titleFont = FontFamily.Cursive,
        ),
    )

    /** Newsprint: halftone dots, serif type throughout, square corners, ink rules, headline red. */
    val Newsprint = Theme(
        id = "newsprint",
        displayName = "Newsprint",
        accentColor = ThemeColor(Color(0xFFB3261E), Color(0xFFFF6B5E)),
        outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFF1F4BA5), Color(0xFF8FB4FF)),
        forcedDark = null,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFF4F1EA), Color(0xFF121212)),
            card = ThemeColor(Color(0xFFFBF9F4), Color(0xFF1C1C1C)),
        ),
        identityGamut = IdentityGamut(listOf(0.0, 20.0, 30.0, 210.0, 230.0), 0.25..0.55),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 0.0, repeater = 210.0, room = 30.0),
        lightScheme = NewsprintLightColors,
        darkScheme = NewsprintDarkColors,
        group = ThemeGroup.CHARACTER,
        style = ThemeStyle(
            pattern = BackdropPattern.HALFTONE,
            patternColor = ThemeColor(Color(0x24111111), Color(0x17FFFFFF)),
            accentWash = false,
            outline = ThemeColor(Color(0xFF1A1A1A), Color(0xFF5A5752)),
            shadow = false,
            corners = CornerStyle.SQUARE,
            titleFont = FontFamily.Serif,
            bodyFont = FontFamily.Serif,
        ),
    )

    /** A star atlas in light, the night sky in dark: a starfield with constellations, gold on navy. */
    val Starmap = Theme(
        id = "starmap",
        displayName = "Starmap",
        accentColor = ThemeColor(Color(0xFF454BA8), Color(0xFFF2C14E)),
        outgoingTextColor = ThemeColor(Color(0xFFFFFFFF), Color(0xFF000000)),
        hashtagColor = ThemeColor(Color(0xFFA1580B), Color(0xFF9EC3FF)),
        forcedDark = null,
        surfaces = Surfaces(
            canvas = ThemeColor(Color(0xFFF3F1FA), Color(0xFF070A1F)),
            card = ThemeColor(Color(0xFFFFFFFF), Color(0xFF11163A)),
        ),
        // Blues and violets only: gold, darkened enough for names and avatars on the light atlas,
        // turns muddy olive.
        identityGamut = IdentityGamut(listOf(205.0, 220.0, 235.0, 250.0, 265.0, 280.0, 300.0), 0.45..0.8),
        categoryAvatarOverride = null,
        categoryHues = CategoryHues(channel = 250.0, repeater = 220.0, room = 280.0),
        lightScheme = StarmapLightColors,
        darkScheme = StarmapDarkColors,
        group = ThemeGroup.CHARACTER,
        style = ThemeStyle(
            pattern = BackdropPattern.STARS,
            patternColor = ThemeColor(Color(0x8C2B2F77), Color(0xE6FFFFFF)),
            patternAccent = ThemeColor(Color(0x402B2F77), Color(0x59F2C14E)),
            outline = ThemeColor(Color(0xFFE2DEF2), Color(0xFF232A5C)),
            glow = ThemeColor(Color.Transparent, Color(0x4DF2C14E)),
        ),
    )

    val all: List<Theme> = listOf(
        Night, Daylight, Taiga, Amber, Garnet, Orchid, Mesh, Blueprint, Topo, Phosphor,
        Neon, Hazard, Notebook, Newsprint, Starmap,
    )
}

/** Night's status colors: every success/caution/radio hue collapses onto reds of different lightness. */
val NightExtendedColors = MeshExtendedColors(
    success = Color(0xFFFF8A7A),
    caution = Color(0xFFFF5A3C),
    warning = Color(0xFFFF6E3A),
    danger = Color(0xFFE8291D),
    info = Color(0xFFFF4234),
    radioReady = Color(0xFFFF8A7A),
    radioConnecting = Color(0xFFFF5A3C),
    radioRepeatMode = Color(0xFFFF6E3A),
)
