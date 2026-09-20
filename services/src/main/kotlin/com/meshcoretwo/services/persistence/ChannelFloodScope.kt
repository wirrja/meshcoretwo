// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

/**
 * A channel's flood-scope preference. Ported from `ChannelFloodScope.swift`. Orthogonal to the
 * device-level default flood scope — [Inherit] means "apply the device default at send time";
 * [AllRegions] means "override the device default and broadcast to all regions"; [Region] is a
 * per-channel override to a specific named region.
 */
sealed class ChannelFloodScope {
    object Inherit : ChannelFloodScope()
    object AllRegions : ChannelFloodScope()
    data class Region(val name: String) : ChannelFloodScope()
}

/**
 * Bridges the two-column on-disk representation ([ChannelEntity.floodScopeModeRawValue] +
 * [ChannelEntity.regionScope]) and the public [ChannelFloodScope] enum. Ported from
 * `ChannelFloodScopeStorage` (`ChannelFloodScope.swift`). Invalid storage combinations are not
 * representable externally: if [Mode.SPECIFIC] is set but the region name is null/empty,
 * [recompose] yields [ChannelFloodScope.Inherit] defensively.
 */
internal object ChannelFloodScopeStorage {
    /** Raw values are pinned so a case rename can't silently change what's persisted to Room or matched elsewhere. */
    enum class Mode(val rawValue: String) {
        INHERIT("inherit"),
        ALL_REGIONS("allRegions"),
        SPECIFIC("specific"),
        ;

        companion object {
            fun fromRawValue(value: String): Mode? = entries.find { it.rawValue == value }
        }
    }

    fun decompose(scope: ChannelFloodScope): Pair<Mode, String?> = when (scope) {
        is ChannelFloodScope.Inherit -> Mode.INHERIT to null
        is ChannelFloodScope.AllRegions -> Mode.ALL_REGIONS to null
        is ChannelFloodScope.Region -> Mode.SPECIFIC to scope.name
    }

    fun recompose(modeRawValue: String, regionName: String?): ChannelFloodScope =
        when (Mode.fromRawValue(modeRawValue) ?: Mode.INHERIT) {
            Mode.INHERIT -> ChannelFloodScope.Inherit
            Mode.ALL_REGIONS -> ChannelFloodScope.AllRegions
            Mode.SPECIFIC -> if (regionName.isNullOrEmpty()) ChannelFloodScope.Inherit else ChannelFloodScope.Region(regionName)
        }
}
