// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.persistence.ContactDto
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun contact(name: String, isBlocked: Boolean = false): ContactDto =
    ContactDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = ByteArray(0),
        name = name,
        typeRawValue = ContactType.CHAT.value,
        flags = 0u,
        outPathLength = 0u,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0u,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0u,
        lastHeardTimestamp = 0u,
        nickname = null,
        isBlocked = isBlocked,
        isMuted = false,
        isFavorite = false,
        lastMessageDate = null,
        unreadCount = 0,
        unreadMentionCount = 0,
        ocvPreset = null,
        customOCVArrayString = null,
        avatarImageData = null,
    )

class SenderContactMatcherTest {
    @Test
    fun `matches contact name case-insensitively`() {
        val alice = contact(name = "Alice")
        val result = SenderContactMatcher.filter(listOf(alice, contact(name = "Bob")), senderName = "aLICE")

        assertEquals(listOf(alice), result)
    }

    @Test
    fun `non-matching names are excluded`() {
        val result = SenderContactMatcher.filter(listOf(contact(name = "Alice")), senderName = "Charlie")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `excludeBlocked false keeps blocked matches`() {
        val blocked = contact(name = "Alice", isBlocked = true)
        val result = SenderContactMatcher.filter(listOf(blocked), senderName = "Alice", excludeBlocked = false)

        assertEquals(listOf(blocked), result)
    }

    @Test
    fun `excludeBlocked true drops blocked matches`() {
        val result = SenderContactMatcher.filter(listOf(contact(name = "Alice", isBlocked = true)), senderName = "Alice", excludeBlocked = true)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `multiple contacts can share the same name`() {
        val first = contact(name = "Alice")
        val second = contact(name = "Alice")
        val result = SenderContactMatcher.filter(listOf(first, second, contact(name = "Bob")), senderName = "Alice")

        assertEquals(listOf(first, second), result)
    }
}
