// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.protocol.encodePathLen
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class DiscoveryFilteringTest {
    private fun node(
        name: String = "Node",
        typeRawValue: UByte = 0x01u, // ContactType.CHAT
        lastHeard: Instant = Instant.ofEpochSecond(1_000),
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        outPathLength: UByte = 0xFFu, // flood
        inboundHopCount: Int? = null,
        publicKey: ByteArray = ByteArray(32) { 0xAA.toByte() },
    ) = DiscoveredNodeDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = publicKey,
        name = name,
        typeRawValue = typeRawValue,
        lastHeard = lastHeard,
        lastAdvertTimestamp = lastHeard.epochSecond.toUInt(),
        latitude = latitude,
        longitude = longitude,
        outPathLength = outPathLength,
        outPath = ByteArray(0),
        inboundHopCount = inboundHopCount,
        inboundHopAdvertTimestamp = null,
    )

    @Test
    fun `ALL segment with blank search keeps every node`() {
        val nodes = listOf(node(name = "A", typeRawValue = 0x01u), node(name = "B", typeRawValue = 0x02u))
        val result = DiscoveryFiltering.visibleNodes(nodes, "", DiscoverSegment.ALL, NodeSortOrder.NAME, null)
        assertEquals(2, result.size)
    }

    @Test
    fun `REPEATERS segment keeps only repeater-typed nodes`() {
        val repeater = node(name = "Repeater", typeRawValue = 0x02u)
        val chat = node(name = "Chat", typeRawValue = 0x01u)
        val result = DiscoveryFiltering.visibleNodes(listOf(repeater, chat), "", DiscoverSegment.REPEATERS, NodeSortOrder.NAME, null)
        assertEquals(listOf(repeater), result)
    }

    @Test
    fun `a non-blank search matches by name substring, bypassing the segment`() {
        val target = node(name = "Weather Station", typeRawValue = 0x02u)
        val other = node(name = "Home Node", typeRawValue = 0x01u)
        val result = DiscoveryFiltering.visibleNodes(listOf(target, other), "weather", DiscoverSegment.CONTACTS, NodeSortOrder.NAME, null)
        assertEquals(listOf(target), result)
    }

    @Test
    fun `a non-blank search also matches by hex public-key prefix`() {
        val target = node(name = "Unnamed", publicKey = byteArrayOf(0xAB.toByte(), 0xCD.toByte()) + ByteArray(30))
        val other = node(name = "Other", publicKey = ByteArray(32) { 0x11 })
        val result = DiscoveryFiltering.visibleNodes(listOf(target, other), "abcd", DiscoverSegment.ALL, NodeSortOrder.NAME, null)
        assertEquals(listOf(target), result)
    }

    @Test
    fun `LAST_HEARD sorts most recent first`() {
        val older = node(name = "Older", lastHeard = Instant.ofEpochSecond(100))
        val newer = node(name = "Newer", lastHeard = Instant.ofEpochSecond(200))
        val result = DiscoveryFiltering.visibleNodes(listOf(older, newer), "", DiscoverSegment.ALL, NodeSortOrder.LAST_HEARD, null)
        assertEquals(listOf(newer, older), result)
    }

    @Test
    fun `NAME sorts case-insensitively`() {
        val b = node(name = "banana")
        val a = node(name = "Apple")
        val result = DiscoveryFiltering.visibleNodes(listOf(b, a), "", DiscoverSegment.ALL, NodeSortOrder.NAME, null)
        assertEquals(listOf(a, b), result)
    }

    @Test
    fun `DISTANCE with no user location falls back to name order`() {
        val b = node(name = "Zebra", latitude = 1.0, longitude = 1.0)
        val a = node(name = "Apple")
        val result = DiscoveryFiltering.visibleNodes(listOf(b, a), "", DiscoverSegment.ALL, NodeSortOrder.DISTANCE, null)
        assertEquals(listOf(a, b), result)
    }

    @Test
    fun `DISTANCE sorts nearer nodes first, unlocated nodes last`() {
        val userLocation = LocationFix(latitude = 0.0, longitude = 0.0)
        val far = node(name = "Far", latitude = 10.0, longitude = 10.0)
        val near = node(name = "Near", latitude = 0.01, longitude = 0.01)
        val unlocated = node(name = "Unlocated", latitude = 0.0, longitude = 0.0)
        val result = DiscoveryFiltering.visibleNodes(listOf(far, unlocated, near), "", DiscoverSegment.ALL, NodeSortOrder.DISTANCE, userLocation)
        assertEquals(listOf(near, far, unlocated), result)
    }

    @Test
    fun `HOPS sorts fewer hops first, nodes with no known hop count last`() {
        val threeHops = node(name = "ThreeHops", outPathLength = encodePathLen(1, 3))
        val direct = node(name = "Direct", outPathLength = encodePathLen(1, 0))
        val unknownHops = node(name = "Flood", outPathLength = 0xFFu, inboundHopCount = null)
        val result = DiscoveryFiltering.visibleNodes(listOf(threeHops, unknownHops, direct), "", DiscoverSegment.ALL, NodeSortOrder.HOPS, null)
        assertEquals(listOf(direct, threeHops, unknownHops), result)
    }

    @Test
    fun `HOPS uses the inbound-advert hop count for a flood-routed node`() {
        val floodTwoHops = node(name = "FloodHeard", outPathLength = 0xFFu, inboundHopCount = 2)
        val direct = node(name = "Direct", outPathLength = encodePathLen(1, 0))
        val result = DiscoveryFiltering.visibleNodes(listOf(floodTwoHops, direct), "", DiscoverSegment.ALL, NodeSortOrder.HOPS, null)
        assertEquals(listOf(direct, floodTwoHops), result)
    }
}
