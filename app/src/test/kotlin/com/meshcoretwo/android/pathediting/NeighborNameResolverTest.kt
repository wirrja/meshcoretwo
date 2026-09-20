// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactPathHop
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.rendering.NodeNameMatchKind
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun key(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

private fun contact(name: String, publicKey: ByteArray, lastAdvertTimestamp: UInt = 0u) = ContactDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    publicKey = publicKey,
    name = name,
    typeRawValue = ContactType.REPEATER.value,
    flags = 0u,
    outPathLength = 0u,
    outPath = ByteArray(0),
    lastAdvertTimestamp = lastAdvertTimestamp,
    latitude = 0.0,
    longitude = 0.0,
    lastModified = 0u,
    lastHeardTimestamp = 0u,
    nickname = null,
    isBlocked = false,
    isMuted = false,
    isFavorite = false,
    lastMessageDate = null,
    unreadCount = 0,
    unreadMentionCount = 0,
    ocvPreset = null,
    customOCVArrayString = null,
    avatarImageData = null,
)

private fun discovered(name: String, publicKey: ByteArray, lastAdvertTimestamp: UInt = 0u) = DiscoveredNodeDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    publicKey = publicKey,
    name = name,
    typeRawValue = ContactType.REPEATER.value,
    lastHeard = Instant.EPOCH,
    lastAdvertTimestamp = lastAdvertTimestamp,
    latitude = 0.0,
    longitude = 0.0,
    outPathLength = 0u,
    outPath = ByteArray(0),
    inboundHopCount = null,
    inboundHopAdvertTimestamp = null,
)

class NeighborNameResolverTest {
    @Test
    fun `resolve prefers a contact match over a discovered-node match sharing the same prefix`() {
        val fromContact = contact("Tower", key(0xAA, 0x01))
        val fromDiscovered = discovered("Ghost Tower", key(0xAA, 0x01))

        val resolution = NeighborNameResolver.resolve(key(0xAA, 0x01), listOf(fromContact), listOf(fromDiscovered), userLocation = null)

        assertEquals("Tower", resolution?.displayName)
    }

    @Test
    fun `resolve falls back to discovered nodes when no contact matches`() {
        val fromDiscovered = discovered("Ridge Node", key(0xBB, 0x02))

        val resolution = NeighborNameResolver.resolve(key(0xBB, 0x02), contacts = emptyList(), listOf(fromDiscovered), userLocation = null)

        assertEquals("Ridge Node", resolution?.displayName)
    }

    @Test
    fun `resolve returns null when nothing matches`() {
        val resolution = NeighborNameResolver.resolve(key(0xCC), listOf(contact("Other", key(0xDD))), emptyList(), userLocation = null)
        assertNull(resolution)
    }

    @Test
    fun `resolve reports fallback when a short prefix collides across contacts and discovered nodes`() {
        val a = contact("Alpha", key(0xAA, 0x01), lastAdvertTimestamp = 2u)
        val b = discovered("Beta", key(0xAA, 0x02), lastAdvertTimestamp = 1u)

        val resolution = NeighborNameResolver.resolve(key(0xAA), listOf(a), listOf(b), userLocation = null)

        assertEquals(NodeNameMatchKind.FALLBACK, resolution?.matchKind)
    }

    @Test
    fun `resolve reports exact for a short prefix matching only one node across both sources`() {
        val a = contact("Alpha", key(0xAA, 0x01))

        val resolution = NeighborNameResolver.resolve(key(0xAA), listOf(a), emptyList(), userLocation = null)

        assertEquals(NodeNameMatchKind.EXACT, resolution?.matchKind)
    }

    @Test
    fun `resolveName disambiguates a short contact prefix by the resolver's recency policy`() {
        // Ported from `TelemetryHistoryOverviewViewModelTests`' resolveNeighborName case.
        val older = contact("A Older Repeater", key(0xAB, 0xCD, 0x01), lastAdvertTimestamp = 100u)
        val newer = contact("Z Newer Repeater", key(0xAB, 0xCD, 0x02), lastAdvertTimestamp = 200u)

        val name = NeighborNameResolver.resolveName(key(0xAB, 0xCD), listOf(older, newer), emptyList(), userLocation = null)

        assertEquals("Z Newer Repeater", name)
    }

    @Test
    fun `resolveName returns null when nothing matches`() {
        assertNull(NeighborNameResolver.resolveName(key(0xCC), listOf(contact("Other", key(0xDD))), emptyList(), userLocation = null))
    }

    @Test
    fun `fallbackName renders uppercase hex clamped to the maximum display width`() {
        val prefix = key(0x0A, 0xBC, 0xDE, 0xFF)
        assertEquals("0A", NeighborNameResolver.fallbackName(prefix, byteCount = 1))
        assertEquals("0ABCDE", NeighborNameResolver.fallbackName(prefix, byteCount = 10))
    }

    @Test
    fun `resolveLocated carries the resolved contact's coordinate`() {
        val located = contact("Tower", key(0xAA, 0x01)).copy(latitude = 37.5, longitude = -122.5)

        val resolved = NeighborNameResolver.resolveLocated(key(0xAA, 0x01), listOf(located), emptyList(), userLocation = null)

        assertEquals(37.5, resolved?.latitude)
        assertEquals(-122.5, resolved?.longitude)
    }

    @Test
    fun `resolveLocated reports a null coordinate for a node without a location`() {
        val unlocated = contact("Tower", key(0xAA, 0x01))

        val resolved = NeighborNameResolver.resolveLocated(key(0xAA, 0x01), listOf(unlocated), emptyList(), userLocation = null)

        assertNull(resolved?.latitude)
        assertNull(resolved?.longitude)
    }

    @Test
    fun `resolvePath resolves a hop against a matching repeater contact`() {
        val repeater = contact("Tower", key(0xAA, 0x01))
        val hops = listOf(ContactPathHop(data = key(0xAA, 0x01), hex = "AA01"))

        val resolved = NeighborNameResolver.resolvePath(hops, listOf(repeater), emptyList(), userLocation = null)

        assertEquals(1, resolved.size)
        assertEquals(0, resolved[0].id)
        assertEquals("AA01", resolved[0].hex)
        assertEquals("Tower", resolved[0].resolution.displayName)
    }

    @Test
    fun `resolvePath ignores a non-repeater contact sharing the hop's prefix`() {
        val chatContact = contact("Alice", key(0xAA, 0x01)).copy(typeRawValue = ContactType.CHAT.value)
        val hops = listOf(ContactPathHop(data = key(0xAA, 0x01), hex = "AA01"))

        val resolved = NeighborNameResolver.resolvePath(hops, listOf(chatContact), emptyList(), userLocation = null)

        assertEquals(NodeNameMatchKind.UNRESOLVED, resolved[0].resolution.matchKind)
        assertEquals(NeighborNameResolver.PATH_HOP_UNKNOWN, resolved[0].resolution.displayName)
    }

    @Test
    fun `resolvePath falls back to a placeholder when no repeater matches`() {
        val hops = listOf(ContactPathHop(data = key(0xFF, 0xEE), hex = "FFEE"))

        val resolved = NeighborNameResolver.resolvePath(hops, emptyList(), emptyList(), userLocation = null)

        assertEquals("<unknown>", resolved[0].resolution.displayName)
    }

    @Test
    fun `resolvePath preserves hop order and index across contacts and discovered repeaters`() {
        val a = contact("A", key(0x01))
        val b = discovered("B", key(0x02))
        val hops = listOf(
            ContactPathHop(data = key(0x01), hex = "01"),
            ContactPathHop(data = key(0x02), hex = "02"),
        )

        val resolved = NeighborNameResolver.resolvePath(hops, listOf(a), listOf(b), userLocation = null)

        assertEquals(listOf(0, 1), resolved.map { it.id })
        assertEquals(listOf("A", "B"), resolved.map { it.resolution.displayName })
    }
}
