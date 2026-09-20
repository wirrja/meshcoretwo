// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

/** Outcome of a single hex code parsed from a bulk-add entry. Ported from `HopCodeStatus`. */
sealed class HopCodeStatus {
    /** Valid, resolves to a node, and fits within the hop cap. Carries the prebuilt hop so callers append without re-parsing or re-resolving. */
    data class WillAdd(val hop: PathHop) : HopCodeStatus()
    object AlreadyInPath : HopCodeStatus()

    /** Valid hex but no matching node. */
    object NotFound : HopCodeStatus()

    /** Wrong length or non-hex. */
    object InvalidFormat : HopCodeStatus()

    /** Valid and resolvable but past the hop cap. */
    object PathFull : HopCodeStatus()
}

/** One parsed code from a bulk-add entry, with its status. Ported from `HopCodeClassification`. */
data class HopCodeClassification(
    /** The uppercased code, unique after de-duplication; also the stable id. */
    val code: String,
    val status: HopCodeStatus,
) {
    /** True when tapping "Add" will append this code to the path. */
    val willBeAdded: Boolean get() = status is HopCodeStatus.WillAdd
}
