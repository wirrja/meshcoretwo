// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.withTransaction
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.backup.PerTypeCounts
import java.time.Instant
import java.util.UUID

/**
 * Device persistence, wrapping [DeviceDao] with the upsert/activation logic Swift keeps in
 * `PersistenceStore+Devices.swift`. [demoteDeviceToGhost]/[reconcileGhostIdentity]/
 * [deleteDeviceAndData] are all ported (see their own docs) — no Room `@ForeignKey` links
 * Contact/Message/Channel/etc. to [DeviceEntity] anywhere in this schema, so [deleteDeviceAndData]
 * cascades across every one of those tables by hand, matching how Swift's own
 * `deleteDeviceAndData`/`_deleteAllDeviceData` do it (SwiftData's bulk `delete(model:where:)`
 * bypasses `@Relationship(deleteRule: .cascade)` too, so Swift is manual here as well, not just
 * this port).
 *
 * `fetchDeviceById`/`fetchDeviceByRadioId` aren't overloads of one `fetchDevice(...)` name (as
 * `PersistenceStore+Devices.swift`'s `fetchDevice(id:)`/`fetchDevice(radioID:)` are in Swift):
 * Kotlin/JVM overload resolution is by parameter type, not argument label, and both take a bare
 * [UUID] — the same clash [DeviceDao] avoids the same way.
 */
class DeviceStore(private val database: MeshCoreDatabase) {
    private val dao get() = database.deviceDao()

    suspend fun fetchDevices(): List<DeviceDto> = dao.fetchDevices().map { it.toDto() }

    suspend fun fetchDeviceById(id: UUID): DeviceDto? = dao.fetchDeviceById(id)?.toDto()

    suspend fun fetchDeviceByRadioId(radioID: UUID): DeviceDto? = dao.fetchDeviceByRadioId(radioID)?.toDto()

    suspend fun fetchDevice(publicKey: ByteArray): DeviceDto? = dao.fetchDevice(publicKey)?.toDto()

    /** Looks up a device by its last-known BLE MAC address — see [DeviceEntity.bleAddress]'s doc. */
    suspend fun fetchDeviceByBleAddress(bleAddress: String): DeviceDto? = dao.fetchDeviceByBleAddress(bleAddress)?.toDto()

    /** Saves or updates a device, matched by [DeviceDto.id] — a full overwrite either way, matching `Device.apply(_:)`. */
    suspend fun saveDevice(dto: DeviceDto) {
        if (dao.fetchDeviceById(dto.id) != null) {
            dao.update(dto.toEntity())
        } else {
            dao.insert(dto.toEntity())
        }
    }

    /** Marks [id] as the active device, deactivating every other one, and stamps [now] as its last-connected time. */
    suspend fun setActiveDevice(id: UUID, now: Instant = Instant.now()) {
        database.withTransaction {
            dao.deactivateAll()
            dao.fetchDeviceById(id)?.let { dao.update(it.copy(isActive = true, lastConnected = now)) }
        }
    }

    /**
     * Stamps a device's contact-sync watermark unconditionally (no max-wins — a caller passing an
     * older value would regress it). No-op if the device doesn't exist, unlike Swift's
     * `updateDeviceLastContactSync` which throws `.deviceNotFound` — matching this store's own
     * no-op-on-missing-row convention (see [ContactStore]'s counterparts). Ported from
     * `updateDeviceLastContactSync`.
     */
    suspend fun updateDeviceLastContactSync(radioID: UUID, timestamp: UInt) = dao.updateLastContactSync(radioID, timestamp.toLong())

    /**
     * Deletes a device row and cascades across every table this data partitions on `radioID` (or a
     * table keyed transitively through one of them): Reaction, RemoteNodeSession(+RoomMessage,
     * +orphan-checked NodeStatusSnapshot), BlockedChannelSender, RxLogEntry, DiscoveredNode,
     * Contact, Message(+PendingSend/MessageRepeat sub-cascade), Channel, SavedTracePath
     * (+TracePathRun). Room has no `@ForeignKey`/`onDelete` cascade anywhere in this schema (see
     * this class's doc), so every step below is an explicit, ordered delete — ported from
     * `deleteDeviceAndData`/its private `_deleteAllDeviceData` helper in
     * `PersistenceStore+Devices.swift`, replicating that method's exact step order in one Room
     * transaction (Room's atomicity boundary, standing in for SwiftData's single-save-at-the-end).
     *
     * [NodeStatusSnapshotEntity] is node-identity-scoped, not radio-scoped (shared across
     * devices/re-pairings of the same physical node) — its rows for a node's public key are removed
     * only when no [RemoteNodeSessionEntity] anywhere (not just this radio's) still references it,
     * mirroring Swift's cross-device-shared-orphan check exactly.
     *
     * No-op if [id] doesn't exist. This is the "delete everything" counterpart to
     * [demoteDeviceToGhost] ("keep my data"). Used by `ConnectionManager`'s pairing slice
     * (`forgetDevice`'s `deleteData = true` branch and the factory-reset overload) — see its class
     * doc. Swift's sibling `deleteDeviceData(id:)` (cascade without deleting the `Device` row — a
     * separate "wipe data, keep device" feature) has no Android caller and isn't ported here.
     */
    suspend fun deleteDeviceAndData(id: UUID) = database.withTransaction {
        val device = dao.fetchDeviceById(id) ?: return@withTransaction
        val radioID = device.radioID

        database.reactionDao().deleteAll(radioID)

        val sessions = database.remoteNodeSessionDao().fetchByRadioID(radioID)
        for (session in sessions) database.roomMessageDao().deleteForSession(session.id)
        database.remoteNodeSessionDao().deleteAll(radioID)
        // ByteArray has reference equality, so distinctBy { it.toList() } is needed to actually
        // dedupe public keys here (a plain distinct() would under-dedupe harmlessly, but this is
        // just as cheap and correct).
        for (publicKey in sessions.map { it.publicKey }.distinctBy { it.toList() }) {
            if (database.remoteNodeSessionDao().countByPublicKey(publicKey) == 0) {
                database.nodeStatusSnapshotDao().deleteForPublicKey(publicKey)
            }
        }

        database.blockedChannelSenderDao().deleteAll(radioID)
        database.rxLogDao().deleteAll(radioID)
        database.discoveredNodeDao().deleteAll(radioID)
        database.contactDao().deleteAll(radioID)

        // PendingSend/MessageRepeat sub-cascade first, matching Swift's ordering and its defensive
        // radioID-keyed PendingSend sweep for orphans a messageID join can't see.
        val messageIDs = database.messageDao().fetchMessageIdsForRadio(radioID)
        database.pendingSendDao().deleteForMessages(messageIDs)
        database.messageRepeatDao().deleteForMessages(messageIDs)
        database.pendingSendDao().deleteAll(radioID)
        database.messageDao().deleteMessagesForRadio(radioID)

        database.channelDao().deleteAll(radioID)

        for (path in database.tracePathDao().fetchForRadio(radioID)) {
            database.tracePathRunDao().deleteForPath(path.id)
        }
        database.tracePathDao().deleteAll(radioID)

        dao.deleteById(id)
    }

    /**
     * Demotes a device to a ghost: deletes the row and reinserts it with a fresh [DeviceDto.id]
     * (Room's `@Update` matches by primary key, so changing it requires delete+insert — this
     * happens to match Swift's own delete+reinsert-with-fresh-id approach in `demoteDeviceToGhost`
     * exactly, so no divergence), `isActive = false`, [DeviceDto.isGhost] `= true`, and
     * [DeviceDto.bleAddress] cleared. [DeviceDto.wifiHost]/[DeviceDto.wifiPort] are deliberately
     * *not* cleared — Swift's `demoteDeviceToGhost` does empty its whole `connectionMethods` array,
     * WiFi included, but this port already treats WiFi host/port as portable, stable connection
     * info distinct from a volatile BLE MAC address everywhere else it redacts a device (see
     * [DeviceDto.redactedForBackup]'s doc, which keeps them for the same reason); keeping them here
     * too is what lets [reconcileGhostIdentity] recall the last known WiFi endpoint for a radio
     * that gets re-paired over BLE first. [DeviceDto.publicKey]/[DeviceDto.radioID] are preserved
     * verbatim: [reconcileGhostIdentity] matches ghosts by [DeviceDto.publicKey], and every
     * Contact/Message/Channel/etc. row stays keyed by the untouched [DeviceDto.radioID] — nothing
     * else needs to move, since those tables were never touched in the first place (see this
     * class's doc). No-op if [id] doesn't exist. Ported from `demoteDeviceToGhost(id:)`.
     */
    suspend fun demoteDeviceToGhost(id: UUID) {
        database.withTransaction {
            val device = dao.fetchDeviceById(id) ?: return@withTransaction
            dao.deleteById(id)
            dao.insert(
                device.copy(
                    id = UUID.randomUUID(),
                    isActive = false,
                    isGhost = true,
                    bleAddress = null,
                ),
            )
        }
    }

    /**
     * The other half of ghost-identity reconciliation: called after a config import pushes a
     * (possibly different) private key onto the connected radio, with [newPublicKey] freshly
     * re-read from the radio itself. Looks for a ghost (see [DeviceEntity.isGhost]) whose
     * [DeviceDto.publicKey] matches, other than [currentDeviceId] itself — i.e. the *original*
     * identity this radio is being restored to. If found: re-points the live ([currentDeviceId])
     * row's `radioID`/`publicKey` to the ghost's, merges in the ghost's `wifiHost`/`wifiPort` where
     * the live row doesn't already have its own (a ghost never carries a `bleAddress`, see
     * [demoteDeviceToGhost]), and deletes the ghost row — every Contact/Message/Channel/etc. row
     * that was sitting under the ghost's `radioID` becomes reachable again for free, since they
     * were never touched (nothing is copied/migrated row-by-row; a child row written under the
     * live device's own *interim* `radioID`, between re-pairing and this call, stays orphaned —
     * documented Swift limitation, not a bug).
     *
     * **Deletes the ghost row before updating the live row** — the opposite order from Swift's own
     * `reconcileGhostIdentity`, which sets fields then deletes. Swift's `Device.publicKey`/
     * `radioID` carry no uniqueness constraint at the schema level (only `id` does); this port's
     * [DeviceEntity] has a unique index on *both* `radioID` and `publicKey` (see its class doc), so
     * updating the live row to values the still-present ghost row currently holds would throw
     * `SQLiteConstraintException` in Swift's order. Deleting first avoids ever having two rows
     * share a unique value, even transiently, within the one transaction.
     *
     * @return the reconciled `radioID`, or `null` if no ghost matched.
     */
    suspend fun reconcileGhostIdentity(currentDeviceId: UUID, newPublicKey: ByteArray): UUID? = database.withTransaction {
        val ghost = dao.fetchGhostByPublicKey(newPublicKey, excludingId = currentDeviceId) ?: return@withTransaction null
        val live = dao.fetchDeviceById(currentDeviceId) ?: return@withTransaction null
        dao.deleteById(ghost.id)
        dao.update(
            live.copy(
                radioID = ghost.radioID,
                publicKey = newPublicKey,
                wifiHost = live.wifiHost ?: ghost.wifiHost,
                wifiPort = live.wifiPort ?: ghost.wifiPort,
            ),
        )
        ghost.radioID
    }

    /**
     * Every local device's publicKey (as [hexString]) -> radioID, in one pass — feeds backup
     * import's radioID remap. Ported from `existingDeviceRadioIDsByPublicKey`.
     */
    suspend fun existingDeviceRadioIdsByPublicKey(): Map<String, UUID> =
        dao.fetchDevices().associate { it.publicKey.hexString to it.radioID }

    /**
     * Inserts backup devices whose publicKey isn't already in [existingPublicKeysHex] (as
     * [hexString]). A fresh [DeviceDto.id] is minted on insert — the backup UUID was a source
     * phone's BLE peripheral identifier and could collide with a live local one under
     * [DeviceDao]'s unique `id` — and [DeviceDto.isActive] is always forced `false`, matching
     * Swift's `cleanedForImport()`/`batchInsertDevices` (a restored device is never the live
     * connection). Ported from `batchInsertDevices`.
     */
    suspend fun batchInsertDevices(dtos: List<DeviceDto>, existingPublicKeysHex: Set<String>): PerTypeCounts {
        val knownKeys = existingPublicKeysHex.toMutableSet()
        var inserted = 0
        var skipped = 0
        for (dto in dtos) {
            val key = dto.publicKey.hexString
            if (!knownKeys.add(key)) {
                skipped++
                continue
            }
            dao.insert(dto.copy(id = UUID.randomUUID(), isActive = false).toEntity())
            inserted++
        }
        return PerTypeCounts(inserted = inserted, skipped = skipped)
    }
}
