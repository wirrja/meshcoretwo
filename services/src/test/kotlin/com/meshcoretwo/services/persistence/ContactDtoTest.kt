// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.encodePathLen
import com.meshcoretwo.services.remotenode.OCVPreset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from `ContactOCVTests.swift`/`ContactDTOPathTests.swift`. */
class ContactDtoTest {
    private fun contactDto(
        ocvPreset: String? = null,
        customOCVArrayString: String? = null,
        outPathLength: UByte = 0xFFu,
        outPath: ByteArray = ByteArray(0),
    ) = ContactDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = ByteArray(32) { 0x42 },
        name = "Test",
        typeRawValue = ContactType.REPEATER.value,
        flags = 0u,
        outPathLength = outPathLength,
        outPath = outPath,
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
        ocvPreset = ocvPreset,
        customOCVArrayString = customOCVArrayString,
        avatarImageData = null,
    )

    @Test
    fun `activeOCVArray returns Li-Ion by default`() {
        val contact = contactDto(ocvPreset = null, customOCVArrayString = null)

        assertEquals(OCVPreset.LI_ION.ocvArray, contact.activeOCVArray)
    }

    @Test
    fun `activeOCVArray returns preset array when set`() {
        val contact = contactDto(ocvPreset = OCVPreset.LI_FE_PO4.rawValue, customOCVArrayString = null)

        assertEquals(OCVPreset.LI_FE_PO4.ocvArray, contact.activeOCVArray)
    }

    @Test
    fun `activeOCVArray returns custom array when valid`() {
        val customArray = listOf(4200, 4100, 4000, 3900, 3800, 3700, 3600, 3500, 3400, 3300, 3200)
        val customString = customArray.joinToString(",")
        val contact = contactDto(ocvPreset = OCVPreset.CUSTOM.rawValue, customOCVArrayString = customString)

        assertEquals(customArray, contact.activeOCVArray)
    }

    @Test
    fun `activeOCVArray falls back to Li-Ion for invalid custom array`() {
        val contact = contactDto(ocvPreset = OCVPreset.CUSTOM.rawValue, customOCVArrayString = "invalid")

        assertEquals(OCVPreset.LI_ION.ocvArray, contact.activeOCVArray)
    }

    @Test
    fun `pathHops chunk single-byte hops into one pair each`() {
        val contact = contactDto(
            outPathLength = encodePathLen(hashSize = 1, hopCount = 3),
            outPath = byteArrayOf(0x1A, 0x2B, 0x3C),
        )

        assertEquals(listOf("1A", "2B", "3C"), contact.pathNodesHex)
        assertTrue(contact.pathHops[0].data.contentEquals(byteArrayOf(0x1A)))
    }

    @Test
    fun `pathHops chunk multi-byte hops by the encoded hash size`() {
        val contact = contactDto(
            outPathLength = encodePathLen(hashSize = 2, hopCount = 2),
            outPath = byteArrayOf(0x1A, 0x2B, 0x3C, 0x4D),
        )

        assertEquals(listOf("1A2B", "3C4D"), contact.pathNodesHex)
    }

    @Test
    fun `pathHops ignore bytes beyond the encoded path length`() {
        val contact = contactDto(
            outPathLength = encodePathLen(hashSize = 1, hopCount = 2),
            outPath = byteArrayOf(0x1A, 0x2B, 0xFF.toByte(), 0xFF.toByte()),
        )

        assertEquals(listOf("1A", "2B"), contact.pathNodesHex)
    }

    @Test
    fun `a flood-routed contact has no hops`() {
        val contact = contactDto(outPathLength = PacketBuilder.FLOOD_PATH_SENTINEL, outPath = byteArrayOf(0x1A, 0x2B))

        assertTrue(contact.pathHops.isEmpty())
        assertTrue(contact.pathNodesHex.isEmpty())
    }

    @Test
    fun `pathString joins hex hops with an arrow separator`() {
        val contact = contactDto(
            outPathLength = encodePathLen(hashSize = 1, hopCount = 3),
            outPath = byteArrayOf(0xA3.toByte(), 0x7F, 0x42),
        )

        assertEquals("A3 → 7F → 42", contact.pathString)
    }
}
