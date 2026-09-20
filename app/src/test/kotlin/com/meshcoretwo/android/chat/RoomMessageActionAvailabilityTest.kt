// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RoomMessageDto
import com.meshcoretwo.services.persistence.RoomPermissionLevel
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

private fun roomMessage(isFromSelf: Boolean = false, authorName: String? = "Alice") = RoomMessageDto(
    sessionID = UUID.randomUUID(),
    authorKeyPrefix = ByteArray(4),
    authorName = authorName,
    text = "hi",
    timestamp = 1_000u,
    isFromSelf = isFromSelf,
)

private fun session(permissionLevel: RoomPermissionLevel = RoomPermissionLevel.READ_WRITE) = RemoteNodeSessionDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    publicKey = ByteArray(32),
    name = "Test Room",
    role = RemoteNodeRole.ROOM_SERVER,
    isConnected = true,
    permissionLevel = permissionLevel,
)

class RoomMessageActionAvailabilityTest {
    @Test
    fun `an incoming message with read-write permission allows reply and send-DM, not send-again`() {
        val availability = RoomMessageActionAvailability(roomMessage(isFromSelf = false), session())

        assertEquals(RoomMessageActionAvailability(canReply = true, canSendDM = true, canSendAgain = false), availability)
    }

    @Test
    fun `an outgoing message allows send-again only`() {
        val availability = RoomMessageActionAvailability(roomMessage(isFromSelf = true), session())

        assertEquals(RoomMessageActionAvailability(canReply = false, canSendDM = false, canSendAgain = true), availability)
    }

    @Test
    fun `a guest session (read-only) disallows reply even for an incoming message`() {
        val availability = RoomMessageActionAvailability(roomMessage(isFromSelf = false), session(RoomPermissionLevel.GUEST))

        assertEquals(false, availability.canReply)
    }

    @Test
    fun `an incoming message with no resolved author name disallows send-DM`() {
        val availability = RoomMessageActionAvailability(roomMessage(isFromSelf = false, authorName = null), session())

        assertEquals(false, availability.canSendDM)
    }
}
