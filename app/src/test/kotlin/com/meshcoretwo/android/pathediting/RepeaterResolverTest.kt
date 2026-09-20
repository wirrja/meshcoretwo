// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import com.meshcoretwo.services.RepeaterResolvable
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.rendering.NodeNameMatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

private data class FakeNode(
    override val publicKey: ByteArray,
    override val latitude: Double = 0.0,
    override val longitude: Double = 0.0,
    override val hasLocation: Boolean = false,
    override val lastAdvertTimestamp: UInt = 0u,
    override val recencyValue: UInt = 0u,
    override val resolvableName: String = "Node",
) : RepeaterResolvable {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

class RepeaterResolverTest {
    private fun key(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

    @Test
    fun `bestMatch by hash bytes finds the single node whose public key starts with the prefix`() {
        val target = FakeNode(publicKey = key(0xAA, 0xBB, 0x01))
        val other = FakeNode(publicKey = key(0xCC, 0xDD, 0x02))

        val match = RepeaterResolver.bestMatch(key(0xAA, 0xBB), listOf(target, other), userLocation = null)

        assertSame(target, match)
    }

    @Test
    fun `bestMatch returns null when no node's public key starts with the prefix`() {
        val node = FakeNode(publicKey = key(0xAA, 0xBB))
        assertNull(RepeaterResolver.bestMatch(key(0x11, 0x22), listOf(node), userLocation = null))
    }

    @Test
    fun `bestMatch returns null for an empty prefix`() {
        val node = FakeNode(publicKey = key(0xAA, 0xBB))
        assertNull(RepeaterResolver.bestMatch(ByteArray(0), listOf(node), userLocation = null))
    }

    @Test
    fun `bestMatch by PathHop with a known publicKey matches exactly, bypassing prefix ambiguity`() {
        val exactKey = key(0xAA, 0xBB, 0x01, 0x02)
        val exact = FakeNode(publicKey = exactKey)
        val ambiguousPrefixMatch = FakeNode(publicKey = key(0xAA, 0xBB, 0x99, 0x99))
        val hop = PathHop(hashBytes = key(0xAA, 0xBB), publicKey = exactKey)

        val match = RepeaterResolver.bestMatch(hop, listOf(ambiguousPrefixMatch, exact), userLocation = null)

        assertSame(exact, match)
    }

    @Test
    fun `bestMatch by PathHop with no publicKey falls back to hash-byte prefix matching`() {
        val target = FakeNode(publicKey = key(0xAA, 0xBB, 0x01))
        val hop = PathHop(hashBytes = key(0xAA, 0xBB))

        assertSame(target, RepeaterResolver.bestMatch(hop, listOf(target), userLocation = null))
    }

    @Test
    fun `among colliding prefixes, the node closer to userLocation wins regardless of recency`() {
        val near = FakeNode(publicKey = key(0xAA, 0x01), latitude = 1.0, longitude = 1.0, hasLocation = true, lastAdvertTimestamp = 1u)
        val far = FakeNode(publicKey = key(0xAA, 0x02), latitude = 10.0, longitude = 10.0, hasLocation = true, lastAdvertTimestamp = 100u)
        val userLocation = LocationFix(latitude = 1.0, longitude = 1.0)

        val match = RepeaterResolver.bestMatch(key(0xAA), listOf(far, near), userLocation)

        assertSame(near, match)
    }

    @Test
    fun `a node with a known distance always outranks one without, even if farther in absolute terms`() {
        val withDistance = FakeNode(publicKey = key(0xAA, 0x01), latitude = 5.0, longitude = 5.0, hasLocation = true, lastAdvertTimestamp = 1u)
        val withoutDistance = FakeNode(publicKey = key(0xAA, 0x02), hasLocation = false, lastAdvertTimestamp = 999u)
        val userLocation = LocationFix(latitude = 0.0, longitude = 0.0)

        val match = RepeaterResolver.bestMatch(key(0xAA), listOf(withoutDistance, withDistance), userLocation)

        assertSame(withDistance, match)
    }

    @Test
    fun `without location data, more recently advertised wins`() {
        val recent = FakeNode(publicKey = key(0xAA, 0x01), lastAdvertTimestamp = 200u)
        val stale = FakeNode(publicKey = key(0xAA, 0x02), lastAdvertTimestamp = 100u)

        assertSame(recent, RepeaterResolver.bestMatch(key(0xAA), listOf(stale, recent), userLocation = null))
    }

    @Test
    fun `equal advert timestamps fall back to recencyValue`() {
        val recent = FakeNode(publicKey = key(0xAA, 0x01), lastAdvertTimestamp = 1u, recencyValue = 200u)
        val stale = FakeNode(publicKey = key(0xAA, 0x02), lastAdvertTimestamp = 1u, recencyValue = 100u)

        assertSame(recent, RepeaterResolver.bestMatch(key(0xAA), listOf(stale, recent), userLocation = null))
    }

    @Test
    fun `equal timestamps fall back to alphabetical name order`() {
        val alpha = FakeNode(publicKey = key(0xAA, 0x01), resolvableName = "Alpha")
        val zulu = FakeNode(publicKey = key(0xAA, 0x02), resolvableName = "Zulu")

        assertEquals("Alpha", RepeaterResolver.bestMatch(key(0xAA), listOf(zulu, alpha), userLocation = null)?.resolvableName)
    }

    @Test
    fun `resolve reports exact for a single unambiguous candidate, even with a short prefix`() {
        val node = FakeNode(publicKey = key(0xAA, 0x01))
        val resolved = RepeaterResolver.resolve(key(0xAA), listOf(node), userLocation = null)
        assertEquals(NodeNameMatchKind.EXACT, resolved?.matchKind)
    }

    @Test
    fun `resolve reports fallback for a short prefix matching multiple candidates`() {
        val a = FakeNode(publicKey = key(0xAA, 0x01), lastAdvertTimestamp = 2u)
        val b = FakeNode(publicKey = key(0xAA, 0x02), lastAdvertTimestamp = 1u)
        val resolved = RepeaterResolver.resolve(key(0xAA), listOf(a, b), userLocation = null)
        assertSame(a, resolved?.node)
        assertEquals(NodeNameMatchKind.FALLBACK, resolved?.matchKind)
    }

    @Test
    fun `resolve reports exact once the prefix reaches EXACT_PREFIX_LENGTH, even amid collisions`() {
        val longPrefix = key(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF)
        assertEquals(RepeaterResolver.EXACT_PREFIX_LENGTH, longPrefix.size)
        val a = FakeNode(publicKey = longPrefix + key(0x01))
        val b = FakeNode(publicKey = longPrefix + key(0x02))
        val resolved = RepeaterResolver.resolve(longPrefix, listOf(a, b), userLocation = null)
        assertEquals(NodeNameMatchKind.EXACT, resolved?.matchKind)
    }
}
