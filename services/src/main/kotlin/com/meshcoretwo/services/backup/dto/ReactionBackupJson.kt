// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.services.persistence.ReactionDto
import org.json.JSONObject

fun ReactionDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putUuid("messageID", messageID)
    put("emoji", emoji)
    put("senderName", senderName)
    put("messageHash", messageHash)
    put("rawText", rawText)
    putInstant("receivedAt", receivedAt)
    putUByteOrNull("channelIndex", channelIndex)
    putUuidOrNull("contactID", contactID)
    putUuid("radioID", radioID)
}

fun JSONObject.toReactionDto(): ReactionDto = ReactionDto(
    id = getUuid("id"),
    messageID = getUuid("messageID"),
    emoji = getString("emoji"),
    senderName = getString("senderName"),
    messageHash = getString("messageHash"),
    rawText = getString("rawText"),
    receivedAt = getInstant("receivedAt"),
    channelIndex = getUByteOrNull("channelIndex"),
    contactID = getUuidOrNull("contactID"),
    radioID = getUuid("radioID"),
)
