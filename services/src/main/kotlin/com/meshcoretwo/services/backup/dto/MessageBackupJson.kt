// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import org.json.JSONObject

fun MessageDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putUuid("radioID", radioID)
    putUuidOrNull("contactID", contactID)
    putUByteOrNull("channelIndex", channelIndex)
    put("text", text)
    put("timestamp", timestamp.toLong())
    putInstant("createdAt", createdAt)
    putInstant("sortDate", sortDate)
    put("direction", direction.rawValue)
    put("status", status.rawValue)
    put("textType", textType.value.toInt())
    putUIntOrNull("ackCode", ackCode)
    put("pathLength", pathLength.toInt())
    putDoubleOrNull("snr", snr)
    putHexOrNull("pathNodes", pathNodes)
    putHexOrNull("senderKeyPrefix", senderKeyPrefix)
    putStringOrNull("senderNodeName", senderNodeName)
    put("isRead", isRead)
    putUuidOrNull("replyToID", replyToID)
    putUIntOrNull("roundTripTime", roundTripTime)
    put("sendCount", sendCount)
    put("retryAttempt", retryAttempt)
    put("maxRetryAttempts", maxRetryAttempts)
    putStringOrNull("deduplicationKey", deduplicationKey)
    putStringOrNull("reactionSummary", reactionSummary)
    putUIntOrNull("senderTimestamp", senderTimestamp)
    putIntOrNull("routeType", routeType?.value?.toInt())
    put("heardRepeats", heardRepeats)
    put("containsSelfMention", containsSelfMention)
    put("mentionSeen", mentionSeen)
}

fun JSONObject.toMessageDto(): MessageDto = MessageDto(
    id = getUuid("id"),
    radioID = getUuid("radioID"),
    contactID = getUuidOrNull("contactID"),
    channelIndex = getUByteOrNull("channelIndex"),
    text = getString("text"),
    timestamp = getLong("timestamp").toUInt(),
    createdAt = getInstant("createdAt"),
    sortDate = getInstant("sortDate"),
    direction = MessageDirection.fromRawValue(getInt("direction")) ?: MessageDirection.INCOMING,
    status = MessageStatus.fromRawValue(getInt("status")) ?: MessageStatus.DELIVERED,
    textType = TextType.fromValue(getInt("textType").toUByte()) ?: TextType.PLAIN_TEXT,
    ackCode = getUIntOrNull("ackCode"),
    pathLength = getInt("pathLength").toUByte(),
    snr = getDoubleOrNull("snr"),
    pathNodes = getHexOrNull("pathNodes"),
    senderKeyPrefix = getHexOrNull("senderKeyPrefix"),
    senderNodeName = getStringOrNull("senderNodeName"),
    isRead = getBoolean("isRead"),
    replyToID = getUuidOrNull("replyToID"),
    roundTripTime = getUIntOrNull("roundTripTime"),
    sendCount = getInt("sendCount"),
    retryAttempt = getInt("retryAttempt"),
    maxRetryAttempts = getInt("maxRetryAttempts"),
    deduplicationKey = getStringOrNull("deduplicationKey"),
    reactionSummary = getStringOrNull("reactionSummary"),
    senderTimestamp = getUIntOrNull("senderTimestamp"),
    routeType = getIntOrNull("routeType")?.toUByte()?.let { RouteType.fromValue(it) },
    heardRepeats = getInt("heardRepeats"),
    containsSelfMention = getBoolean("containsSelfMention"),
    mentionSeen = getBoolean("mentionSeen"),
)
