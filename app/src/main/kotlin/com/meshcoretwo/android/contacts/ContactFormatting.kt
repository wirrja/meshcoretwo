// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.ContactDto
import java.time.Instant

/**
 * Route summary for a contact's stored outbound path — "Direct" (zero hops and not flood-routed),
 * "N hops" (an out-path, or — when flood-routed — a passively-heard [inboundHopCount] via
 * [displayedHopCount]), or "Flood" when neither is known. Ported from `ContactRowView.swift`'s
 * `routeLabel` computed property. [inboundHopCount] defaults to `null` for callers without a
 * Discover-list lookup handy (e.g. [com.meshcoretwo.android.contacts.ContactDetailScreen]'s Path
 * section) — same as passing no hop override on iOS.
 */
fun ContactDto.routeLabel(inboundHopCount: Int? = null): String {
    if (!isFloodRouted && pathHopCount <= 0) return "Direct"
    return displayedHopCount(inboundHopCount)?.let { "$it hop${if (it == 1) "" else "s"}" } ?: "Flood"
}

/** Uppercase hex of the contact's full public key, spaced every 4 characters for readability. */
fun ContactDto.publicKeyHex(): String = publicKey.hexString.uppercase().chunked(4).joinToString(" ")

/** Uppercase hex of just [ContactDto.publicKeyPrefix], for compact list rows. */
fun ContactDto.publicKeyPrefixHex(): String = publicKeyPrefix.hexString.uppercase()

/** [ContactDto.lastHeardTimestamp]/[ContactDto.lastAdvertTimestamp] are `0u` when never recorded. */
fun UInt.toInstantOrNull(): Instant? = if (this == 0u) null else Instant.ofEpochSecond(toLong())

/**
 * The hop count to surface in the UI: the deliberately-set out-path hops when a route exists,
 * otherwise [inboundHopCount] — the passively-heard advert hop count the caller looks up from the
 * Discover list ([com.meshcoretwo.services.persistence.DiscoveredNodeDto.inboundHopCount], keyed by
 * public key). `null` when flood-routed and neither is known. Ported from
 * `ContactDTO.displayedHopCount(inboundHopCount:)` — same shape as
 * [com.meshcoretwo.services.persistence.DiscoveredNodeDto.displayedHopCount], which already exists
 * for the Discover list's own row.
 */
fun ContactDto.displayedHopCount(inboundHopCount: Int?): Int? = if (isFloodRouted) inboundHopCount else pathHopCount
