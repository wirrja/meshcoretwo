// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

/**
 * User's geographic region, used to recommend community-curated radio presets. Distinct from the
 * firmware-mesh-region concept elsewhere in this codebase (channel/repeater flood-routing scope).
 * Ported from `RegionSelection.swift`.
 */
data class RegionSelection(
    /** ISO-3166 alpha-2 (e.g. "US"). */
    val countryCode: String,
    /** ISO 3166-2 (e.g. "US-CA"). */
    val administrativeAreaCode: String? = null,
    /** Normalized US county key (e.g. "los angeles"). */
    val countyKey: String? = null,
    val source: Source,
) {
    enum class Source {
        LOCATION,
        MANUAL,
    }

    companion object {
        /** `null` means do not write: the tapped country is already selected. Ported from `RegionSelection.afterChoosingCountry`. */
        fun afterChoosingCountry(newCountry: String, current: RegionSelection?): RegionSelection? =
            if (current?.countryCode == newCountry) null else RegionSelection(countryCode = newCountry, source = Source.MANUAL)

        /** `null` means do not write: the tapped subdivision is already selected. Ported from `RegionSelection.afterChoosingSubdivision`. */
        fun afterChoosingSubdivision(newSubdivision: String, current: RegionSelection?): RegionSelection? {
            if (current == null || current.administrativeAreaCode == newSubdivision) return null
            return RegionSelection(countryCode = current.countryCode, administrativeAreaCode = newSubdivision, source = Source.MANUAL)
        }
    }
}
