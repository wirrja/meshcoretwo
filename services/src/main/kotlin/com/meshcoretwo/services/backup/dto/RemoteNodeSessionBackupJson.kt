// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RoomPermissionLevel
import org.json.JSONObject

fun RemoteNodeSessionDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putUuid("radioID", radioID)
    putHex("publicKey", publicKey)
    put("name", name)
    put("role", role.rawValue.toInt())
    put("latitude", latitude)
    put("longitude", longitude)
    put("isConnected", isConnected)
    put("permissionLevel", permissionLevel.rawValue.toInt())
    putInstantOrNull("lastConnectedDate", lastConnectedDate)
    putIntOrNull("lastBatteryMillivolts", lastBatteryMillivolts)
    putLongOrNull("lastUptimeSeconds", lastUptimeSeconds)
    putIntOrNull("lastNoiseFloor", lastNoiseFloor)
    put("unreadCount", unreadCount)
    put("notificationLevel", notificationLevel.rawValue)
    putLongOrNull("lastRxAirtimeSeconds", lastRxAirtimeSeconds)
    put("neighborCount", neighborCount)
    put("lastSyncTimestamp", lastSyncTimestamp)
    putInstantOrNull("lastMessageDate", lastMessageDate)
    put("isFavorite", isFavorite)
}

fun JSONObject.toRemoteNodeSessionDto(): RemoteNodeSessionDto = RemoteNodeSessionDto(
    id = getUuid("id"),
    radioID = getUuid("radioID"),
    publicKey = getHex("publicKey"),
    name = getString("name"),
    role = RemoteNodeRole.fromRawValue(getInt("role").toUByte()) ?: RemoteNodeRole.REPEATER,
    latitude = getDouble("latitude"),
    longitude = getDouble("longitude"),
    isConnected = getBoolean("isConnected"),
    permissionLevel = RoomPermissionLevel.fromRawValue(getInt("permissionLevel").toUByte()) ?: RoomPermissionLevel.GUEST,
    lastConnectedDate = getInstantOrNull("lastConnectedDate"),
    lastBatteryMillivolts = getIntOrNull("lastBatteryMillivolts"),
    lastUptimeSeconds = getLongOrNull("lastUptimeSeconds"),
    lastNoiseFloor = getIntOrNull("lastNoiseFloor"),
    unreadCount = getInt("unreadCount"),
    notificationLevel = NotificationLevel.fromRawValue(getInt("notificationLevel")) ?: NotificationLevel.ALL,
    lastRxAirtimeSeconds = getLongOrNull("lastRxAirtimeSeconds"),
    neighborCount = getInt("neighborCount"),
    lastSyncTimestamp = getLong("lastSyncTimestamp"),
    lastMessageDate = getInstantOrNull("lastMessageDate"),
    // Missing (rather than defaulted false-but-present) on a backup written before isFavorite
    // existed — no shipped app/backup format to preserve, but cheap to be defensive here anyway.
    isFavorite = getBooleanOrNull("isFavorite") ?: false,
)
