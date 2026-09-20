// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.withTransaction
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.backup.ChannelBatchInsertResult
import com.meshcoretwo.services.backup.PerTypeCounts
import com.meshcoretwo.services.backup.channelHasStableSecret
import com.meshcoretwo.services.backup.mergeChannelBackupMetadata
import com.meshcoretwo.services.backup.resolveChannelPlacementIndex
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Channel persistence, wrapping [ChannelDao] with the upsert logic Swift keeps in
 * `PersistenceStore+Channels.swift`. Scoped to what `ChannelService`/`MessageService`'s ported
 * methods actually call: fetch, single-item save, batch sync persist, delete, last-message
 * timestamp, the two unread-counter resets (added for `clearChannelMessages` in the
 * "HeardRepeats" slice), and (added for the mention-count/blocked-sender backfill slice) the
 * unread/unread-mention counter increments and mention-counter decrement
 * [com.meshcoretwo.services.messages.IncomingMessageService]'s ingestion pipeline needs, plus
 * (added for Phase 5's channel-management UI slice) [setChannelFavorite]/
 * [setChannelNotificationLevel] — app-only metadata writes with no radio round-trip, exactly
 * mirroring how `ChatViewModel.setFavorite`/`setNotificationLevel` call `dataStore` directly on
 * iOS rather than going through `ChannelService`. [com.meshcoretwo.services.channels.ChannelService]
 * exposes thin passthroughs to these two for callers outside `services`, matching how the rest of
 * this codebase keeps UI talking to services rather than stores directly.
 */
class ChannelStore(private val database: MeshCoreDatabase) {
    private val dao get() = database.channelDao()

    /** Fetch all channels for a device, sorted by index. */
    suspend fun fetchChannels(radioID: UUID): List<ChannelDto> = dao.fetchChannels(radioID).map { it.toDto() }

    /** Every channel across every device — backup export's unscoped fetch. Ported from `fetchAllChannels`. */
    suspend fun fetchAllChannels(): List<ChannelDto> = dao.fetchAll().map { it.toDto() }

    /** Live version of [fetchChannels] — re-emits whenever any row in `channels` changes. */
    fun observeChannels(radioID: UUID): Flow<List<ChannelDto>> =
        dao.observeChannels(radioID).map { entities -> entities.map { it.toDto() } }

    /** Fetch a channel by its device and slot index. */
    suspend fun fetchChannel(radioID: UUID, index: UByte): ChannelDto? = dao.fetchChannel(radioID, index.toInt())?.toDto()

    /** Fetch a channel by its local id. */
    suspend fun fetchChannelById(id: UUID): ChannelDto? = dao.fetchChannel(id)?.toDto()

    /**
     * Saves or updates a channel from a freshly-read wire record, matching by
     * `(radioID, index)`. Returns the row's id — see [ChannelEntity.fromChannelInfo]/
     * [ChannelEntity.updatedFrom] for exactly which fields an update touches.
     */
    suspend fun saveChannel(radioID: UUID, info: ChannelInfo): UUID = database.withTransaction {
        val existing = dao.fetchChannel(radioID, info.index.toInt())
        if (existing != null) {
            dao.update(applyRadioChannelInfo(info, existing))
            existing.id
        } else {
            val id = UUID.randomUUID()
            dao.insert(ChannelEntity.fromChannelInfo(id, radioID, info))
            id
        }
    }

    /**
     * Applies a radio-reported [info] to an existing row. A different secret means a different
     * channel now occupies the slot, so the previous occupant's messages (and the counters derived
     * from them) are wiped first — a new secret at the same index must never inherit old history.
     * Ported from `applyRadioChannelInfo` (upstream `70b4bcfd`). Must run inside a transaction so a
     * failed write rolls the wipe back with it.
     */
    private suspend fun applyRadioChannelInfo(info: ChannelInfo, row: ChannelEntity): ChannelEntity {
        if (!row.secret.contentEquals(info.secret)) {
            wipeMessagesInTransaction(row.radioID, row.index)
            return row.copy(lastMessageDate = null, unreadCount = 0, unreadMentionCount = 0).updatedFrom(info)
        }
        return row.updatedFrom(info)
    }

    /** Deletes a slot's messages with their Reaction/MessageRepeat/PendingSend rows. Caller supplies the transaction. */
    private suspend fun wipeMessagesInTransaction(radioID: UUID, channelIndex: Int) {
        MessageStore(database).deleteCascading(database.messageDao().fetchMessageIdsForChannel(radioID, channelIndex))
    }

    /**
     * Deletes every message in a channel slot, cascading Reaction/MessageRepeat/PendingSend, in one
     * transaction. Ported from `deleteMessagesForChannel` (upstream `70b4bcfd`).
     */
    suspend fun deleteMessagesForChannel(radioID: UUID, channelIndex: UByte) =
        database.withTransaction { wipeMessagesInTransaction(radioID, channelIndex.toInt()) }

    /**
     * Persists a full channel-sync pass in a single transaction: upserts each [configured]
     * channel (matched by `(radioID, index)`), deletes stale local rows at
     * [unconfiguredIndices], and (when [pruneBeyond] is non-null) deletes rows whose index is
     * `>= pruneBeyond`. An index that is neither configured nor unconfigured (e.g. skipped by a
     * circuit breaker) is left untouched. Returns every channel for the device after the write,
     * sorted by index. Ported from `PersistenceStore+Channels.swift`'s override of the
     * `ChannelPersisting` default (the default is a per-item, per-commit fallback this class
     * doesn't need since it always has a real transaction to run it in).
     */
    suspend fun batchSaveChannels(
        radioID: UUID,
        configured: List<ChannelInfo>,
        unconfiguredIndices: List<UByte>,
        pruneBeyond: UByte?,
    ): List<ChannelDto> {
        database.withTransaction {
            val byIndex = dao.fetchChannels(radioID).associateByTo(mutableMapOf()) { it.index }

            for (info in configured) {
                val index = info.index.toInt()
                val existing = byIndex[index]
                val row = if (existing != null) {
                    applyRadioChannelInfo(info, existing).also { dao.update(it) }
                } else {
                    ChannelEntity.fromChannelInfo(UUID.randomUUID(), radioID, info).also { dao.insert(it) }
                }
                byIndex[index] = row
            }

            // A slot the radio reports empty has no legitimate history, so wipe it even when the
            // row is already gone — that clears leftovers from earlier prunes.
            for (index in unconfiguredIndices) {
                wipeMessagesInTransaction(radioID, index.toInt())
                byIndex.remove(index.toInt())?.let { dao.deleteChannel(it.id) }
            }

            if (pruneBeyond != null) {
                val threshold = pruneBeyond.toInt()
                byIndex.values.filter { it.index >= threshold }.forEach {
                    wipeMessagesInTransaction(radioID, it.index)
                    dao.deleteChannel(it.id)
                }
            }
        }
        return fetchChannels(radioID)
    }

    /**
     * Deletes a channel together with its slot's messages in one transaction, so a failed write
     * rolls both back and a later channel at that slot starts empty. Ported from `deleteChannel`
     * (upstream `70b4bcfd`).
     */
    suspend fun deleteChannel(id: UUID) = database.withTransaction {
        val row = dao.fetchChannel(id) ?: return@withTransaction
        wipeMessagesInTransaction(row.radioID, row.index)
        dao.deleteChannel(id)
    }

    /** Updates a channel's last-message timestamp (`null` clears it), used to sort/preview conversations. */
    suspend fun updateChannelLastMessage(channelId: UUID, date: Instant?) {
        dao.fetchChannel(channelId)?.let { dao.update(it.copy(lastMessageDate = date)) }
    }

    /** Zeroes a channel's unread-message counter. Ported from `clearChannelUnreadCount`. */
    suspend fun clearChannelUnreadCount(channelId: UUID) {
        dao.fetchChannel(channelId)?.let { dao.update(it.copy(unreadCount = 0)) }
    }

    /** Zeroes a channel's unread-mention counter. Ported from `clearChannelUnreadMentionCount`. */
    suspend fun clearChannelUnreadMentionCount(channelId: UUID) {
        dao.fetchChannel(channelId)?.let { dao.update(it.copy(unreadMentionCount = 0)) }
    }

    /** Increments a channel's unread-message counter (no-op if the row doesn't exist). Ported from `incrementChannelUnreadCount`. */
    suspend fun incrementChannelUnreadCount(channelId: UUID) {
        dao.fetchChannel(channelId)?.let { dao.update(it.copy(unreadCount = it.unreadCount + 1)) }
    }

    /** Increments a channel's unread-mention counter (no-op if the row doesn't exist). Ported from `incrementChannelUnreadMentionCount`. */
    suspend fun incrementChannelUnreadMentionCount(channelId: UUID) {
        dao.fetchChannel(channelId)?.let { dao.update(it.copy(unreadMentionCount = it.unreadMentionCount + 1)) }
    }

    /** Decrements a channel's unread-mention counter, clamped at 0. Ported from `decrementChannelUnreadMentionCount`. */
    suspend fun decrementChannelUnreadMentionCount(channelId: UUID) {
        dao.fetchChannel(channelId)?.let { dao.update(it.copy(unreadMentionCount = maxOf(0, it.unreadMentionCount - 1))) }
    }

    /** Sets a channel's favorite flag. App-only — no radio round-trip. Ported from `setChannelFavorite`. */
    suspend fun setChannelFavorite(channelId: UUID, isFavorite: Boolean) {
        dao.fetchChannel(channelId)?.let { dao.update(it.copy(isFavorite = isFavorite)) }
    }

    /** Sets a channel's notification level. App-only — no radio round-trip. Ported from `setChannelNotificationLevel`. */
    suspend fun setChannelNotificationLevel(channelId: UUID, level: NotificationLevel) {
        dao.fetchChannel(channelId)?.let { dao.update(it.copy(notificationLevelRawValue = level.rawValue)) }
    }

    /**
     * Atomically updates a channel's flood-scope preference — both backing columns
     * ([ChannelEntity.floodScopeModeRawValue]/[ChannelEntity.regionScope]) in one write, so callers
     * cannot persist a malformed combination. App-only — pushing the resolved scope to the radio
     * session is a separate step (see [com.meshcoretwo.services.connection.pushChannelFloodScope]).
     * Ported from `PersistenceStore.setChannelFloodScope`.
     */
    suspend fun setChannelFloodScope(channelId: UUID, floodScope: ChannelFloodScope) {
        val (mode, regionName) = ChannelFloodScopeStorage.decompose(floodScope)
        dao.fetchChannel(channelId)?.let { dao.update(it.copy(floodScopeModeRawValue = mode.rawValue, regionScope = regionName)) }
    }

    /** Every channel across [radioIDs], as a raw list — backup import's existing-row lookup. Ported from `fetchExistingChannels`. */
    suspend fun fetchExistingChannels(radioIDs: Set<UUID>): List<ChannelDto> {
        if (radioIDs.isEmpty()) return emptyList()
        return dao.fetchAll(radioIDs.toList()).map { it.toDto() }
    }

    /**
     * Reconciles backup channels against local channels by stable cryptographic identity
     * `(radioID, secret)`, treating the slot `index` as mere placement — see
     * [com.meshcoretwo.services.backup.resolveChannelPlacementIndex]'s doc for the placement rule
     * and [com.meshcoretwo.services.backup.ChannelBatchInsertResult]'s doc for what the remap maps
     * mean. Channels with an empty (all-zero) secret carry no stable identity, so they reconcile
     * by slot index instead, matching legacy behavior. Ported from `batchInsertChannels`.
     */
    suspend fun batchInsertChannels(
        dtos: List<ChannelDto>,
        radioIDs: Set<UUID>,
        maxChannelsByRadioID: Map<UUID, UByte> = emptyMap(),
    ): ChannelBatchInsertResult {
        val existingChannels = fetchExistingChannels(radioIDs)

        val localChannelsBySecret = mutableMapOf<UUID, MutableMap<String, ChannelDto>>()
        val occupiedIndicesByRadioID = mutableMapOf<UUID, MutableSet<UByte>>()
        val localChannelByIndex = mutableMapOf<UUID, MutableMap<UByte, ChannelDto>>()
        for (channel in existingChannels) {
            occupiedIndicesByRadioID.getOrPut(channel.radioID) { mutableSetOf() }.add(channel.index)
            localChannelByIndex.getOrPut(channel.radioID) { mutableMapOf() }[channel.index] = channel
            if (channelHasStableSecret(channel.secret)) {
                localChannelsBySecret.getOrPut(channel.radioID) { mutableMapOf() }[channel.secret.hexString] = channel
            }
        }

        var inserted = 0
        var skipped = 0
        var merged = 0
        var dropped = 0
        val channelIndexRemap = mutableMapOf<UUID, MutableMap<UByte, UByte>>()
        val droppedChannelIndices = mutableMapOf<UUID, MutableSet<UByte>>()
        val insertedLocalIndices = mutableMapOf<UUID, MutableSet<UByte>>()

        // Sort by (radioID, index) so free-slot assignment is deterministic regardless of the
        // envelope's array order.
        val orderedDTOs = dtos.sortedWith(compareBy({ it.radioID.toString() }, { it.index }))

        for (dto in orderedDTOs) {
            val radioID = dto.radioID

            if (channelHasStableSecret(dto.secret)) {
                val existing = localChannelsBySecret[radioID]?.get(dto.secret.hexString)
                if (existing != null) {
                    val (mergedDto, changed) = mergeChannelBackupMetadata(existing, dto)
                    if (changed) {
                        dao.update(mergedDto.toEntity())
                        localChannelByIndex.getOrPut(radioID) { mutableMapOf() }[existing.index] = mergedDto
                        localChannelsBySecret.getOrPut(radioID) { mutableMapOf() }[dto.secret.hexString] = mergedDto
                        merged++
                    }
                    skipped++
                    if (existing.index != dto.index) {
                        channelIndexRemap.getOrPut(radioID) { mutableMapOf() }[dto.index] = existing.index
                    }
                    continue
                }
            } else {
                // Empty-secret channels reconcile by slot (the public channel's slot is its identity).
                val existing = localChannelByIndex[radioID]?.get(dto.index)
                if (existing != null) {
                    val (mergedDto, changed) = mergeChannelBackupMetadata(existing, dto)
                    if (changed) {
                        dao.update(mergedDto.toEntity())
                        localChannelByIndex.getOrPut(radioID) { mutableMapOf() }[dto.index] = mergedDto
                        merged++
                    }
                    skipped++
                    continue
                }
            }

            val placementIndex = resolveChannelPlacementIndex(dto.index, occupiedIndicesByRadioID[radioID] ?: emptySet(), maxChannelsByRadioID[radioID])
            if (placementIndex == null) {
                dropped++
                droppedChannelIndices.getOrPut(radioID) { mutableSetOf() }.add(dto.index)
                continue
            }

            // Re-mint the surrogate id on insert so a backup channel can never upsert a live local
            // channel that shares its unique id — mirrors [DeviceStore.batchInsertDevices]. Channel
            // has no inbound foreign key, so the fresh id breaks no message/reaction linkage (those
            // key by channelIndex).
            val relocatedDto = if (placementIndex == dto.index) dto else dto.copy(index = placementIndex)
            val placedDto = relocatedDto.copy(id = UUID.randomUUID())
            dao.insert(placedDto.toEntity())
            occupiedIndicesByRadioID.getOrPut(radioID) { mutableSetOf() }.add(placementIndex)
            localChannelByIndex.getOrPut(radioID) { mutableMapOf() }[placementIndex] = placedDto
            if (channelHasStableSecret(placedDto.secret)) {
                localChannelsBySecret.getOrPut(radioID) { mutableMapOf() }[placedDto.secret.hexString] = placedDto
            }
            insertedLocalIndices.getOrPut(radioID) { mutableSetOf() }.add(placementIndex)
            if (placementIndex != dto.index) {
                channelIndexRemap.getOrPut(radioID) { mutableMapOf() }[dto.index] = placementIndex
            }
            inserted++
        }
        return ChannelBatchInsertResult(
            counts = PerTypeCounts(inserted = inserted, merged = merged, skipped = skipped, dropped = dropped),
            channelIndexRemap = channelIndexRemap,
            droppedChannelIndices = droppedChannelIndices,
            insertedLocalIndices = insertedLocalIndices,
        )
    }

    /**
     * Advances `lastMessageDate` toward the per-`(radioID, index)` max timestamp from a
     * just-completed backup import. Existing values are preserved when already newer. Ported from
     * `applyLastMessageDatesToChannels`.
     */
    suspend fun applyLastMessageDatesToChannels(maxDates: Map<UUID, Map<UByte, Instant>>) {
        if (maxDates.isEmpty()) return
        for ((radioID, perIndex) in maxDates) {
            for (channel in dao.fetchChannels(radioID)) {
                val latest = perIndex[channel.index.toUByte()] ?: continue
                if (channel.lastMessageDate == null || channel.lastMessageDate!! < latest) {
                    dao.update(channel.copy(lastMessageDate = latest))
                }
            }
        }
    }
}
