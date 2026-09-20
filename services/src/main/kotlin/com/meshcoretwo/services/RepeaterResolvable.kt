// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services

/**
 * Shared interface for DTOs that can be matched by `RepeaterResolver` (`app`'s
 * `pathediting` package): [com.meshcoretwo.services.persistence.ContactDto] and
 * [com.meshcoretwo.services.persistence.DiscoveredNodeDto]. Ported from `RepeaterResolvable`,
 * trimmed to what a Room-backed row can actually supply — Swift's `recencyDate: Date` becomes
 * [recencyValue] here (each conformer's own recency field, truncated to epoch-second [UInt]).
 */
interface RepeaterResolvable {
    val publicKey: ByteArray
    val latitude: Double
    val longitude: Double
    val hasLocation: Boolean
    val lastAdvertTimestamp: UInt

    /** Secondary recency tiebreaker (`ContactDto.lastModified`). */
    val recencyValue: UInt

    /** Display name used for path hops and resolver tiebreaking. */
    val resolvableName: String
}
