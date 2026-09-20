// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Radio configuration preset for common regional settings. Ported from `RadioPresets.swift`. */
object RadioPresets {
    /** Geographic regions for radio preset filtering. */
    enum class RadioRegion(val shortCode: String) {
        NORTH_AMERICA("NA"),
        SOUTH_AMERICA("SA"),
        EUROPE("EU"),
        OCEANIA("AU"),
        ASIA("AS"),
        /** Not a continent: a standalone community network with its own dedicated preset. */
        MESHCORETEL_MOW("MOW"),
        ;

        companion object {
            /** Regions that should be shown for a given locale, most-relevant first. */
            fun regionsForLocale(locale: Locale = Locale.getDefault()): List<RadioRegion> =
                when (locale.country) {
                    "US", "CA", "CR" -> listOf(NORTH_AMERICA, EUROPE, OCEANIA, ASIA)
                    "AU", "NZ" -> listOf(OCEANIA, NORTH_AMERICA, EUROPE, ASIA)
                    "GB", "DE", "FR", "IT", "ES", "PT", "CH", "CZ", "IE", "NL", "BE", "AT", "HU", "SK" ->
                        listOf(EUROPE, NORTH_AMERICA, OCEANIA, ASIA)
                    "VN", "TH", "MY", "SG", "PH", "ID" -> listOf(ASIA, OCEANIA, EUROPE, NORTH_AMERICA)
                    "CL", "BR" -> listOf(SOUTH_AMERICA, NORTH_AMERICA, EUROPE, OCEANIA, ASIA)
                    else -> entries.toList()
                }
        }
    }

    /**
     * Geographic availability tier for a [RadioPreset]. Used by [recommended] to choose the
     * most-specific community-curated preset matching the user's [RegionSelection].
     */
    sealed class PresetAvailability {
        data class Continent(val region: RadioRegion) : PresetAvailability()
        data class Countries(val codes: Set<String>) : PresetAvailability() // ISO-3166 alpha-2
        data class SubRegions(val country: String, val areas: Set<String>) : PresetAvailability() // ISO 3166-2
        data class Counties(val country: String, val state: String, val keys: Set<String>) : PresetAvailability()
    }

    data class RadioPreset(
        val id: String,
        val name: String,
        val region: RadioRegion,
        val frequencyMHz: Double,
        val bandwidthKHz: Double,
        val spreadingFactor: UByte,
        val codingRate: UByte,
        /** Section header for repeat mode presets (e.g., "EU/Asia", "US/AU/NZ"). */
        val repeatSectionHeader: String? = null,
        internal val availability: PresetAvailability,
        /** Higher value = preferred within a geographic tier. Standard presets use 100; community-recommended favorites use 110. */
        val recommendationPriority: Int = 100,
        /** Bytes per hop hash (1, 2, or 3). `null` leaves the radio's current hash size unchanged. */
        val pathHashSize: Int? = null,
    ) {
        /** Frequency in kHz for protocol encoding. */
        val frequencyKHz: UInt get() = (frequencyMHz * 1000).roundToLong().toUInt()

        /** Bandwidth in Hz for protocol encoding. */
        val bandwidthHz: UInt get() = (bandwidthKHz * 1000).roundToLong().toUInt()

        /** Firmware `path_hash_mode` (size - 1), or `null` when [pathHashSize] is `null`. */
        val pathHashMode: UByte? get() = pathHashSize?.let { (it - 1).toUByte() }
    }

    val all: List<RadioPreset> = listOf(
        // Oceania
        RadioPreset(
            id = "au-915", name = "Australia", region = RadioRegion.OCEANIA,
            frequencyMHz = 915.800, bandwidthKHz = 250.0, spreadingFactor = 10u, codingRate = 5u,
            availability = PresetAvailability.Countries(setOf("AU")),
        ),
        RadioPreset(
            id = "au-narrow", name = "Australia (Narrow)", region = RadioRegion.OCEANIA,
            frequencyMHz = 916.575, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 8u,
            availability = PresetAvailability.Countries(setOf("AU")),
        ),
        RadioPreset(
            id = "au-mid", name = "Australia (Mid)", region = RadioRegion.OCEANIA,
            frequencyMHz = 915.075, bandwidthKHz = 125.0, spreadingFactor = 9u, codingRate = 5u,
            availability = PresetAvailability.Countries(setOf("AU")),
        ),
        RadioPreset(
            id = "au-sa-wa", name = "Australia: SA, WA", region = RadioRegion.OCEANIA,
            frequencyMHz = 923.125, bandwidthKHz = 62.5, spreadingFactor = 8u, codingRate = 8u,
            availability = PresetAvailability.SubRegions(country = "AU", areas = setOf("AU-SA", "AU-WA")),
        ),
        RadioPreset(
            id = "au-qld", name = "Australia: QLD", region = RadioRegion.OCEANIA,
            frequencyMHz = 923.125, bandwidthKHz = 62.5, spreadingFactor = 8u, codingRate = 5u,
            availability = PresetAvailability.SubRegions(country = "AU", areas = setOf("AU-QLD")),
        ),
        RadioPreset(
            id = "nz-lr", name = "New Zealand (Gisborne)", region = RadioRegion.OCEANIA,
            frequencyMHz = 917.375, bandwidthKHz = 250.0, spreadingFactor = 11u, codingRate = 5u,
            pathHashSize = 1,
            availability = PresetAvailability.Countries(setOf("NZ")),
        ),
        RadioPreset(
            id = "nz-narrow", name = "New Zealand (Narrow)", region = RadioRegion.OCEANIA,
            frequencyMHz = 917.375, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 5u,
            pathHashSize = 2,
            availability = PresetAvailability.Countries(setOf("NZ")),
        ),

        // Europe
        RadioPreset(
            id = "eu-narrow", name = "EU/UK (Narrow)", region = RadioRegion.EUROPE,
            frequencyMHz = 869.618, bandwidthKHz = 62.5, spreadingFactor = 8u, codingRate = 8u,
            availability = PresetAvailability.Continent(RadioRegion.EUROPE), recommendationPriority = 110,
        ),
        RadioPreset(
            id = "eu-lr", name = "EU/UK (Deprecated)", region = RadioRegion.EUROPE,
            frequencyMHz = 869.525, bandwidthKHz = 250.0, spreadingFactor = 11u, codingRate = 5u,
            availability = PresetAvailability.Continent(RadioRegion.EUROPE),
        ),
        RadioPreset(
            id = "cz-narrow", name = "Czech Republic (Narrow)", region = RadioRegion.EUROPE,
            frequencyMHz = 869.432, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 5u,
            availability = PresetAvailability.Countries(setOf("CZ")),
        ),
        RadioPreset(
            id = "eu-433-lr", name = "EU 433MHz (Long Range)", region = RadioRegion.EUROPE,
            frequencyMHz = 433.650, bandwidthKHz = 250.0, spreadingFactor = 11u, codingRate = 5u,
            availability = PresetAvailability.Continent(RadioRegion.EUROPE),
        ),
        RadioPreset(
            id = "eu-433-narrow", name = "EU 433MHz (Narrow)", region = RadioRegion.EUROPE,
            frequencyMHz = 433.650, bandwidthKHz = 62.5, spreadingFactor = 8u, codingRate = 8u,
            availability = PresetAvailability.Continent(RadioRegion.EUROPE),
        ),
        RadioPreset(
            id = "pt-433", name = "Portugal 433", region = RadioRegion.EUROPE,
            frequencyMHz = 433.375, bandwidthKHz = 62.5, spreadingFactor = 9u, codingRate = 6u,
            availability = PresetAvailability.Countries(setOf("PT")),
        ),
        RadioPreset(
            id = "pt-868", name = "Portugal 868", region = RadioRegion.EUROPE,
            frequencyMHz = 869.618, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 6u,
            availability = PresetAvailability.Countries(setOf("PT")), recommendationPriority = 110,
        ),
        RadioPreset(
            id = "ch", name = "Switzerland", region = RadioRegion.EUROPE,
            frequencyMHz = 869.618, bandwidthKHz = 62.5, spreadingFactor = 8u, codingRate = 8u,
            availability = PresetAvailability.Countries(setOf("CH")),
        ),
        RadioPreset(
            id = "hu", name = "Hungary", region = RadioRegion.EUROPE,
            frequencyMHz = 869.618, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 5u,
            pathHashSize = 2,
            availability = PresetAvailability.Countries(setOf("HU")),
        ),
        RadioPreset(
            id = "nl", name = "Netherlands", region = RadioRegion.EUROPE,
            frequencyMHz = 869.618, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 5u,
            availability = PresetAvailability.Countries(setOf("NL")), recommendationPriority = 110,
        ),
        RadioPreset(
            id = "nl-li", name = "Netherlands (Limburg)", region = RadioRegion.EUROPE,
            frequencyMHz = 869.618, bandwidthKHz = 62.5, spreadingFactor = 8u, codingRate = 8u,
            pathHashSize = 2,
            availability = PresetAvailability.Countries(setOf("NL")),
        ),
        RadioPreset(
            id = "sk", name = "Slovakia", region = RadioRegion.EUROPE,
            frequencyMHz = 869.618, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 5u,
            pathHashSize = 2,
            availability = PresetAvailability.Countries(setOf("SK")),
        ),

        // North America
        RadioPreset(
            id = "us-ca", name = "USA", region = RadioRegion.NORTH_AMERICA,
            frequencyMHz = 910.525, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 5u,
            availability = PresetAvailability.Countries(setOf("US")), recommendationPriority = 110,
        ),
        RadioPreset(
            id = "ca", name = "Canada", region = RadioRegion.NORTH_AMERICA,
            frequencyMHz = 910.525, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 5u,
            pathHashSize = 3,
            availability = PresetAvailability.Countries(setOf("CA")), recommendationPriority = 110,
        ),
        RadioPreset(
            id = "cr", name = "Costa Rica", region = RadioRegion.NORTH_AMERICA,
            frequencyMHz = 910.525, bandwidthKHz = 125.0, spreadingFactor = 11u, codingRate = 5u,
            availability = PresetAvailability.Countries(setOf("CR")),
        ),
        RadioPreset(
            id = "wcmesh", name = "WCMesh (SoCal)", region = RadioRegion.NORTH_AMERICA,
            frequencyMHz = 927.875, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 5u,
            pathHashSize = 3,
            availability = PresetAvailability.Counties(
                country = "US", state = "US-CA",
                keys = setOf(
                    "los angeles", "orange", "san diego", "riverside", "san bernardino",
                    "ventura", "imperial", "kern", "santa barbara", "san luis obispo",
                ),
            ),
        ),
        RadioPreset(
            id = "lvmesh", name = "LVMesh", region = RadioRegion.NORTH_AMERICA,
            frequencyMHz = 910.525, bandwidthKHz = 500.0, spreadingFactor = 10u, codingRate = 5u,
            pathHashSize = 2,
            availability = PresetAvailability.SubRegions(country = "US", areas = setOf("US-PA", "US-NJ")),
        ),

        // South America
        // Chile: community-standard settings from the MeshChile network (https://meshchile.cl).
        RadioPreset(
            id = "cl", name = "Chile", region = RadioRegion.SOUTH_AMERICA,
            frequencyMHz = 927.875, bandwidthKHz = 62.5, spreadingFactor = 8u, codingRate = 5u,
            availability = PresetAvailability.Countries(setOf("CL")),
        ),
        // Brazil
        RadioPreset(
            id = "br", name = "Brazil", region = RadioRegion.SOUTH_AMERICA,
            frequencyMHz = 923.125, bandwidthKHz = 62.5, spreadingFactor = 8u, codingRate = 8u,
            availability = PresetAvailability.Countries(setOf("BR")),
        ),

        // Asia
        RadioPreset(
            id = "vn-narrow", name = "Vietnam (Narrow)", region = RadioRegion.ASIA,
            frequencyMHz = 920.250, bandwidthKHz = 62.5, spreadingFactor = 8u, codingRate = 5u,
            availability = PresetAvailability.Countries(setOf("VN")), recommendationPriority = 110,
        ),
        RadioPreset(
            id = "vn", name = "Vietnam (Deprecated)", region = RadioRegion.ASIA,
            frequencyMHz = 920.250, bandwidthKHz = 250.0, spreadingFactor = 11u, codingRate = 5u,
            availability = PresetAvailability.Countries(setOf("VN")),
        ),

        // Meshcoretel MOW: standalone community network, selectable directly as its own
        // pseudo-country (RegionalAreas.MESHCORETEL_MOW_ID) in the region picker.
        RadioPreset(
            id = "meshcoretel-mow", name = "Meshcoretel MOW", region = RadioRegion.MESHCORETEL_MOW,
            frequencyMHz = 868.731018, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 7u,
            availability = PresetAvailability.Countries(setOf(RegionalAreas.MESHCORETEL_MOW_ID)),
            pathHashSize = 2,
        ),
    )

    /**
     * Repeat mode frequency presets with regional grouping. Frequencies must match the firmware's
     * allowed repeat set exactly. Enabling Repeat Mode applies only the frequency; the per-entry
     * bandwidth/SF/CR are inert (kept because [RadioPreset]'s fields are non-optional).
     */
    val repeatPresets: List<RadioPreset> = listOf(
        RadioPreset(
            id = "repeat-433", name = "433 MHz", region = RadioRegion.EUROPE,
            frequencyMHz = 433.000, bandwidthKHz = 62.5, spreadingFactor = 9u, codingRate = 8u,
            repeatSectionHeader = "EU/Asia", availability = PresetAvailability.Continent(RadioRegion.EUROPE),
        ),
        RadioPreset(
            id = "repeat-869", name = "869 MHz", region = RadioRegion.EUROPE,
            frequencyMHz = 869.495, bandwidthKHz = 62.5, spreadingFactor = 8u, codingRate = 8u,
            repeatSectionHeader = "EU", availability = PresetAvailability.Continent(RadioRegion.EUROPE),
        ),
        RadioPreset(
            id = "repeat-918", name = "918 MHz", region = RadioRegion.NORTH_AMERICA,
            frequencyMHz = 918.000, bandwidthKHz = 62.5, spreadingFactor = 7u, codingRate = 8u,
            repeatSectionHeader = "US/AU/NZ", availability = PresetAvailability.Continent(RadioRegion.NORTH_AMERICA),
        ),
    )

    /** Presets filtered and sorted by user's locale. */
    fun presetsForLocale(locale: Locale = Locale.getDefault()): List<RadioPreset> {
        val preferredRegions = RadioRegion.regionsForLocale(locale)
        return all.sortedWith(
            compareBy(
                { preferredRegions.indexOf(it.region).let { i -> if (i < 0) preferredRegions.size else i } },
                { it.name },
            ),
        )
    }

    /** Every catalog row whose RF tuple matches, in catalog order. More than one name can share a tuple. Ported from `matchingPresets`. */
    fun matchingPresets(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte): List<RadioPreset> {
        val freqMHz = frequencyKHz.toDouble() / 1000.0
        val bwKHz = bandwidthKHz.toDouble() / 1000.0
        return all.filter { preset ->
            abs(preset.frequencyMHz - freqMHz) < 0.1 &&
                abs(preset.bandwidthKHz - bwKHz) < 1.0 &&
                preset.spreadingFactor == spreadingFactor &&
                preset.codingRate == codingRate
        }
    }

    /**
     * Last-applied id if still RF-equal, then recommended-if-in-set, then a unique match; else
     * `null` (Custom). Ported from `resolvedPreset` (upstream `74ad7911`).
     */
    fun resolvedPreset(
        frequencyKHz: UInt,
        bandwidthKHz: UInt,
        spreadingFactor: UByte,
        codingRate: UByte,
        preferredID: String?,
        region: RegionSelection?,
    ): RadioPreset? {
        val matches = matchingPresets(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate)
        if (preferredID != null) matches.firstOrNull { it.id == preferredID }?.let { return it }
        if (region != null) {
            val recommendedID = recommended(region)?.id
            if (recommendedID != null) matches.firstOrNull { it.id == recommendedID }?.let { return it }
        }
        return matches.singleOrNull()
    }

    /** Unique RF match, or `null` when the tuple is unlabeled or collides. */
    fun matchingPreset(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte): RadioPreset? =
        resolvedPreset(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate, preferredID = null, region = null)

    /**
     * Finds the repeat preset for the device's current frequency. Repeat Mode only sets frequency,
     * so bandwidth/SF/CR are not part of the match.
     */
    fun matchingRepeatPreset(frequencyKHz: UInt): RadioPreset? = repeatPresets.firstOrNull { it.frequencyKHz == frequencyKHz }

    /**
     * The repeat preset nearest to a frequency by absolute kHz distance. Enabling Repeat Mode snaps
     * an off-band frequency to this preset, since the firmware accepts only exact repeat frequencies.
     */
    fun nearestRepeatPreset(frequencyKHz: UInt): RadioPreset? =
        repeatPresets.minByOrNull { abs(it.frequencyKHz.toLong() - frequencyKHz.toLong()) }

    /**
     * Stable recommendation order, computed once. [RadioPreset.recommendationPriority] is a
     * compile-time constant on each preset so the sort output never changes.
     */
    private val recommendationOrder: List<RadioPreset> = all.sortedWith(
        compareByDescending<RadioPreset> { it.recommendationPriority }.thenBy { it.id },
    )

    /**
     * Returns the most-specific community-curated preset for [region]. Tier 0 (county) -> Tier 1
     * (sub-region) -> Tier 2 (country) -> Tier 3 (continent). Returns null for regions not covered
     * by any tier (e.g. Bermuda).
     */
    fun recommended(region: RegionSelection): RadioPreset? {
        val stable = recommendationOrder

        // Tier 0: counties
        val adminCode = region.administrativeAreaCode
        val countyKey = region.countyKey
        if (adminCode != null && countyKey != null) {
            stable.firstOrNull { preset ->
                val availability = preset.availability
                availability is PresetAvailability.Counties &&
                    availability.country == region.countryCode &&
                    availability.state == adminCode &&
                    countyKey in availability.keys
            }?.let { return it }
        }

        // Tier 1: sub-regions
        if (adminCode != null) {
            stable.firstOrNull { preset ->
                val availability = preset.availability
                availability is PresetAvailability.SubRegions &&
                    availability.country == region.countryCode &&
                    adminCode in availability.areas
            }?.let { return it }
        }

        // Tier 2: countries
        stable.firstOrNull { preset ->
            val availability = preset.availability
            availability is PresetAvailability.Countries && region.countryCode in availability.codes
        }?.let { return it }

        // Tier 3: continent
        val continent = RegionalAreas.continents[region.countryCode] ?: return null
        return stable.firstOrNull { preset ->
            val availability = preset.availability
            availability is PresetAvailability.Continent && availability.region == continent
        }
    }

    /**
     * Returns the alternatives list for the region's country (or continent if no country-level
     * matches exist). The list always includes [PresetAvailability.Counties] and
     * [PresetAvailability.SubRegions] presets for the country regardless of the user's specific
     * county/state, so e.g. a Sacramento user can still pick `wcmesh` manually.
     */
    fun presets(region: RegionSelection): List<RadioPreset> {
        val countryAndBelow = all.filter { preset ->
            when (val availability = preset.availability) {
                is PresetAvailability.Counties -> availability.country == region.countryCode
                is PresetAvailability.SubRegions -> availability.country == region.countryCode
                is PresetAvailability.Countries -> region.countryCode in availability.codes
                is PresetAvailability.Continent -> false
            }
        }
        if (countryAndBelow.isNotEmpty()) return countryAndBelow
        val continent = RegionalAreas.continents[region.countryCode] ?: return emptyList()
        return all.filter { preset ->
            val availability = preset.availability
            availability is PresetAvailability.Continent && availability.region == continent
        }
    }

    /**
     * Whether [preset] should appear in a manual picker for [region]. County presets appear only
     * when [region] resolves to one of their counties; sub-region presets only when [region] matches
     * the preset's country and one of its areas. A null region hides both. Country and continent
     * presets are always selectable.
     */
    fun isSelectable(preset: RadioPreset, region: RegionSelection?): Boolean =
        when (val availability = preset.availability) {
            is PresetAvailability.Counties -> {
                region != null && region.countryCode == availability.country &&
                    region.administrativeAreaCode == availability.state &&
                    region.countyKey?.let { it in availability.keys } == true
            }
            is PresetAvailability.SubRegions -> {
                region != null && region.countryCode == availability.country &&
                    region.administrativeAreaCode?.let { it in availability.areas } == true
            }
            is PresetAvailability.Countries, is PresetAvailability.Continent -> true
        }

    /**
     * Presets shown in Settings -> Radio for [region], plus the radio's current preset when that id
     * isn't already in the regional list (traveler exception). Ported from `visiblePresets`.
     */
    fun visiblePresets(region: RegionSelection?, activeID: String?): List<RadioPreset> {
        val start = if (region != null) presets(region).ifEmpty { presetsForLocale() } else presetsForLocale()
        val result = start.filter { isSelectable(it, region) || it.id == activeID }.toMutableList()
        if (activeID != null && result.none { it.id == activeID }) {
            all.firstOrNull { it.id == activeID }?.let(result::add)
        }
        return result
    }
}
