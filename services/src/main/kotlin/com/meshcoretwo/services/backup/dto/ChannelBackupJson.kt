// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.NotificationLevel
import org.json.JSONObject

fun ChannelDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putUuid("radioID", radioID)
    put("index", index.toInt())
    put("name", name)
    putHex("secret", secret)
    put("isEnabled", isEnabled)
    putInstantOrNull("lastMessageDate", lastMessageDate)
    put("unreadCount", unreadCount)
    put("unreadMentionCount", unreadMentionCount)
    put("notificationLevel", notificationLevel.rawValue)
    put("isFavorite", isFavorite)
    put("floodScopeModeRawValue", floodScopeModeRawValue)
    putStringOrNull("regionScope", regionScope)
}

fun JSONObject.toChannelDto(): ChannelDto = ChannelDto(
    id = getUuid("id"),
    radioID = getUuid("radioID"),
    index = getInt("index").toUByte(),
    name = getString("name"),
    secret = getHex("secret"),
    isEnabled = getBoolean("isEnabled"),
    lastMessageDate = getInstantOrNull("lastMessageDate"),
    unreadCount = getInt("unreadCount"),
    unreadMentionCount = getInt("unreadMentionCount"),
    notificationLevel = NotificationLevel.fromRawValue(getInt("notificationLevel")) ?: NotificationLevel.ALL,
    isFavorite = getBoolean("isFavorite"),
    floodScopeModeRawValue = getString("floodScopeModeRawValue"),
    regionScope = getStringOrNull("regionScope"),
)
