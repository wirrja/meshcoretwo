// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

/**
 * Adopt rule for inbound advert hop counts, shared by [DiscoveredNodeStore] and its tests so the
 * policy has a single definition. Ported from `InboundHopAdoption.swift`'s free function.
 *
 * Returns the `(hopCount, advertTimestamp)` pair to store, or `null` to no-op.
 *
 * Rules:
 * - stored timestamp `null` -> adopt the incoming pair
 * - `incoming.timestamp > stored.timestamp` -> adopt (newer advert; hop may rise or fall)
 * - `incoming.timestamp == stored.timestamp && incoming.hops < stored.hops` -> adopt (closer copy of this broadcast)
 * - else -> no-op
 *
 * A `null` incoming timestamp is "no grouping signal": adopted only while the stored timestamp is
 * also `null`. Once a timestamp is stored, an ungrouped incoming read is a no-op, since its
 * ordering against the stored value is unknowable.
 *
 * Known limitation: a node that reboots and loses its RTC re-advertises with a lower timestamp,
 * freezing the stored count until the row ages out of the discovered-node cap. Reset detection is
 * out of scope (matches Swift).
 */
fun adoptInboundHop(
    storedHops: Int?,
    storedTimestamp: UInt?,
    incomingHops: Int,
    incomingTimestamp: UInt?,
): Pair<Int, UInt?>? {
    if (storedTimestamp == null) return incomingHops to incomingTimestamp
    if (incomingTimestamp == null) return null
    if (incomingTimestamp > storedTimestamp) return incomingHops to incomingTimestamp
    if (incomingTimestamp == storedTimestamp && incomingHops < (storedHops ?: Int.MAX_VALUE)) {
        return incomingHops to incomingTimestamp
    }
    return null
}
