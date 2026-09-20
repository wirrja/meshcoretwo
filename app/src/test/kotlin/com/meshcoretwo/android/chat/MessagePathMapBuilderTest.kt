// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactPathHop
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun key(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

private fun contact(
    prefix: ByteArray,
    name: String,
    latitude: Double,
    longitude: Double,
    type: ContactType = ContactType.REPEATER,
) = ContactDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    publicKey = prefix,
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
    isFavorite = false,
    lastMessageDate = null,
    unreadCount = 0,
    unreadMentionCount = 0,
    ocvPreset = null,
    customOCVArrayString = null,
    avatarImageData = null,
)

private fun device(latitude: Double, longitude: Double, nodeName: String = "My Node") = DeviceDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    publicKey = key(0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5),
    nodeName = nodeName,
    firmwareVersion = 10u,
    firmwareVersionString = "v1.16",
    manufacturerName = "Test",
    buildDate = "2026-01-01",
    maxContacts = 100u,
    maxChannels = 10u,
    frequency = 915_000u,
    bandwidth = 250_000u,
    spreadingFactor = 10u,
    codingRate = 5u,
    txPower = 20,
    maxTxPower = 20,
    latitude = latitude,
    longitude = longitude,
    blePin = 0u,
    lastConnected = Instant.EPOCH,
    lastContactSync = 0u,
    isActive = true,
    ocvPreset = null,
    customOCVArrayString = null,
)

private fun message(
    senderKeyPrefix: ByteArray? = null,
    senderNodeName: String? = null,
    channelIndex: UByte? = null,
    pathLength: UByte = 0u,
    pathNodes: ByteArray? = null,
) = MessageDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    contactID = if (channelIndex == null) UUID.randomUUID() else null,
    channelIndex = channelIndex,
    text = "hi",
    timestamp = 1_000u,
    createdAt = Instant.ofEpochSecond(1_000),
    sortDate = Instant.ofEpochSecond(1_000),
    direction = MessageDirection.INCOMING,
    status = MessageStatus.SENT,
    textType = TextType.PLAIN_TEXT,
    ackCode = null,
    pathLength = pathLength,
    snr = null,
    pathNodes = pathNodes,
    senderKeyPrefix = senderKeyPrefix,
    senderNodeName = senderNodeName,
    isRead = true,
    replyToID = null,
    roundTripTime = null,
    sendCount = 1,
    retryAttempt = 0,
    maxRetryAttempts = 3,
    deduplicationKey = null,
    reactionSummary = null,
    senderTimestamp = null,
    routeType = null,
    heardRepeats = 0,
)

private val SENDER_PREFIX = key(0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6)
private val HOP_PREFIX = key(0xB1)

private fun build(
    message: MessageDto,
    contacts: List<ContactDto> = emptyList(),
    selfDevice: DeviceDto? = null,
    receiverName: String = "You",
) = MessagePathMapBuilder.build(message, contacts, discoveredNodes = emptyList(), selfDevice, receiverName, userLocation = null)

class MessagePathMapBuilderTest {
    @Test
    fun `sender, one exact hop, and a located device draw three points and a line`() {
        val sender = contact(SENDER_PREFIX, "Alice", 37.0, -122.0, type = ContactType.CHAT)
        val repeater = contact(HOP_PREFIX, "Ridge", 37.1, -122.1)
        val selfDevice = device(37.2, -122.2)
        val msg = message(senderKeyPrefix = SENDER_PREFIX, pathLength = 1u, pathNodes = HOP_PREFIX)

        val result = build(msg, contacts = listOf(sender, repeater), selfDevice = selfDevice)

        assertEquals(3, result.points.size)
        assertEquals(MessagePathMapPointRole.SENDER, result.points[0].role)
        assertEquals(MessagePathMapPointRole.HOP, result.points[1].role)
        assertEquals(MessagePathMapPointRole.RECEIVER, result.points[2].role)
        assertEquals(3, result.lineCoordinates.size)
        assertTrue((result.totalDistanceMeters ?: 0.0) > 0)
    }

    @Test
    fun `sender without a stored location is skipped`() {
        val sender = contact(SENDER_PREFIX, "Alice", 0.0, 0.0, type = ContactType.CHAT)
        val msg = message(senderKeyPrefix = SENDER_PREFIX)

        val result = build(msg, contacts = listOf(sender))

        assertTrue(result.points.none { it.role == MessagePathMapPointRole.SENDER })
    }

    @Test
    fun `unresolved sender key prefix plots no sender pin`() {
        val msg = message(senderKeyPrefix = SENDER_PREFIX)

        val result = build(msg, contacts = emptyList())

        assertTrue(result.points.isEmpty())
        assertTrue(result.lineCoordinates.isEmpty())
        assertNull(result.totalDistanceMeters)
    }

    @Test
    fun `a channel message resolves the sender by unique name match`() {
        val sender = contact(key(0xC1), "Bob", 37.0, -122.0, type = ContactType.CHAT)
        val msg = message(senderNodeName = "Bob", channelIndex = 0u)

        val result = build(msg, contacts = listOf(sender))

        val senderPoint = result.points.single { it.role == MessagePathMapPointRole.SENDER }
        assertEquals("Bob", senderPoint.label)
    }

    @Test
    fun `an ambiguous channel sender name plots no sender pin`() {
        val first = contact(key(0xC1), "Bob", 37.0, -122.0, type = ContactType.CHAT)
        val second = contact(key(0xC2), "Bob", 37.1, -122.1, type = ContactType.CHAT)
        val msg = message(senderNodeName = "Bob", channelIndex = 0u)

        val result = build(msg, contacts = listOf(first, second))

        assertTrue(result.points.none { it.role == MessagePathMapPointRole.SENDER })
    }

    @Test
    fun `a fallback-only hop match is not plotted`() {
        // A single-byte prefix colliding across two repeaters forces a FALLBACK match, which this
        // builder drops rather than guess at — same reasoning as NeighborSnrMapBuilder's exact-only gate.
        val repeaterA = contact(key(0xB1, 0x01), "Ridge", 37.1, -122.1)
        val repeaterB = contact(key(0xB1, 0x02), "Valley", 37.2, -121.9)
        val msg = message(pathLength = 1u, pathNodes = HOP_PREFIX)

        val result = build(msg, contacts = listOf(repeaterA, repeaterB))

        assertTrue(result.points.none { it.role == MessagePathMapPointRole.HOP })
    }

    @Test
    fun `two hops resolving to the same node are only plotted once`() {
        val repeater = contact(HOP_PREFIX, "Ridge", 37.1, -122.1)
        // pathLength 0x02 = mode 0 (hashSize 1) with hopCount 2, both hops sharing the same hash byte.
        val msg = message(pathLength = 2u, pathNodes = HOP_PREFIX + HOP_PREFIX)

        val result = build(msg, contacts = listOf(repeater))

        assertEquals(1, result.points.count { it.role == MessagePathMapPointRole.HOP })
    }

    @Test
    fun `receiver falls back to the best-available location when the device has none`() {
        val selfDevice = device(0.0, 0.0)
        val msg = message()

        val result = build(msg, selfDevice = selfDevice, receiverName = "You")

        assertTrue(result.points.none { it.role == MessagePathMapPointRole.RECEIVER })
    }

    @Test
    fun `a single located point yields no line or distance`() {
        val selfDevice = device(37.2, -122.2)
        val msg = message()

        val result = build(msg, selfDevice = selfDevice)

        assertEquals(1, result.points.size)
        assertTrue(result.lineCoordinates.isEmpty())
        assertNull(result.totalDistanceMeters)
    }

    @Test
    fun `endpoint pins alone do not make a map when the path has hops`() {
        val sender = contact(SENDER_PREFIX, "Alice", 37.0, -122.0, type = ContactType.CHAT)
        val msg = message(senderKeyPrefix = SENDER_PREFIX, pathLength = 1u, pathNodes = HOP_PREFIX)

        val result = build(msg, contacts = listOf(sender), selfDevice = device(37.2, -122.2))

        assertEquals(1, result.hopCount)
        assertTrue(result.isDistanceIncomplete)
        assertTrue(!result.showsPathMap)
    }

    @Test
    fun `a lone placed hop with a skipped hop is not a path map`() {
        val repeater = contact(HOP_PREFIX, "Ridge", 37.1, -122.1)
        val msg = message(pathLength = 2u, pathNodes = key(0xB1, 0xC9))

        val result = build(msg, contacts = listOf(repeater), selfDevice = device(37.2, -122.2))

        assertEquals(1, result.points.count { it.role == MessagePathMapPointRole.HOP })
        assertTrue(result.isDistanceIncomplete)
        assertTrue(!result.showsPathMap)
    }

    @Test
    fun `two placed hops keep the map even when a third was skipped`() {
        val a = contact(key(0xB1), "Ridge", 37.1, -122.1)
        val b = contact(key(0xB2), "Valley", 37.2, -122.0)
        val msg = message(pathLength = 3u, pathNodes = key(0xB1, 0xB2, 0xC9))

        val result = build(msg, contacts = listOf(a, b))

        assertTrue(result.isDistanceIncomplete)
        assertTrue(result.showsPathMap)
    }

    @Test
    fun `a fully placed single hop shows the map`() {
        val repeater = contact(HOP_PREFIX, "Ridge", 37.1, -122.1)
        val msg = message(pathLength = 1u, pathNodes = HOP_PREFIX)

        val result = build(msg, contacts = listOf(repeater))

        assertTrue(!result.isDistanceIncomplete)
        assertTrue(result.showsPathMap)
    }

    @Test
    fun `a zero-hop message shows the map when any endpoint is located`() {
        val sender = contact(SENDER_PREFIX, "Alice", 37.0, -122.0, type = ContactType.CHAT)
        val msg = message(senderKeyPrefix = SENDER_PREFIX)

        assertTrue(build(msg, contacts = listOf(sender)).showsPathMap)
        assertTrue(!build(msg, contacts = emptyList()).showsPathMap)
    }

    @Test
    fun `an extra arrival plots its own hops between the same endpoints`() {
        val sender = contact(SENDER_PREFIX, "Alice", 37.0, -122.0, type = ContactType.CHAT)
        val ownHop = contact(HOP_PREFIX, "Ridge", 37.1, -122.1)
        val otherHopPrefix = key(0xC7)
        val otherHop = contact(otherHopPrefix, "Valley", 37.4, -122.4)
        val selfDevice = device(37.2, -122.2)
        val msg = message(senderKeyPrefix = SENDER_PREFIX, pathLength = 1u, pathNodes = HOP_PREFIX)

        val result = MessagePathMapBuilder.build(
            msg, listOf(sender, ownHop, otherHop), discoveredNodes = emptyList(), selfDevice = selfDevice,
            receiverName = "You", userLocation = null,
            hops = listOf(ContactPathHop(data = otherHopPrefix, hex = "C7")),
        )

        val hop = result.points.single { it.role == MessagePathMapPointRole.HOP }
        assertEquals("Hop 1: Valley", hop.label)
        assertEquals(1, result.hopCount)
        // Endpoints are the message's, only the middle differs.
        assertEquals(3, result.points.size)
    }
}
