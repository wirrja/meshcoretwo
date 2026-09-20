// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from `RegionDiscoveryServiceTests.swift`'s `buildRegionQueryTargets` coverage. */
class RegionDiscoveryServiceTest {
    private fun publicKey(byte: Int) = ByteArray(32) { byte.toByte() }

    private fun contactDto(publicKey: ByteArray, type: ContactType = ContactType.REPEATER) = ContactDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = publicKey,
        name = "Contact",
        typeRawValue = type.value,
        flags = 0u,
        outPathLength = 0xFFu,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0u,
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

    private fun discoveredNodeDto(publicKey: ByteArray, type: ContactType = ContactType.REPEATER) = DiscoveredNodeDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = publicKey,
        name = "Node",
        typeRawValue = type.value,
        lastHeard = Instant.EPOCH,
        lastAdvertTimestamp = 0u,
        latitude = 0.0,
        longitude = 0.0,
        outPathLength = 0xFFu,
        outPath = ByteArray(0),
        inboundHopCount = null,
        inboundHopAdvertTimestamp = null,
    )

    @Test
    fun `queries repeater contacts that responded`() {
        val key = publicKey(1)
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            responders = setOf(key.hexString),
            contacts = listOf(contactDto(key)),
            discoveredNodes = emptyList(),
            supportsAdHocRequest = false,
        )
        assertEquals(1, targets.size)
        assertEquals(key.hexString, targets[0].id)
    }

    @Test
    fun `excludes non-repeater contacts even when they responded`() {
        val key = publicKey(1)
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            responders = setOf(key.hexString),
            contacts = listOf(contactDto(key, type = ContactType.CHAT)),
            discoveredNodes = emptyList(),
            supportsAdHocRequest = true,
        )
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `excludes contacts that did not respond`() {
        val key = publicKey(1)
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            responders = setOf(publicKey(2).hexString),
            contacts = listOf(contactDto(key)),
            discoveredNodes = emptyList(),
            supportsAdHocRequest = false,
        )
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `falls back to discovered nodes only when ad-hoc requests are supported`() {
        val key = publicKey(1)
        val discoveredNodes = listOf(discoveredNodeDto(key))

        val withoutAdHoc = RegionDiscoveryService.buildRegionQueryTargets(
            responders = setOf(key.hexString),
            contacts = emptyList(),
            discoveredNodes = discoveredNodes,
            supportsAdHocRequest = false,
        )
        assertTrue(withoutAdHoc.isEmpty())

        val withAdHoc = RegionDiscoveryService.buildRegionQueryTargets(
            responders = setOf(key.hexString),
            contacts = emptyList(),
            discoveredNodes = discoveredNodes,
            supportsAdHocRequest = true,
        )
        assertEquals(1, withAdHoc.size)
        assertEquals(key.hexString, withAdHoc[0].id)
    }

    @Test
    fun `prefers the contact record over a discovered-node duplicate of the same key`() {
        val key = publicKey(1)
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            responders = setOf(key.hexString),
            contacts = listOf(contactDto(key)),
            discoveredNodes = listOf(discoveredNodeDto(key)),
            supportsAdHocRequest = true,
        )
        assertEquals(1, targets.size)
    }
}
