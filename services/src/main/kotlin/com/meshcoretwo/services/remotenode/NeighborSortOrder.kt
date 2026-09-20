// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

/** Sort order options for neighbour queries. Ported from `NeighborSortOrder` (`RepeaterAdminService.swift`). */
enum class NeighborSortOrder(val rawValue: UByte) {
    NEWEST_FIRST(0u),
    OLDEST_FIRST(1u),
    STRONGEST_FIRST(2u),
    WEAKEST_FIRST(3u),
}
