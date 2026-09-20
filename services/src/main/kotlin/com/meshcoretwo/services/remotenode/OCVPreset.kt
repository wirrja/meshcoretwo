// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

/** Whether a preset models a generic battery chemistry or one specific commercial device. */
internal enum class OCVPresetCategory { BATTERY_CHEMISTRY, DEVICE_SPECIFIC }

/**
 * Battery OCV (Open Circuit Voltage) presets for accurate percentage calculation. Ported from
 * `OCVPreset.swift`. Each preset carries 11 millivolt values mapping to 100%, 90%, 80%... 0%.
 *
 * [rawValue] is pinned to Swift's `String` raw values — `ContactDto.ocvPreset`/`DeviceDto.ocvPreset`
 * persist this string directly (see `ContactService.updateContactOCVSettings`/
 * `DeviceService.updateOCVSettings`), so a case rename here would silently reclassify already-stored
 * rows as an unrecognized preset (falling back to Li-Ion, see [fromRawValue] usage).
 *
 * Reference: https://github.com/meshtastic/firmware
 */
enum class OCVPreset(val rawValue: String) {
    LI_ION("liIon"),
    LI_FE_PO4("liFePO4"),
    LEAD_ACID("leadAcid"),
    ALKALINE("alkaline"),
    NI_MH("niMH"),
    LTO("lto"),
    TRACKER_T1000E("trackerT1000E"),
    HELTEC_POCKET_5000("heltecPocket5000"),
    HELTEC_POCKET_10000("heltecPocket10000"),
    SEEED_WIO_TRACKER("seeedWioTracker"),
    SEEED_SOLAR_NODE("seeedSolarNode"),
    R1_NEO("r1Neo"),
    WIS_MESH_TAG("wisMeshTag"),
    LILY_GO_TBEAM_1W("lilyGoTBeam1W"),
    THINK_NODE_M6("thinkNodeM6"),
    CUSTOM("custom"),
    ;

    /** The 11-point OCV array in millivolts (100% to 0% in 10% steps). */
    val ocvArray: List<Int>
        get() = when (this) {
            LI_ION -> listOf(4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100)
            LI_FE_PO4 -> listOf(3400, 3350, 3320, 3290, 3270, 3260, 3250, 3230, 3200, 3120, 3000)
            LEAD_ACID -> listOf(2120, 2090, 2070, 2050, 2030, 2010, 1990, 1980, 1970, 1960, 1950)
            ALKALINE -> listOf(1580, 1400, 1350, 1300, 1280, 1250, 1230, 1190, 1150, 1100, 1000)
            NI_MH -> listOf(1400, 1300, 1280, 1270, 1260, 1250, 1240, 1230, 1210, 1150, 1000)
            LTO -> listOf(2770, 2650, 2540, 2420, 2300, 2180, 2060, 1940, 1800, 1680, 1550)
            TRACKER_T1000E -> listOf(4190, 4042, 3957, 3885, 3820, 3776, 3746, 3725, 3696, 3644, 3100)
            HELTEC_POCKET_5000 -> listOf(4300, 4240, 4120, 4000, 3888, 3800, 3740, 3698, 3655, 3580, 3400)
            HELTEC_POCKET_10000 -> listOf(4100, 4060, 3960, 3840, 3729, 3625, 3550, 3500, 3420, 3345, 3100)
            SEEED_WIO_TRACKER -> listOf(4200, 3876, 3826, 3763, 3713, 3660, 3573, 3485, 3422, 3359, 3300)
            SEEED_SOLAR_NODE -> listOf(4200, 3986, 3922, 3812, 3734, 3645, 3527, 3420, 3281, 3087, 2786)
            R1_NEO -> listOf(4120, 4020, 4000, 3940, 3870, 3820, 3750, 3630, 3550, 3450, 3100)
            WIS_MESH_TAG -> listOf(4160, 4020, 3940, 3870, 3810, 3760, 3740, 3720, 3680, 3620, 2990)
            LILY_GO_TBEAM_1W -> listOf(7950, 7850, 7750, 7580, 7440, 7310, 7150, 7005, 6860, 6685, 6000)
            THINK_NODE_M6 -> listOf(4080, 3990, 3935, 3880, 3825, 3770, 3715, 3660, 3605, 3550, 3450)
            CUSTOM -> LI_ION.ocvArray // Fallback, actual custom values stored separately.
        }

    /** Human-readable display name. */
    val displayName: String
        get() = when (this) {
            LI_ION -> "Li-Ion (Default)"
            LI_FE_PO4 -> "LiFePO4"
            LEAD_ACID -> "Lead Acid"
            ALKALINE -> "Alkaline"
            NI_MH -> "NiMH"
            LTO -> "LTO"
            TRACKER_T1000E -> "Tracker T1000-E"
            HELTEC_POCKET_5000 -> "Heltec Pocket 5000"
            HELTEC_POCKET_10000 -> "Heltec Pocket 10000"
            SEEED_WIO_TRACKER -> "Seeed WIO Tracker"
            SEEED_SOLAR_NODE -> "Seeed Solar Node"
            R1_NEO -> "R1 Neo"
            WIS_MESH_TAG -> "WisMesh Tag"
            LILY_GO_TBEAM_1W -> "LilyGo T-Beam 1W"
            THINK_NODE_M6 -> "ThinkNode M6"
            CUSTOM -> "Custom"
        }

    internal val category: OCVPresetCategory
        get() = when (this) {
            LI_ION, LI_FE_PO4, LEAD_ACID, ALKALINE, NI_MH, LTO -> OCVPresetCategory.BATTERY_CHEMISTRY
            TRACKER_T1000E, HELTEC_POCKET_5000, HELTEC_POCKET_10000, SEEED_WIO_TRACKER, SEEED_SOLAR_NODE,
            R1_NEO, WIS_MESH_TAG, LILY_GO_TBEAM_1W, THINK_NODE_M6, CUSTOM,
            -> OCVPresetCategory.DEVICE_SPECIFIC
        }

    companion object {
        /** Valid range for user-entered OCV voltage values (millivolts). Upper bound accommodates multi-cell series packs. */
        val VALID_MILLIVOLT_RANGE = 1000..99999

        /** All presets except [CUSTOM] (for picker display). */
        val selectablePresets: List<OCVPreset> get() = entries.filter { it != CUSTOM }

        /** Battery chemistry presets only (excludes device-specific and custom). */
        val batteryChemistryPresets: List<OCVPreset> get() = entries.filter { it.category == OCVPresetCategory.BATTERY_CHEMISTRY }

        /** Presets available for remote node configuration: battery chemistry types plus select device-specific presets. */
        val nodePresets: List<OCVPreset> get() = batteryChemistryPresets + SEEED_SOLAR_NODE

        fun fromRawValue(value: String): OCVPreset? = entries.find { it.rawValue == value }

        /**
         * Returns the OCV preset for a known manufacturer name, or `null` if no match. Manufacturer
         * names must exactly match the strings returned by `getManufacturerName()` in the MeshCore
         * firmware's device variant headers (`{device_variant}.h`).
         * See: https://github.com/meshcore-dev/MeshCore
         */
        fun preset(forManufacturer: String): OCVPreset? = when (forManufacturer) {
            "Seeed Tracker T1000-e", "Seeed Tracker T1000-E" -> TRACKER_T1000E
            "Seeed Wio Tracker L1" -> SEEED_WIO_TRACKER
            "Seeed SenseCap Solar" -> SEEED_SOLAR_NODE
            "RAK WisMesh Tag" -> WIS_MESH_TAG
            "LilyGo T-Beam 1W" -> LILY_GO_TBEAM_1W
            "Elecrow ThinkNode M6" -> THINK_NODE_M6
            else -> null
        }
    }
}
