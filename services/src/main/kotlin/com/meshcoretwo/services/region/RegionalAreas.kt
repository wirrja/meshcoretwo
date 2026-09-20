// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

import java.util.Locale

/**
 * Geographic catalog used to translate reverse-geocoding results into the [RegionSelection] keys
 * consumed by [RadioPresets.recommended]. Ported from `RegionalAreas.swift`.
 *
 * Vocabulary note: elsewhere in this codebase "region" means a *firmware mesh region* (a named
 * flood-routing scope on a repeater). This file's "region" vocabulary refers to *user geographic
 * location* instead.
 *
 * Subdivision names are returned in English only (`englishSubdivisionFallbacks`) — unlike iOS,
 * which resolves `region.subdivision.*` against the host app's localized `Settings.strings`, this
 * module has no string-resource context to localize against. Full localization is a follow-up once
 * this catalog is surfaced through `app`'s resources.
 */
object RegionalAreas {
    data class Country(
        val id: String,
        val subdivisions: List<Subdivision>?,
        /**
         * Overrides Locale-derived naming for entries whose [id] isn't a real ISO 3166-1 alpha-2
         * code (e.g. a standalone community network's pseudo-country id), which
         * `Locale.Builder().setRegion(id)` would otherwise reject.
         */
        val displayNameOverride: String? = null,
    ) {
        fun localizedName(displayLocale: Locale = Locale.getDefault()): String =
            displayNameOverride ?: runCatching {
                Locale.Builder().setRegion(id).build().getDisplayCountry(displayLocale).ifEmpty { id }
            }.getOrDefault(id)
    }

    data class Subdivision(
        val id: String,
        /** Lowercased, diacritic-folded matchers from reverse-geocoding results. */
        val normalizedNames: Set<String>,
    )

    val usSubdivisions: List<Subdivision> = listOf(
        Subdivision(id = "US-CA", normalizedNames = setOf("california", "ca")),
    )

    val auSubdivisions: List<Subdivision> = listOf(
        Subdivision(id = "AU-QLD", normalizedNames = setOf("queensland", "qld")),
        Subdivision(id = "AU-SA", normalizedNames = setOf("south australia", "sa")),
        Subdivision(id = "AU-WA", normalizedNames = setOf("western australia", "wa")),
    )

    /**
     * ISO alpha-2 -> [RadioPresets.RadioRegion] mapping. Mexico (MX) and Africa are intentionally
     * absent — those countries fall through [RadioPresets.recommended] to the empty-region
     * fallback. South America is CL and BR; CR is North America (US-band).
     */
    val continents: Map<String, RadioPresets.RadioRegion> = buildMap {
        // North America
        put("US", RadioPresets.RadioRegion.NORTH_AMERICA)
        put("CA", RadioPresets.RadioRegion.NORTH_AMERICA)
        put("CR", RadioPresets.RadioRegion.NORTH_AMERICA)
        // South America
        put("CL", RadioPresets.RadioRegion.SOUTH_AMERICA)
        put("BR", RadioPresets.RadioRegion.SOUTH_AMERICA)
        // Europe
        put("GB", RadioPresets.RadioRegion.EUROPE)
        put("IE", RadioPresets.RadioRegion.EUROPE)
        put("DE", RadioPresets.RadioRegion.EUROPE)
        put("FR", RadioPresets.RadioRegion.EUROPE)
        put("IT", RadioPresets.RadioRegion.EUROPE)
        put("ES", RadioPresets.RadioRegion.EUROPE)
        put("PT", RadioPresets.RadioRegion.EUROPE)
        put("NL", RadioPresets.RadioRegion.EUROPE)
        put("BE", RadioPresets.RadioRegion.EUROPE)
        put("CH", RadioPresets.RadioRegion.EUROPE)
        put("AT", RadioPresets.RadioRegion.EUROPE)
        put("CZ", RadioPresets.RadioRegion.EUROPE)
        put("PL", RadioPresets.RadioRegion.EUROPE)
        put("DK", RadioPresets.RadioRegion.EUROPE)
        put("SE", RadioPresets.RadioRegion.EUROPE)
        put("NO", RadioPresets.RadioRegion.EUROPE)
        put("FI", RadioPresets.RadioRegion.EUROPE)
        put("GR", RadioPresets.RadioRegion.EUROPE)
        put("HU", RadioPresets.RadioRegion.EUROPE)
        put("SK", RadioPresets.RadioRegion.EUROPE)
        put("RO", RadioPresets.RadioRegion.EUROPE)
        // Oceania
        put("AU", RadioPresets.RadioRegion.OCEANIA)
        put("NZ", RadioPresets.RadioRegion.OCEANIA)
        // Asia
        put("VN", RadioPresets.RadioRegion.ASIA)
        put("TH", RadioPresets.RadioRegion.ASIA)
        put("MY", RadioPresets.RadioRegion.ASIA)
        put("SG", RadioPresets.RadioRegion.ASIA)
        put("PH", RadioPresets.RadioRegion.ASIA)
        put("ID", RadioPresets.RadioRegion.ASIA)
        put("JP", RadioPresets.RadioRegion.ASIA)
        put("KR", RadioPresets.RadioRegion.ASIA)
        // Standalone community network preset; tier 2 (Countries availability) matches it before
        // this continent tier is ever consulted, but every id in `countries` must have an entry
        // here too (see the `continents and countries cover the same set of country codes` test).
        put(MESHCORETEL_MOW_ID, RadioPresets.RadioRegion.MESHCORETEL_MOW)
    }

    val countries: List<Country> = listOf(
        Country(id = "US", subdivisions = usSubdivisions),
        Country(id = "CA", subdivisions = null),
        Country(id = "CR", subdivisions = null),
        Country(id = "CL", subdivisions = null),
        Country(id = "BR", subdivisions = null),
        Country(id = "AU", subdivisions = auSubdivisions),
        Country(id = "NZ", subdivisions = null),
        Country(id = "GB", subdivisions = null),
        Country(id = "IE", subdivisions = null),
        Country(id = "DE", subdivisions = null),
        Country(id = "FR", subdivisions = null),
        Country(id = "IT", subdivisions = null),
        Country(id = "ES", subdivisions = null),
        Country(id = "PT", subdivisions = null),
        Country(id = "NL", subdivisions = null),
        Country(id = "BE", subdivisions = null),
        Country(id = "CH", subdivisions = null),
        Country(id = "AT", subdivisions = null),
        Country(id = "CZ", subdivisions = null),
        Country(id = "PL", subdivisions = null),
        Country(id = "DK", subdivisions = null),
        Country(id = "SE", subdivisions = null),
        Country(id = "NO", subdivisions = null),
        Country(id = "FI", subdivisions = null),
        Country(id = "GR", subdivisions = null),
        Country(id = "HU", subdivisions = null),
        Country(id = "SK", subdivisions = null),
        Country(id = "RO", subdivisions = null),
        Country(id = "VN", subdivisions = null),
        Country(id = "TH", subdivisions = null),
        Country(id = "MY", subdivisions = null),
        Country(id = "SG", subdivisions = null),
        Country(id = "PH", subdivisions = null),
        Country(id = "ID", subdivisions = null),
        Country(id = "JP", subdivisions = null),
        Country(id = "KR", subdivisions = null),
        // Standalone community network preset, not a real country. See RadioPresets.all's
        // "meshcoretel-mow" entry, whose Countries availability matches this id directly.
        Country(id = MESHCORETEL_MOW_ID, subdivisions = null, displayNameOverride = "Meshcoretel MOW"),
    )

    /** Pseudo-country id for the standalone "Meshcoretel MOW" community network preset. */
    const val MESHCORETEL_MOW_ID: String = "meshcoretel-mow"

    /** [countries] sorted by localized display name, for a country-picker list. */
    fun countriesSortedByLocalizedName(displayLocale: Locale = Locale.getDefault()): List<Country> =
        countries.sortedBy { it.localizedName(displayLocale) }

    /**
     * Normalized US county names (lowercased, diacritic-folded, "county" suffix stripped), indexed
     * by ISO 3166-2 state code. Only states with county-scoped presets are filled.
     */
    val usCounties: Map<String, Set<String>> = mapOf(
        "US-CA" to setOf(
            "los angeles", "orange", "san diego", "riverside", "san bernardino",
            "ventura", "imperial", "kern", "santa barbara", "san luis obispo",
        ),
    )

    /** Returns the subdivisions catalog for an ISO alpha-2 country code, or empty when unknown/none. */
    fun subdivisions(country: String?): List<Subdivision> {
        val entry = countries.firstOrNull { it.id == country } ?: return emptyList()
        return entry.subdivisions ?: emptyList()
    }

    /**
     * Returns the ISO 3166-2 subdivision code matching a normalized administrative area name.
     * Matches against [Subdivision.normalizedNames], which contains both the English long form
     * (e.g. "california") and short codes (e.g. "ca") so a geocoder's administrative-area field
     * returning either form resolves correctly. Returns null on miss; recommendation falls back to
     * the country tier.
     */
    fun matchSubdivision(country: String, normalized: String?): String? {
        if (normalized == null) return null
        val subdivisions = countries.firstOrNull { it.id == country }?.subdivisions ?: return null
        return subdivisions.firstOrNull { normalized in it.normalizedNames }?.id
    }

    /**
     * Returns the normalized county key when (country, state, name) all match the catalog. US
     * counties have no ISO identifier — the normalized name *is* the key.
     */
    fun matchCounty(country: String, state: String?, normalized: String?): String? {
        if (country != "US" || state == null || normalized == null) return null
        val countiesForState = usCounties[state] ?: return null
        return normalized.takeIf { it in countiesForState }
    }

    /**
     * Returns a display name for Settings detail and Radio footer. Short form for unambiguous US
     * states; disambiguated "State, Country" for ambiguous regions.
     */
    fun displayName(region: RegionSelection, displayLocale: Locale = Locale.getDefault()): String {
        val countryName = countries.firstOrNull { it.id == region.countryCode }?.localizedName(displayLocale)
            ?: runCatching {
                Locale.Builder().setRegion(region.countryCode).build().getDisplayCountry(displayLocale)
            }.getOrNull()?.ifEmpty { region.countryCode } ?: region.countryCode
        val admin = region.administrativeAreaCode ?: return countryName
        val stateName = subdivisionDisplayName(admin) ?: admin
        if (region.countryCode == "US" || region.countryCode == "CA") return stateName
        return "$stateName, $countryName"
    }

    /** Returns the English display name for an ISO 3166-2 code (e.g. "US-CA" -> "California"). */
    fun subdivisionDisplayName(code: String): String? = englishSubdivisionFallbacks[code]

    private val englishSubdivisionFallbacks: Map<String, String> = mapOf(
        "US-CA" to "California",
        "AU-QLD" to "Queensland",
        "AU-SA" to "South Australia",
        "AU-WA" to "Western Australia",
    )
}
