// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.services.persistence.ContactDto
import org.json.JSONObject

fun ContactDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putUuid("radioID", radioID)
    putHex("publicKey", publicKey)
    put("name", name)
    put("typeRawValue", typeRawValue.toInt())
    put("flags", flags.toInt())
    put("outPathLength", outPathLength.toInt())
    putHex("outPath", outPath)
    put("lastAdvertTimestamp", lastAdvertTimestamp.toLong())
    put("latitude", latitude)
    put("longitude", longitude)
    put("lastModified", lastModified.toLong())
    put("lastHeardTimestamp", lastHeardTimestamp.toLong())
    putStringOrNull("nickname", nickname)
    put("isBlocked", isBlocked)
    put("isMuted", isMuted)
    put("isFavorite", isFavorite)
    putInstantOrNull("lastMessageDate", lastMessageDate)
    put("unreadCount", unreadCount)
    put("unreadMentionCount", unreadMentionCount)
    putStringOrNull("ocvPreset", ocvPreset)
    putStringOrNull("customOCVArrayString", customOCVArrayString)
    putHexOrNull("avatarImageData", avatarImageData)
}

fun JSONObject.toContactDto(): ContactDto = ContactDto(
    id = getUuid("id"),
    radioID = getUuid("radioID"),
    publicKey = getHex("publicKey"),
    name = getString("name"),
    typeRawValue = getInt("typeRawValue").toUByte(),
    flags = getInt("flags").toUByte(),
    outPathLength = getInt("outPathLength").toUByte(),
    outPath = getHex("outPath"),
    lastAdvertTimestamp = getLong("lastAdvertTimestamp").toUInt(),
    latitude = getDouble("latitude"),
    longitude = getDouble("longitude"),
    lastModified = getLong("lastModified").toUInt(),
    lastHeardTimestamp = getLong("lastHeardTimestamp").toUInt(),
    nickname = getStringOrNull("nickname"),
    isBlocked = getBoolean("isBlocked"),
    isMuted = getBoolean("isMuted"),
    isFavorite = getBoolean("isFavorite"),
    lastMessageDate = getInstantOrNull("lastMessageDate"),
    unreadCount = getInt("unreadCount"),
    unreadMentionCount = getInt("unreadMentionCount"),
    ocvPreset = getStringOrNull("ocvPreset"),
    customOCVArrayString = getStringOrNull("customOCVArrayString"),
    avatarImageData = getHexOrNull("avatarImageData"),
)
