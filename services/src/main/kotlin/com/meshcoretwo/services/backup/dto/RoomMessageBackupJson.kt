// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.RoomMessageDto
import org.json.JSONObject

fun RoomMessageDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putUuid("sessionID", sessionID)
    putHex("authorKeyPrefix", authorKeyPrefix)
    putStringOrNull("authorName", authorName)
    put("text", text)
    put("timestamp", timestamp.toLong())
    putInstant("createdAt", createdAt)
    put("isFromSelf", isFromSelf)
    put("status", status.rawValue)
    putUIntOrNull("ackCode", ackCode)
    putUIntOrNull("roundTripTime", roundTripTime)
    put("retryAttempt", retryAttempt)
    put("maxRetryAttempts", maxRetryAttempts)
    put("failureSeen", failureSeen)
}

fun JSONObject.toRoomMessageDto(): RoomMessageDto = RoomMessageDto(
    id = getUuid("id"),
    sessionID = getUuid("sessionID"),
    authorKeyPrefix = getHex("authorKeyPrefix"),
    authorName = getStringOrNull("authorName"),
    text = getString("text"),
    timestamp = getLong("timestamp").toUInt(),
    createdAt = getInstant("createdAt"),
    isFromSelf = getBoolean("isFromSelf"),
    status = MessageStatus.fromRawValue(getInt("status")) ?: MessageStatus.DELIVERED,
    ackCode = getUIntOrNull("ackCode"),
    roundTripTime = getUIntOrNull("roundTripTime"),
    retryAttempt = getInt("retryAttempt"),
    maxRetryAttempts = getInt("maxRetryAttempts"),
    failureSeen = getBoolean("failureSeen"),
)
