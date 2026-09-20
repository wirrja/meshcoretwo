// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant
import java.util.UUID

/**
 * An immutable snapshot of a remote-node session — see [ContactDto]'s doc for why this crosses
 * the DAO/store boundary instead of the Room entity. Ported from `RemoteNodeSessionDTO`
 * (`RemoteNodeSession.swift`), trimmed to the fields [RemoteNodeSessionEntity] carries; the
 * `with(...)` copy helper is dropped (Kotlin's generated `copy()` already covers its job). This
 * type has no `isMuted` field of its own — like [ChannelDto], "muted" is `notificationLevel ==
 * .MUTED`, computed where it's needed (`Conversation.kt`'s `app`-layer chat-list shaping) rather
 * than stored redundantly; Swift's `RemoteNodeSession` migrated its own legacy `isMuted` boolean
 * into `notificationLevelRawValue` the same way, and there's no legacy install to migrate from
 * here, so this port never had the boolean column to begin with.
 */
data class RemoteNodeSessionDto(
    val id: UUID,
    val radioID: UUID,
    val publicKey: ByteArray,
    val name: String,
    val role: RemoteNodeRole,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val isConnected: Boolean = false,
    val permissionLevel: RoomPermissionLevel = RoomPermissionLevel.GUEST,
    val lastConnectedDate: Instant? = null,
    val lastBatteryMillivolts: Int? = null,
    val lastUptimeSeconds: Long? = null,
    val lastNoiseFloor: Int? = null,
    val unreadCount: Int = 0,
    val notificationLevel: NotificationLevel = NotificationLevel.ALL,
    val lastRxAirtimeSeconds: Long? = null,
    val neighborCount: Int = 0,
    val lastSyncTimestamp: Long = 0,
    val lastMessageDate: Instant? = null,
    val isFavorite: Boolean = false,
) {
    /** 6-byte public key prefix for addressing. */
    val publicKeyPrefix: ByteArray get() = publicKey.copyOfRange(0, minOf(6, publicKey.size))

    /**
     * Whether the node reported a usable location — latitude/longitude default to (0,0) when GPS
     * was never shared, so the sentinel and range are both checked, mirroring [ContactDto].
     */
    val hasLocation: Boolean
        get() {
            if (latitude == 0.0 && longitude == 0.0) return false
            return latitude in -90.0..90.0 && longitude in -180.0..180.0
        }

    val isRoom: Boolean get() = role == RemoteNodeRole.ROOM_SERVER

    val isRepeater: Boolean get() = role == RemoteNodeRole.REPEATER

    val canPost: Boolean get() = isRoom && permissionLevel.canPost

    val isAdmin: Boolean get() = permissionLevel.isAdmin

    // ByteArray has reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteNodeSessionDto) return false
        return id == other.id &&
            radioID == other.radioID &&
            publicKey.contentEquals(other.publicKey) &&
            name == other.name &&
            role == other.role &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            isConnected == other.isConnected &&
            permissionLevel == other.permissionLevel &&
            lastConnectedDate == other.lastConnectedDate &&
            lastBatteryMillivolts == other.lastBatteryMillivolts &&
            lastUptimeSeconds == other.lastUptimeSeconds &&
            lastNoiseFloor == other.lastNoiseFloor &&
            unreadCount == other.unreadCount &&
            notificationLevel == other.notificationLevel &&
            lastRxAirtimeSeconds == other.lastRxAirtimeSeconds &&
            neighborCount == other.neighborCount &&
            lastSyncTimestamp == other.lastSyncTimestamp &&
            lastMessageDate == other.lastMessageDate &&
            isFavorite == other.isFavorite
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + permissionLevel.hashCode()
        result = 31 * result + (lastConnectedDate?.hashCode() ?: 0)
        result = 31 * result + (lastBatteryMillivolts ?: 0)
        result = 31 * result + (lastUptimeSeconds?.hashCode() ?: 0)
        result = 31 * result + (lastNoiseFloor ?: 0)
        result = 31 * result + unreadCount
        result = 31 * result + notificationLevel.hashCode()
        result = 31 * result + (lastRxAirtimeSeconds?.hashCode() ?: 0)
        result = 31 * result + neighborCount
        result = 31 * result + lastSyncTimestamp.hashCode()
        result = 31 * result + (lastMessageDate?.hashCode() ?: 0)
        result = 31 * result + isFavorite.hashCode()
        return result
    }
}

/** Maps a persisted row to the immutable snapshot services consume. */
fun RemoteNodeSessionEntity.toDto(): RemoteNodeSessionDto = RemoteNodeSessionDto(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    role = RemoteNodeRole.fromRawValue(roleRawValue.toUByte()) ?: RemoteNodeRole.REPEATER,
    latitude = latitude,
    longitude = longitude,
    isConnected = isConnected,
    permissionLevel = RoomPermissionLevel.fromRawValue(permissionLevelRawValue.toUByte()) ?: RoomPermissionLevel.GUEST,
    lastConnectedDate = lastConnectedDate,
    lastBatteryMillivolts = lastBatteryMillivolts,
    lastUptimeSeconds = lastUptimeSeconds,
    lastNoiseFloor = lastNoiseFloor,
    unreadCount = unreadCount,
    notificationLevel = NotificationLevel.fromRawValue(notificationLevelRawValue) ?: NotificationLevel.ALL,
    lastRxAirtimeSeconds = lastRxAirtimeSeconds,
    neighborCount = neighborCount,
    lastSyncTimestamp = lastSyncTimestamp,
    lastMessageDate = lastMessageDate,
    isFavorite = isFavorite,
)

/** The reverse of [RemoteNodeSessionEntity.toDto] — backup import's batch-insert needs to persist an arbitrary [RemoteNodeSessionDto]. */
fun RemoteNodeSessionDto.toEntity(): RemoteNodeSessionEntity = RemoteNodeSessionEntity(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    roleRawValue = role.rawValue.toInt(),
    latitude = latitude,
    longitude = longitude,
    isConnected = isConnected,
    permissionLevelRawValue = permissionLevel.rawValue.toInt(),
    lastConnectedDate = lastConnectedDate,
    lastBatteryMillivolts = lastBatteryMillivolts,
    lastUptimeSeconds = lastUptimeSeconds,
    lastNoiseFloor = lastNoiseFloor,
    unreadCount = unreadCount,
    notificationLevelRawValue = notificationLevel.rawValue,
    lastRxAirtimeSeconds = lastRxAirtimeSeconds,
    neighborCount = neighborCount,
    lastSyncTimestamp = lastSyncTimestamp,
    lastMessageDate = lastMessageDate,
    isFavorite = isFavorite,
)
