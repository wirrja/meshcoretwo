// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import com.meshcoretwo.android.map.MapFilterState
import com.meshcoretwo.android.pathediting.PathHop
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.rendering.SNRQuality
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun key(first: Int) = ByteArray(32) { index -> if (index == 0) first.toByte() else (index + first).toByte() }

private fun contact(
    first: Int,
    name: String,
    latitude: Double,
    longitude: Double,
    isFavorite: Boolean = false,
    type: ContactType = ContactType.REPEATER,
) = ContactDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    publicKey = key(first),
    name = name,
    typeRawValue = type.value,
    flags = 0u,
    outPathLength = 0u,
    outPath = ByteArray(0),
    lastAdvertTimestamp = 100u,
    latitude = latitude,
    longitude = longitude,
    lastModified = 0u,
    lastHeardTimestamp = 0u,
    nickname = null,
    isBlocked = false,
    isMuted = false,
    isFavorite = isFavorite,
    lastMessageDate = null,
    unreadCount = 0,
    unreadMentionCount = 0,
    ocvPreset = null,
    customOCVArrayString = null,
    avatarImageData = null,
)

private fun discovered(first: Int, name: String, latitude: Double, longitude: Double, publicKey: ByteArray = key(first)) = DiscoveredNodeDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    publicKey = publicKey,
    name = name,
    typeRawValue = ContactType.REPEATER.value,
    lastHeard = Instant.EPOCH,
    lastAdvertTimestamp = 100u,
    latitude = latitude,
    longitude = longitude,
    outPathLength = 0u,
    outPath = ByteArray(0),
    inboundHopCount = null,
    inboundHopAdvertTimestamp = null,
)

private fun hop(node: com.meshcoretwo.services.RepeaterResolvable) =
    PathHop(hashBytes = node.publicKey.copyOf(1), publicKey = node.publicKey, resolvedName = node.resolvableName)

private fun traceHop(snr: Double, start: Boolean = false, end: Boolean = false) =
    TraceHop(hashBytes = null, resolvedName = null, snr = snr, isStartNode = start, isEndNode = end, latitude = null, longitude = null)

private val DISCOVERED_ON = MapFilterState(showDiscovered = true)

class TracePathMapBuilderTest {
    private val tower = contact(0x10, "Tower", 55.70, 37.50)
    private val ridge = contact(0x20, "Ridge", 55.80, 37.60, isFavorite = true)
    private val hill = contact(0x30, "Hill", 55.90, 37.70)
    private val noFix = contact(0x40, "Nowhere", 0.0, 0.0)
    private val wild = discovered(0x50, "Wild", 55.75, 37.55)

    private fun state(path: List<PathHop> = emptyList(), discoveredNodes: List<DiscoveredNodeDto> = listOf(wild), result: TraceResult? = null) =
        TracePathUiState(
            outboundPath = path,
            availableRepeaters = listOf(tower, ridge, hill, noFix),
            discoveredRepeaters = discoveredNodes,
            result = result,
        )

    @Test
    fun `plots located contacts and discovered repeaters, numbering path hops`() {
        val content = TracePathMapBuilder.build(state(listOf(hop(tower), hop(ridge))), null, DISCOVERED_ON)

        assertEquals(setOf("Tower", "Ridge", "Hill", "Wild"), content.pins.map { it.label }.toSet())
        val byLabel = content.pins.associateBy { it.label }
        assertEquals(1, byLabel.getValue("Tower").hopIndex)
        assertEquals(2, byLabel.getValue("Ridge").hopIndex)
        assertTrue(byLabel.getValue("Ridge").isLastHop)
        assertNull(byLabel.getValue("Hill").hopIndex)
    }

    @Test
    fun `path lines start at the user and skip unlocated hops`() {
        val user = LocationFix(55.60, 37.40)
        val content = TracePathMapBuilder.build(state(listOf(hop(tower), hop(noFix), hop(hill))), user, DISCOVERED_ON)

        assertEquals(listOf(0, 2), content.lines.map { it.pathIndex })
        assertEquals(55.60, content.lines[0].fromLatitude, 0.0)
        assertEquals(55.70, content.lines[1].fromLatitude, 0.0)
        assertEquals(55.90, content.lines[1].toLatitude, 0.0)
        assertTrue(content.lines.all { it.quality == null })
        assertEquals(listOf(55.60, 55.70, 55.90), content.pathCoordinates.map { it.latitude })
    }

    @Test
    fun `without a user location the first hop only anchors the path`() {
        val content = TracePathMapBuilder.build(state(listOf(hop(tower), hop(ridge))), null, DISCOVERED_ON)
        assertEquals(listOf(1), content.lines.map { it.pathIndex })
    }

    @Test
    fun `a successful result grades lines by hop SNR and adds badges`() {
        val result = TraceResult(
            hops = listOf(traceHop(0.0, start = true), traceHop(8.0), traceHop(-9.0), traceHop(-2.0, end = true)),
            durationMs = 500,
            success = true,
            errorMessage = null,
            tracedPathBytes = ByteArray(0),
            hashSize = 1,
        )
        val content = TracePathMapBuilder.build(state(listOf(hop(tower), hop(ridge)), result = result), LocationFix(55.60, 37.40), DISCOVERED_ON)

        assertEquals(listOf(SNRQuality.EXCELLENT, SNRQuality.POOR), content.lines.map { it.quality })
        assertEquals(2, content.badges.size)
        assertTrue(content.badges[1].text.endsWith("-9.0 dB"))
    }

    @Test
    fun `failed result leaves lines untraced`() {
        val failed = TraceResult(hops = emptyList(), durationMs = 0, success = false, errorMessage = "x", tracedPathBytes = ByteArray(0), hashSize = 1)
        val content = TracePathMapBuilder.build(state(listOf(hop(tower), hop(ridge)), result = failed), null, DISCOVERED_ON)
        assertTrue(content.lines.all { it.quality == null })
        assertTrue(content.badges.isEmpty())
    }

    @Test
    fun `favorites filter keeps favorites and path members only`() {
        val content = TracePathMapBuilder.build(state(listOf(hop(tower), hop(wild))), null, MapFilterState(favoritesOnly = true, showDiscovered = true))
        assertEquals(setOf("Tower", "Ridge", "Wild"), content.pins.map { it.label }.toSet())
    }

    @Test
    fun `discovered layer off still shows discovered path members`() {
        val other = discovered(0x60, "Other", 55.65, 37.45)
        val hidden = TracePathMapBuilder.build(state(listOf(hop(wild)), listOf(wild, other)), null, MapFilterState(showDiscovered = false))
        assertEquals(setOf("Tower", "Ridge", "Hill", "Wild"), hidden.pins.map { it.label }.toSet())
    }

    @Test
    fun `discovered repeaters already added as contacts are not plotted twice`() {
        val twin = discovered(0x10, "Tower (heard)", 55.70, 37.50)
        val content = TracePathMapBuilder.build(state(discoveredNodes = listOf(twin)), null, DISCOVERED_ON)
        assertEquals(1, content.pins.count { it.latitude == 55.70 })
    }

    @Test
    fun `full key match without location is not replaced by a prefix match`() {
        // Same first byte as Tower, so a prefix lookup would land on Tower.
        val keyed = contact(0x10, "Keyed", 0.0, 0.0).copy(publicKey = ByteArray(32) { 0x10 })
        val state = state(listOf(hop(keyed))).copy(availableRepeaters = listOf(tower, keyed))
        val content = TracePathMapBuilder.build(state, null, DISCOVERED_ON)
        assertTrue(content.pins.none { it.inPath })
        assertTrue(content.pathCoordinates.isEmpty())
    }

    @Test
    fun `taps add, remove the last hop, and reject middle hops`() {
        val content = TracePathMapBuilder.build(state(listOf(hop(tower), hop(ridge))), null, DISCOVERED_ON)
        val byLabel = content.pins.associateBy { it.label }

        assertSame(TracePathPinAction.RemoveLast, TracePathMapBuilder.tapAction(content, byLabel.getValue("Ridge").id))
        assertSame(TracePathPinAction.RejectMiddleHop, TracePathMapBuilder.tapAction(content, byLabel.getValue("Tower").id))
        assertEquals(TracePathPinAction.Add(wild), TracePathMapBuilder.tapAction(content, byLabel.getValue("Wild").id))
        assertNull(TracePathMapBuilder.tapAction(content, UUID.randomUUID()))
    }
}
