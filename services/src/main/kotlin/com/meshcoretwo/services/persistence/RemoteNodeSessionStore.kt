// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.services.backup.BackupDedupKeys
import com.meshcoretwo.services.backup.PerTypeCounts
import com.meshcoretwo.services.backup.RemoteNodeSessionBatchInsertResult
import com.meshcoretwo.services.backup.mergeRemoteNodeSessionBackupMetadata
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Remote-node session persistence, wrapping [RemoteNodeSessionDao] with the upsert/dedup logic
 * Swift keeps in `PersistenceStore+Rooms.swift`. Scoped to what [RemoteNodeService]/
 * [com.meshcoretwo.services.remotenode.RepeaterAdminService]/
 * [com.meshcoretwo.services.remotenode.RoomAdminService]/
 * [com.meshcoretwo.services.remotenode.RoomServerService]'s ported methods actually call, plus
 * [resetAllConnections] for `ConnectionManager.activate()`.
 *
 * [deleteSession]/[cleanupDuplicateSessions] cascade-delete a session's `room_messages` rows
 * directly via [MeshCoreDatabase.roomMessageDao] rather than taking a [RoomMessageStore]
 * dependency — matching Swift's `deleteRemoteNodeSession`/`cleanupDuplicateRemoteNodeSessions`,
 * which delete `RoomMessage` rows inline in the same `modelContext` transaction rather than
 * routing through a separate persistence type.
 *
 * [fetchSession] (publicKey overload) and [fetchSessionByPrefix]'s `radioID`-scoped overloads,
 * plus [cleanupDuplicateSessions]'s scoping to the kept row's own radio, port upstream
 * `e410eb8d` ("fix(rooms): scope remote sessions to radioID"): a remote node's public key is
 * mesh-wide, but a session row belongs to one local radio partition — an unscoped lookup could
 * resolve/delete/reconnect against another local radio's session for the same physical node.
 * [RemoteNodeSessionEntity]'s class doc previously (incorrectly) defended the unscoped design as
 * a faithful port; `e410eb8d` fixed the same bug upstream, so this scopes to match.
 */
class RemoteNodeSessionStore(private val database: MeshCoreDatabase) {
    private val dao get() = database.remoteNodeSessionDao()

    /** Fetch a remote node session by its local id. */
    suspend fun fetchSession(id: UUID): RemoteNodeSessionDto? = dao.fetchById(id)?.toDto()

    /** Fetch a remote node session by its full 32-byte public key, scoped to [radioID]. */
    suspend fun fetchSession(radioID: UUID, publicKey: ByteArray): RemoteNodeSessionDto? =
        dao.fetchByRadioID(radioID).firstOrNull { it.publicKey.contentEquals(publicKey) }?.toDto()

    /** Fetch every remote node session for a device. */
    suspend fun fetchSessions(radioID: UUID): List<RemoteNodeSessionDto> = dao.fetchByRadioID(radioID).map { it.toDto() }

    /** Live version of [fetchSessions] — see `ContactStore.observeContacts` for why this exists. */
    fun observeSessions(radioID: UUID): Flow<List<RemoteNodeSessionDto>> =
        dao.observeByRadioID(radioID).map { entities -> entities.map { it.toDto() } }

    /** Every remote node session across every device — backup export's unscoped fetch. Ported from `fetchAllRemoteNodeSessions`. */
    suspend fun fetchAllSessions(): List<RemoteNodeSessionDto> = dao.fetchAll().map { it.toDto() }

    /**
     * Fetch a remote node session by its 6-byte public key prefix. Not scoped by radio —
     * [RepeaterAdminService.getConnectedSession]/[RoomAdminService.getConnectedSession] use this
     * overload, matching upstream's own `RepeaterAdminService.swift`/`RoomAdminService.swift`,
     * which `e410eb8d` left unscoped (it only scoped [RoomServerService]'s two call sites — see
     * [fetchSessionByPrefix]'s `radioID` overload below).
     */
    suspend fun fetchSessionByPrefix(prefix: ByteArray): RemoteNodeSessionDto? =
        dao.fetchAll().firstOrNull { it.publicKey.copyOfRange(0, minOf(6, it.publicKey.size)).contentEquals(prefix) }?.toDto()

    /** Fetch a remote node session by its 6-byte public key prefix, scoped to [radioID]. */
    suspend fun fetchSessionByPrefix(radioID: UUID, prefix: ByteArray): RemoteNodeSessionDto? =
        dao.fetchByRadioID(radioID).firstOrNull { it.publicKey.copyOfRange(0, minOf(6, it.publicKey.size)).contentEquals(prefix) }?.toDto()

    /** Fetch every currently-connected session, for BLE-reconnection re-auth. */
    suspend fun fetchConnectedSessions(): List<RemoteNodeSessionDto> = dao.fetchConnected().map { it.toDto() }

    /** Saves or updates a session, matched by [RemoteNodeSessionDto.id]. */
    suspend fun saveSession(dto: RemoteNodeSessionDto) {
        val entity = RemoteNodeSessionEntity(
            id = dto.id,
            radioID = dto.radioID,
            publicKey = dto.publicKey,
            name = dto.name,
            roleRawValue = dto.role.rawValue.toInt(),
            latitude = dto.latitude,
            longitude = dto.longitude,
            isConnected = dto.isConnected,
            permissionLevelRawValue = dto.permissionLevel.rawValue.toInt(),
            lastConnectedDate = dto.lastConnectedDate,
            lastBatteryMillivolts = dto.lastBatteryMillivolts,
            lastUptimeSeconds = dto.lastUptimeSeconds,
            lastNoiseFloor = dto.lastNoiseFloor,
            unreadCount = dto.unreadCount,
            notificationLevelRawValue = dto.notificationLevel.rawValue,
            lastRxAirtimeSeconds = dto.lastRxAirtimeSeconds,
            neighborCount = dto.neighborCount,
            lastSyncTimestamp = dto.lastSyncTimestamp,
            lastMessageDate = dto.lastMessageDate,
        )
        if (dao.fetchById(dto.id) != null) dao.update(entity) else dao.insert(entity)
    }

    /**
     * Updates a session's connection state and permission level. Matches
     * `updateRemoteNodeSessionConnection`: stamps [RemoteNodeSessionDto.lastConnectedDate] to now
     * when [isConnected] is true, leaves it untouched otherwise. A no-op if the session doesn't exist.
     */
    suspend fun updateConnection(id: UUID, isConnected: Boolean, permissionLevel: RoomPermissionLevel) {
        val existing = dao.fetchById(id) ?: return
        dao.update(
            existing.copy(
                isConnected = isConnected,
                permissionLevelRawValue = permissionLevel.rawValue.toInt(),
                lastConnectedDate = if (isConnected) Instant.now() else existing.lastConnectedDate,
            ),
        )
    }

    /** Marks a session disconnected without touching its permission level. A no-op if the session doesn't exist. */
    suspend fun markDisconnected(id: UUID) {
        val existing = dao.fetchById(id) ?: return
        dao.update(existing.copy(isConnected = false))
    }

    /**
     * Marks a room session as connected. Called when an incoming message proves the session is
     * active. Only sets `isConnected`; does not change permission level. Matches
     * `markRoomSessionConnected`.
     *
     * @return `true` if the session was actually changed (was disconnected, now connected).
     */
    suspend fun markRoomSessionConnected(id: UUID): Boolean {
        val existing = dao.fetchById(id) ?: return false
        if (existing.isConnected) return false
        dao.update(existing.copy(isConnected = true))
        return true
    }

    /**
     * Updates room activity timestamps. [syncTimestamp] (the sender-clock timestamp on an
     * inbound message) only ever advances [RemoteNodeSessionDto.lastSyncTimestamp], never rewinds
     * it; omit it on the send path to avoid a local send timestamp advancing the sync bookmark
     * past messages the server hasn't delivered yet. `lastMessageDate` is always stamped to now.
     * A no-op if the session doesn't exist. Matches `updateRoomActivity`.
     */
    suspend fun updateRoomActivity(id: UUID, syncTimestamp: UInt? = null) {
        val existing = dao.fetchById(id) ?: return
        dao.update(
            existing.copy(
                lastSyncTimestamp = if (syncTimestamp != null && syncTimestamp.toLong() > existing.lastSyncTimestamp) syncTimestamp.toLong() else existing.lastSyncTimestamp,
                lastMessageDate = Instant.now(),
            ),
        )
    }

    /** Increments a room session's unread message count. A no-op if the session doesn't exist. Matches `incrementRoomUnreadCount`. */
    suspend fun incrementUnreadCount(id: UUID) {
        val existing = dao.fetchById(id) ?: return
        dao.update(existing.copy(unreadCount = existing.unreadCount + 1))
    }

    /** Resets a room session's unread message count to zero. A no-op if the session doesn't exist. Matches `resetRoomUnreadCount`. */
    suspend fun resetUnreadCount(id: UUID) {
        val existing = dao.fetchById(id) ?: return
        dao.update(existing.copy(unreadCount = 0))
    }

    /** Sets a session's favorite flag. A no-op if the session doesn't exist. Local-only preference — matches [ChannelStore.setChannelFavorite]'s scope. */
    suspend fun setFavorite(id: UUID, isFavorite: Boolean) {
        val existing = dao.fetchById(id) ?: return
        dao.update(existing.copy(isFavorite = isFavorite))
    }

    /** Sets a session's notification level. A no-op if the session doesn't exist. Local-only preference — matches [ChannelStore.setChannelNotificationLevel]'s scope. */
    suspend fun setNotificationLevel(id: UUID, level: NotificationLevel) {
        val existing = dao.fetchById(id) ?: return
        dao.update(existing.copy(notificationLevelRawValue = level.rawValue))
    }

    /**
     * Deletes every session on [keepID]'s own radio that shares [publicKey], except [keepID]
     * itself (and their room messages), for post-restore/radio-swap dedup. Scoped to [keepID]'s
     * radio — another radio's session for the same node is a separate partition, not a
     * duplicate. A no-op if [keepID] doesn't exist.
     */
    suspend fun cleanupDuplicateSessions(publicKey: ByteArray, keepID: UUID) {
        val kept = dao.fetchById(keepID) ?: return
        dao.fetchAll()
            .filter { it.publicKey.contentEquals(publicKey) && it.id != keepID && it.radioID == kept.radioID }
            .forEach {
                database.roomMessageDao().deleteForSession(it.id)
                dao.delete(it.id)
            }
    }

    /** Deletes a session by its local id, cascading to its room messages. Matches `deleteRemoteNodeSession`. */
    suspend fun deleteSession(id: UUID) {
        database.roomMessageDao().deleteForSession(id)
        dao.delete(id)
    }

    /**
     * Marks every remote node session disconnected. Called once by `ConnectionManager.activate()`
     * on app launch: a session's `isConnected` flag reflects the previous process's BLE link,
     * which cannot have survived a fresh process start. Matches
     * `resetAllRemoteNodeSessionConnections`, now that a `ConnectionManager` calls it.
     */
    suspend fun resetAllConnections() = dao.resetAllConnections()

    /** Every session across [radioIDs], keyed by [BackupDedupKeys.remoteNodeSessionKey] — backup import's existing-row lookup. Ported from `fetchExistingRemoteNodeSessionsByKey`. */
    suspend fun fetchExistingSessionsByKey(radioIDs: Set<UUID>): Map<String, RemoteNodeSessionDto> {
        if (radioIDs.isEmpty()) return emptyMap()
        return dao.fetchByRadioIDs(radioIDs.toList()).associate { BackupDedupKeys.remoteNodeSessionKey(it.radioID, it.publicKey) to it.toDto() }
    }

    /**
     * Reconciles backup sessions against local sessions by `(radioID, publicKey)`: a match merges
     * backup metadata into the existing row; anything else inserts as a new, disconnected session
     * (a restored session is never a live BLE connection). Ported from `batchInsertRemoteNodeSessions`.
     */
    suspend fun batchInsertRemoteNodeSessions(dtos: List<RemoteNodeSessionDto>, radioIDs: Set<UUID>): RemoteNodeSessionBatchInsertResult {
        val existingByKey = fetchExistingSessionsByKey(radioIDs).toMutableMap()
        val sessionIdsByKey = mutableMapOf<String, UUID>()
        var inserted = 0
        var merged = 0
        var skipped = 0
        for (dto in dtos) {
            val key = BackupDedupKeys.remoteNodeSessionKey(dto.radioID, dto.publicKey)
            val existing = existingByKey[key]
            if (existing != null) {
                val (mergedDto, changed) = mergeRemoteNodeSessionBackupMetadata(existing, dto)
                if (changed) {
                    dao.update(mergedDto.toEntity())
                    existingByKey[key] = mergedDto
                    merged++
                }
                skipped++
                sessionIdsByKey[key] = existing.id
                continue
            }
            val seeded = dto.copy(isConnected = false)
            dao.insert(seeded.toEntity())
            existingByKey[key] = seeded
            sessionIdsByKey[key] = seeded.id
            inserted++
        }
        return RemoteNodeSessionBatchInsertResult(PerTypeCounts(inserted = inserted, merged = merged, skipped = skipped), sessionIdsByKey)
    }

    /**
     * Advances `lastMessageDate` toward the per-session max timestamp from a just-completed backup
     * import, keyed by local session id. Existing values are preserved when already newer. Ported
     * from `applyLastMessageDatesToRemoteNodeSessions`.
     */
    suspend fun applyLastMessageDatesToRemoteNodeSessions(maxDates: Map<UUID, Instant>) {
        if (maxDates.isEmpty()) return
        for (session in dao.fetchByIds(maxDates.keys.toList())) {
            val latest = maxDates[session.id] ?: continue
            if (session.lastMessageDate == null || session.lastMessageDate!! < latest) {
                dao.update(session.copy(lastMessageDate = latest))
            }
        }
    }
}
