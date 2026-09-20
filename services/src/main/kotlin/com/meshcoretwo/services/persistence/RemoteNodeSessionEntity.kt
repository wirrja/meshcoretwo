// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * An authenticated session with a remote node (room server or repeater). Ported from
 * `RemoteNodeSession.swift`'s `@Model` (SwiftData) to a Room `@Entity`.
 *
 * A remote node's public key is a mesh-wide identity, but [RemoteNodeSessionStore]'s `publicKey`/
 * prefix lookups and its `cleanupDuplicateSessions` dedup are scoped to a `radioID`: another
 * radio's session for the same physical node is a separate partition, not a duplicate — upstream
 * `e410eb8d` ("fix(rooms): scope remote sessions to radioID") fixed the same unscoped-by-`id`
 * design this class originally ported faithfully from `PersistenceStore+Rooms.swift`'s pre-fix
 * `fetchRemoteNodeSession(publicKey:)`/`fetchRemoteNodeSessionByPrefix`/
 * `cleanupDuplicateRemoteNodeSessions`. [publicKey] is deliberately **not** a unique index (only
 * [id] is, matching Swift's `@Attribute(.unique)` on `id` alone) — a duplicate row per public key
 * is exactly the state [RemoteNodeSessionStore.cleanupDuplicateSessions] exists to clean up after
 * a restore/radio-swap, and is also the *correct*, permanent state across two radios sharing a
 * node; a unique constraint on [publicKey] alone would make both impossible.
 *
 * Drops `legacyIsMuted`/the `notificationLevelRawValue`-migration dance (there is no legacy V1
 * schema to migrate from on a from-scratch Android install — same reasoning as [ChannelEntity]
 * dropping `legacyIsMuted`) and `lastMessageDate`'s Swift fallback-to-sync-timestamp compat (same
 * "no prior installs" reasoning). `isFavorite` was dropped the same way at first, then added back
 * (bump to [MeshCoreDatabase.version] 22) once the "Room chat UI"/"Room favorite" slices gave rooms
 * a chat-list row to show it on — see [RemoteNodeSessionDto]'s class doc. `roleRawValue`/`permissionLevelRawValue` are [Int]
 * and `lastBatteryMillivolts`/`lastUptimeSeconds`/`lastRxAirtimeSeconds`/`lastSyncTimestamp` are
 * [Int]/[Long] rather than the wire's `UByte`/`UShort`/`UInt` — Room's KSP processor cannot handle
 * Kotlin's unsigned types as column types (see [ContactEntity]'s class doc); converted at the
 * [RemoteNodeSessionDto] boundary instead. `lastNoiseFloor` (`Int16` in Swift) is already signed,
 * so it widens losslessly to [Int] with no encoding scheme needed.
 */
@Entity(
    tableName = "remote_node_sessions",
    indices = [
        Index(value = ["radioID"]),
        Index(value = ["publicKey"]),
    ],
)
data class RemoteNodeSessionEntity(
    @PrimaryKey val id: UUID,
    /** The companion radio used to access this node. Not a uniqueness scope — see class doc. */
    val radioID: UUID,
    val publicKey: ByteArray,
    val name: String,
    val roleRawValue: Int,
    val latitude: Double,
    val longitude: Double,
    val isConnected: Boolean,
    val permissionLevelRawValue: Int,
    val lastConnectedDate: Instant?,
    val lastBatteryMillivolts: Int?,
    val lastUptimeSeconds: Long?,
    val lastNoiseFloor: Int?,
    val unreadCount: Int,
    val notificationLevelRawValue: Int,
    val lastRxAirtimeSeconds: Long?,
    val neighborCount: Int,
    val lastSyncTimestamp: Long,
    val lastMessageDate: Instant?,
    val isFavorite: Boolean = false,
)
