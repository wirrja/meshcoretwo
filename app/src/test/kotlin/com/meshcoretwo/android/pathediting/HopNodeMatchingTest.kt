// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.persistence.ContactDto
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun contact(name: String, publicKey: ByteArray, isFavorite: Boolean = false, type: ContactType = ContactType.REPEATER): ContactDto =
    ContactDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = publicKey,
        name = name,
        typeRawValue = type.value,
        flags = 0u,
        outPathLength = 0u,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0u,
        latitude = 0.0,
        longitude = 0.0,
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

class HopNodeMatchingTest {
    @Test
    fun `isHexQuery is true only for a non-empty all-hex string`() {
        assertTrue(HopNodeMatching.isHexQuery("aaBB01"))
        assertFalse(HopNodeMatching.isHexQuery(""))
        assertFalse(HopNodeMatching.isHexQuery("tower"))
    }

    @Test
    fun `empty query matches every node`() {
        val node = contact("Tower", byteArrayOf(0xAA.toByte()))
        assertTrue(HopNodeMatching.matches(node, ""))
    }

    @Test
    fun `matches by case-insensitive name substring`() {
        val node = contact("North Ridge Repeater", byteArrayOf(0xAA.toByte()))
        assertTrue(HopNodeMatching.matches(node, "ridge"))
        assertFalse(HopNodeMatching.matches(node, "south"))
    }

    @Test
    fun `matches by case-insensitive name substring with diacritics folded`() {
        val node = contact("Café Node", byteArrayOf(0xAA.toByte()))
        assertTrue(HopNodeMatching.matches(node, "cafe"))
    }

    @Test
    fun `a hex query also matches a public-key hex prefix`() {
        val node = contact("Unnamed", byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x01))
        assertTrue(HopNodeMatching.matches(node, "aabb"))
        assertFalse(HopNodeMatching.matches(node, "ccdd"))
    }

    @Test
    fun `a non-hex query never falls back to public-key matching`() {
        val node = contact("Zulu", byteArrayOf(0xAA.toByte()))
        assertFalse(HopNodeMatching.matches(node, "aa-zz"))
    }

    @Test
    fun `filtered preserves order and drops non-matches`() {
        val ridge = contact("Ridge", byteArrayOf(0x01))
        val alpha = contact("Alpha", byteArrayOf(0x02))
        val beta = contact("Beta", byteArrayOf(0x03))

        val result = HopNodeMatching.filtered(listOf(ridge, alpha, beta), "a")

        assertEquals(listOf(alpha, beta), result)
    }

    @Test
    fun `filtered with an empty query returns the input unchanged`() {
        val nodes = listOf(contact("Alpha", byteArrayOf(0x01)), contact("Beta", byteArrayOf(0x02)))
        assertEquals(nodes, HopNodeMatching.filtered(nodes, ""))
    }
}
