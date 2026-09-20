// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import com.meshcoretwo.services.persistence.ContactDto

/**
 * Data source for the shared Add-Hop picker (`app`, once ported). Lets the same picker drive both
 * the contact path editor (hop-capped, once ported from `PathManagementViewModel`) and the trace
 * path builder (uncapped) without depending on either concretely. Ported from `HopPickerSource`.
 *
 * Swift's version also declares `discoveredRepeaters: [DiscoveredNodeDTO]` — the "Discover" list
 * (nodes heard but not yet added as contacts) has no Android equivalent yet (see
 * `AdvertisementService`'s class doc), so implementers resolve against contacts alone for now.
 */
interface HopPickerSource {
    val availableRepeaters: List<ContactDto>
    val availableRooms: List<ContactDto>

    /** Recently added hop public keys, newest first. */
    val recentPublicKeys: List<ByteArray>

    /** Hops currently in the path being built. */
    val currentHopCount: Int

    /** Maximum hops the path can hold, or `null` when unlimited (trace). */
    val hopLimit: Int?

    /** Whether no further hops can be added. Defaults to `currentHopCount >= hopLimit`. */
    val isPathFull: Boolean get() = hopLimit?.let { currentHopCount >= it } ?: false

    /** Appends a single node to the path and records it as recent. */
    fun appendHop(node: ContactDto)

    /** Adds every resolvable code from a comma-separated bulk entry, honoring the hop cap. */
    fun addCodes(input: String): CodeInputResult

    /** Classifies a comma-separated bulk entry per code without mutating the path, for the bulk-add preview panel. */
    fun classifyCodes(input: String): List<HopCodeClassification>

    /** Adopts a hash size inferred from a bulk paste when the source supports a per-entry override (trace). Fixed-width sources ignore it. */
    fun adoptHashSize(forPastedCodes: String) {}
}
