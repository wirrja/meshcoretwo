// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.map.MapFilterState
import com.meshcoretwo.android.pathediting.NeighborNameResolver
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.Neighbour
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.rendering.NodeNameMatchKind
import com.meshcoretwo.services.rendering.SNRQuality
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun key(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

private val EXACT_PREFIX = key(0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6)
private val SECOND_EXACT_PREFIX = key(0xB1, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6)

private fun session(latitude: Double, longitude: Double, name: String = "Center") = RemoteNodeSessionDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    publicKey = key(0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5),
    name = name,
    role = RemoteNodeRole.REPEATER,
    latitude = latitude,
    longitude = longitude,
)

private fun contact(prefix: ByteArray, name: String, latitude: Double, longitude: Double, isFavorite: Boolean = false) = ContactDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    publicKey = prefix,
    name = name,
    typeRawValue = ContactType.REPEATER.value,
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

private fun discoveredNode(prefix: ByteArray, name: String, latitude: Double, longitude: Double) = DiscoveredNodeDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    publicKey = prefix,
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

private fun neighbor(prefix: ByteArray, snr: Double = -3.0) = Neighbour(publicKeyPrefix = prefix, secondsAgo = 0, snr = snr)

private fun build(
    session: RemoteNodeSessionDto,
    neighbors: List<Neighbour>,
    contacts: List<ContactDto> = emptyList(),
    discoveredNodes: List<DiscoveredNodeDto> = emptyList(),
    filter: MapFilterState = MapFilterState(),
    keyDisplayByteCount: Int = 2,
) = NeighborSnrMapBuilder.build(session, neighbors, contacts, discoveredNodes, userLocation = null, filter = filter, keyDisplayByteCount = keyDisplayByteCount)

class NeighborSnrMapBuilderTest {
    @Test
    fun `center located plus exact located neighbor draws pins, an SNR line, and a badge`() {
        val session = session(37.0, -122.0)
        val contact = contact(EXACT_PREFIX, "Ridge", 37.1, -122.1)
        val neighbor = neighbor(EXACT_PREFIX)

        val result = build(session, listOf(neighbor), contacts = listOf(contact))

        assertEquals(1, result.points.count { it.role == SnrMapPointRole.CENTER })
        assertEquals(1, result.points.count { it.role == SnrMapPointRole.NEIGHBOR })
        assertEquals(1, result.lines.size)
        assertEquals(1, result.badges.size)
        assertTrue(result.unplottable.isEmpty())

        val line = result.lines.first()
        assertEquals(SNRQuality.of(neighbor.snr), line.quality)
        assertEquals(session.latitude, line.fromLatitude, 1e-9)
        assertEquals(session.longitude, line.fromLongitude, 1e-9)
        assertEquals(contact.latitude, line.toLatitude, 1e-9)
        assertEquals(contact.longitude, line.toLongitude, 1e-9)
    }

    @Test
    fun `center plus two located neighbors draws a center pin, two repeater pins, two badges, and two lines`() {
        val session = session(37.0, -122.0)
        val contacts = listOf(
            contact(EXACT_PREFIX, "Ridge", 37.1, -122.1),
            contact(SECOND_EXACT_PREFIX, "Valley", 37.2, -121.9),
        )
        val neighbors = listOf(neighbor(EXACT_PREFIX), neighbor(SECOND_EXACT_PREFIX))

        val result = build(session, neighbors, contacts = contacts)

        assertEquals(1, result.points.count { it.role == SnrMapPointRole.CENTER })
        assertEquals(2, result.points.count { it.role == SnrMapPointRole.NEIGHBOR })
        assertEquals(2, result.badges.size)
        assertEquals(2, result.lines.size)
        assertTrue(result.unplottable.isEmpty())
    }

    @Test
    fun `center not located draws neighbor pin without line or badge`() {
        val session = session(0.0, 0.0)
        val contact = contact(EXACT_PREFIX, "Ridge", 37.1, -122.1)
        val neighbor = neighbor(EXACT_PREFIX)

        val result = build(session, listOf(neighbor), contacts = listOf(contact))

        assertTrue(result.points.none { it.role == SnrMapPointRole.CENTER })
        assertEquals(1, result.points.count { it.role == SnrMapPointRole.NEIGHBOR })
        assertTrue(result.lines.isEmpty())
        assertTrue(result.badges.isEmpty())
        assertTrue(result.unplottable.isEmpty())
    }

    @Test
    fun `ambiguous fallback neighbor is not plotted and is listed as fallback`() {
        // A sub-6-byte prefix that collides across a contact and a discovered node forces the
        // resolver's refinement gate to return FALLBACK; production 6-byte prefixes never do.
        val session = session(0.0, 0.0)
        val contact = contact(key(0xAB, 0xCD), "Saved", 37.1, -122.1)
        val node = discoveredNode(key(0xAB, 0xEF), "Advert", 38.0, -123.0)
        val neighbor = neighbor(key(0xAB))

        val result = build(
            session,
            listOf(neighbor),
            contacts = listOf(contact),
            discoveredNodes = listOf(node),
            filter = MapFilterState(showDiscovered = true),
        )

        assertTrue(result.points.none { it.role == SnrMapPointRole.NEIGHBOR })
        assertTrue(result.lines.isEmpty())
        assertEquals(1, result.unplottable.size)
        assertEquals(NodeNameMatchKind.FALLBACK, result.unplottable.first().matchKind)
    }

    @Test
    fun `exact neighbor without a location is not plotted`() {
        val session = session(37.0, -122.0)
        val contact = contact(EXACT_PREFIX, "No GPS", 0.0, 0.0)
        val neighbor = neighbor(EXACT_PREFIX)

        val result = build(session, listOf(neighbor), contacts = listOf(contact))

        assertTrue(result.points.none { it.role == SnrMapPointRole.NEIGHBOR })
        assertEquals(1, result.unplottable.size)
        assertEquals(NodeNameMatchKind.EXACT, result.unplottable.first().matchKind)
        assertEquals("No GPS", result.unplottable.first().displayName)
    }

    @Test
    fun `exact neighbor with an out-of-range coordinate is rejected by the builder guard`() {
        // A DTO's `hasLocation` only checks non-(0,0), so an out-of-range latitude reaches the
        // builder; its own validity guard must reject it.
        val session = session(37.0, -122.0)
        val node = discoveredNode(EXACT_PREFIX, "Bad GPS", 200.0, -122.1)
        val neighbor = neighbor(EXACT_PREFIX)

        val result = build(session, listOf(neighbor), discoveredNodes = listOf(node), filter = MapFilterState(showDiscovered = true))

        assertTrue(result.points.none { it.role == SnrMapPointRole.NEIGHBOR })
        assertEquals(1, result.unplottable.size)
        assertEquals(NodeNameMatchKind.EXACT, result.unplottable.first().matchKind)
    }

    @Test
    fun `unresolved neighbor falls back to a hex name and is listed`() {
        val session = session(37.0, -122.0)
        val neighbor = neighbor(key(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01))
        val keyDisplayByteCount = 2

        val result = build(session, listOf(neighbor), keyDisplayByteCount = keyDisplayByteCount)

        assertTrue(result.points.none { it.role == SnrMapPointRole.NEIGHBOR })
        assertEquals(1, result.unplottable.size)
        assertEquals(NodeNameMatchKind.UNRESOLVED, result.unplottable.first().matchKind)
        assertEquals(NeighborNameResolver.fallbackName(neighbor.publicKeyPrefix, keyDisplayByteCount), result.unplottable.first().displayName)
    }

    @Test
    fun `colliding unresolved titles widen to the full prefix`() {
        val session = session(37.0, -122.0)
        val first = neighbor(key(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01))
        val second = neighbor(key(0xDE, 0xAD, 0x01, 0x02, 0x03, 0x04))
        val distinct = neighbor(key(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF))

        val result = build(session, listOf(first, second, distinct), keyDisplayByteCount = 2)

        assertEquals(listOf("DEADBEEF0001", "DEAD01020304", "AABB"), result.unplottable.map { it.displayName })
    }

    @Test
    fun `all neighbors unplottable keeps only the center pin and lists them all`() {
        val session = session(37.0, -122.0)
        val neighbors = listOf(
            neighbor(key(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01)),
            neighbor(key(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x02)),
        )

        val result = build(session, neighbors)

        assertEquals(1, result.points.size)
        assertEquals(SnrMapPointRole.CENTER, result.points.first().role)
        assertTrue(result.lines.isEmpty())
        assertEquals(2, result.unplottable.size)
    }

    @Test
    fun `favorites only keeps center pin and drops non-favorite neighbor`() {
        val session = session(37.0, -122.0)
        val favorite = contact(EXACT_PREFIX, "Fav", 37.1, -122.1, isFavorite = true)
        val other = contact(SECOND_EXACT_PREFIX, "Other", 37.2, -121.9, isFavorite = false)
        val neighbors = listOf(neighbor(EXACT_PREFIX), neighbor(SECOND_EXACT_PREFIX))

        val result = build(session, neighbors, contacts = listOf(favorite, other), filter = MapFilterState(favoritesOnly = true))

        assertEquals(1, result.points.count { it.role == SnrMapPointRole.CENTER })
        assertEquals(1, result.points.count { it.role == SnrMapPointRole.NEIGHBOR })
        assertTrue(result.points.any { it.role == SnrMapPointRole.NEIGHBOR && it.label == "Fav" })
        assertEquals(1, result.unplottable.size)
        // Non-favorite is excluded from the contact pool, so resolution falls back to hex.
        assertTrue(result.unplottable.any { it.matchKind == NodeNameMatchKind.UNRESOLVED && it.neighbor.publicKeyPrefix.contentEquals(SECOND_EXACT_PREFIX) })
    }

    @Test
    fun `discovered off drops discovered-only exact neighbor into unplottable`() {
        val session = session(37.0, -122.0)
        val discovered = discoveredNode(EXACT_PREFIX, "Heard", 37.1, -122.1)
        val neighbor = neighbor(EXACT_PREFIX)

        val withDiscovered = build(session, listOf(neighbor), discoveredNodes = listOf(discovered), filter = MapFilterState(showDiscovered = true))
        assertEquals(1, withDiscovered.points.count { it.role == SnrMapPointRole.NEIGHBOR })

        val withoutDiscovered = build(session, listOf(neighbor), discoveredNodes = listOf(discovered), filter = MapFilterState(showDiscovered = false))
        assertEquals(0, withoutDiscovered.points.count { it.role == SnrMapPointRole.NEIGHBOR })
        assertEquals(1, withoutDiscovered.points.count { it.role == SnrMapPointRole.CENTER })
        assertEquals(1, withoutDiscovered.unplottable.size)
    }

    @Test
    fun `favorites with discovered true still drops discovered-only neighbor`() {
        val session = session(37.0, -122.0)
        val discovered = discoveredNode(EXACT_PREFIX, "Heard", 37.1, -122.1)
        val neighbor = neighbor(EXACT_PREFIX)

        val result = build(
            session,
            listOf(neighbor),
            discoveredNodes = listOf(discovered),
            filter = MapFilterState(favoritesOnly = true, showDiscovered = true),
        )

        assertEquals(1, result.points.count { it.role == SnrMapPointRole.CENTER })
        assertEquals(0, result.points.count { it.role == SnrMapPointRole.NEIGHBOR })
        assertEquals(1, result.unplottable.size)
    }

    // MARK: - SNR bucketing

    @Test
    fun `SNR buckets map to the expected quality at their boundaries`() {
        assertEquals(SNRQuality.EXCELLENT, SNRQuality.of(7.0))
        assertEquals(SNRQuality.EXCELLENT, SNRQuality.of(6.1))
        assertEquals(SNRQuality.GOOD, SNRQuality.of(3.0))
        assertEquals(SNRQuality.GOOD, SNRQuality.of(0.1))
        assertEquals(SNRQuality.FAIR, SNRQuality.of(0.0))
        assertEquals(SNRQuality.FAIR, SNRQuality.of(-3.0))
        assertEquals(SNRQuality.FAIR, SNRQuality.of(-5.9))
        assertEquals(SNRQuality.POOR, SNRQuality.of(-6.0))
        assertEquals(SNRQuality.POOR, SNRQuality.of(-10.0))
    }

    @Test
    fun `SNR badge midpoint stays between coordinates that straddle the antimeridian`() {
        val session = session(0.0, 179.0)
        val node = discoveredNode(EXACT_PREFIX, "East", 0.0, -179.0)
        val neighbor = neighbor(EXACT_PREFIX)

        val result = build(session, listOf(neighbor), discoveredNodes = listOf(node), filter = MapFilterState(showDiscovered = true))

        // The midpoint must land on the date line near +/-180, not on the opposite hemisphere near 0.
        val badge = result.badges.first()
        assertTrue(abs(abs(badge.longitude) - 180) < 0.0001)
    }

    @Test
    fun `resolveLocated returns null when nothing matches`() {
        assertNull(NeighborNameResolver.resolveLocated(key(0xCC), emptyList(), emptyList(), userLocation = null))
    }
}
