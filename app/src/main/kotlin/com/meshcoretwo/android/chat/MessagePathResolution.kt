// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.android.pathediting.NeighborNameResolver
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.rendering.NodeNameMatchKind
import com.meshcoretwo.services.rendering.NodeNameResolution

/**
 * Resolves the *originating sender* of [this] message (as opposed to an intermediate repeater
 * hop, see [NeighborNameResolver.resolvePath]) for [MessagePathList]. Ported from
 * `MessagePathViewModel.senderResolution(for:)`: a channel message's `senderNodeName` is already
 * an exact name, so only a DM's [MessageDto.senderKeyPrefix] needs resolving against [contacts].
 */
fun MessageDto.senderResolution(contacts: List<ContactDto>, unknownName: String): NodeNameResolution {
    if (isChannelMessage) {
        senderNodeName?.let { return NodeNameResolution(displayName = it, matchKind = NodeNameMatchKind.EXACT) }
    }
    senderKeyPrefix?.let { keyPrefix ->
        NeighborNameResolver.resolve(keyPrefix, contacts, discoveredNodes = emptyList(), userLocation = null)?.let { return it }
    }
    return NodeNameResolution(displayName = unknownName, matchKind = NodeNameMatchKind.UNRESOLVED)
}

/** [MessageDto.senderKeyPrefix]'s first byte as hex, for display next to the sender row. Ported from `senderNodeID(for:)`. */
fun MessageDto.senderNodeIDHex(): String? = senderKeyPrefix?.takeIf { it.isNotEmpty() }?.copyOfRange(0, 1)?.hexString?.uppercase()

/**
 * The exact-match contact behind this message's sender, if it has a location — the "A" pin for
 * [MessagePathMapBuilder]. Ported from `MessagePathViewModel.locatedSender(for:)`: unlike
 * [senderResolution], this only ever returns an *exact* identity match (a DM's full
 * [MessageDto.senderKeyPrefix], or, for a channel message, a uniquely-named
 * [SenderContactMatcher] hit) — a map pin has no way to show "possible match" uncertainty, so an
 * ambiguous or empty match is dropped rather than guessed at.
 */
fun MessageDto.locatedSender(contacts: List<ContactDto>): ContactDto? {
    val keyPrefix = senderKeyPrefix
    if (keyPrefix != null && keyPrefix.isNotEmpty()) {
        val sender = contacts.firstOrNull { it.publicKeyPrefix.contentEquals(keyPrefix) } ?: return null
        return sender.takeIf { it.hasLocation }
    }

    val name = senderNodeName
    if (!isChannelMessage || name.isNullOrEmpty()) return null
    val matches = SenderContactMatcher.filter(contacts, name)
    val sender = matches.singleOrNull() ?: return null
    return sender.takeIf { it.hasLocation }
}
