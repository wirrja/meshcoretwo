// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.services.backup.BackupDedupKeys
import com.meshcoretwo.services.backup.PerTypeCounts
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/**
 * Discovered-node ("Discover" list) persistence, wrapping [DiscoveredNodeDao] with the upsert/
 * cap-eviction/pending-inbound-hop business logic Swift keeps in `PersistenceStore.swift`'s
 * "Discovered Nodes" section — mirrors [ContactStore]'s DAO/store split.
 *
 * Not yet wired to anything: no service calls [upsertDiscoveredNode]/[setInboundHopCount] yet
 * (`AdvertisementService.kt`'s class doc tracks that as the next step — this slice is persistence
 * only, same as `TracePathStore`/`TracePathService` landing before `TracePathViewModel`).
 *
 * [pendingInboundHops] mirrors Swift's actor-isolated buffer for inbound hop counts heard via the
 * RX log before the matching node row exists (the firmware emits the 0x88 RX packet before the
 * 0x80 advert push that creates the row) — guarded by [pendingHopsMutex] instead of actor
 * isolation, this port's established actor→coroutine convention (see
 * [com.meshcoretwo.services.reactions.MessageLRUCache]).
 */
class DiscoveredNodeStore(private val database: MeshCoreDatabase) {
    private val dao get() = database.discoveredNodeDao()

    private data class PendingHopKey(val radioID: UUID, val publicKey: List<Byte>)
    private data class PendingHop(val hopCount: Int, val advertTimestamp: UInt?)

    private val pendingHopsMutex = Mutex()
    private val pendingInboundHops = mutableMapOf<PendingHopKey, PendingHop>()

    /**
     * Inserts or updates a discovered node from a freshly-heard advertisement, matching by
     * `(radioID, publicKey)`. Re-stamps [DiscoveredNodeDto] recency on every call, even for an
     * already-known node. Returns the row and whether it was newly created.
     */
    suspend fun upsertDiscoveredNode(radioID: UUID, contact: MeshContact, now: Instant = Instant.now()): Pair<DiscoveredNodeDto, Boolean> {
        val existing = dao.fetch(radioID, contact.publicKey)
        val isNew = existing == null
        var row = existing?.updatedFrom(contact, now)
            ?: DiscoveredNodeEntity.fromMeshContact(UUID.randomUUID(), radioID, contact, now)

        if (isNew) {
            dao.insert(row)
            enforceCap(radioID)
        } else {
            dao.update(row)
        }

        val pending = takePendingInboundHop(row.radioID, row.publicKey)
        if (pending != null) {
            val adopted = adoptInboundHop(row.inboundHopCount, row.inboundHopAdvertTimestamp?.toUInt(), pending.hopCount, pending.advertTimestamp)
            if (adopted != null) {
                row = row.copy(inboundHopCount = adopted.first, inboundHopAdvertTimestamp = adopted.second?.toLong())
                dao.update(row)
            }
        }

        return row.toDto() to isNew
    }

    /**
     * Stamps the inbound advert hop count onto an existing discovered node, keyed by public key.
     * No-op if no matching row exists yet — the pair is buffered so the next advert upsert for
     * this key drains it (see [bufferPendingInboundHop]).
     */
    suspend fun setInboundHopCount(radioID: UUID, publicKey: ByteArray, hopCount: Int, advertTimestamp: UInt?) {
        val node = dao.fetch(radioID, publicKey)
        if (node == null) {
            bufferPendingInboundHop(radioID, publicKey, hopCount, advertTimestamp)
            return
        }
        val adopted = adoptInboundHop(node.inboundHopCount, node.inboundHopAdvertTimestamp?.toUInt(), hopCount, advertTimestamp) ?: return
        dao.update(node.copy(inboundHopCount = adopted.first, inboundHopAdvertTimestamp = adopted.second?.toLong()))
    }

    /** Fetch all discovered nodes for a device. */
    suspend fun fetchDiscoveredNodes(radioID: UUID): List<DiscoveredNodeDto> = dao.fetchAll(radioID).map { it.toDto() }

    /** Every discovered node across every device — backup export's unscoped fetch. Ported from `fetchAllDiscoveredNodes`. */
    suspend fun fetchAllDiscoveredNodes(): List<DiscoveredNodeDto> = dao.fetchAll().map { it.toDto() }

    /** Deletes a discovered node by its local id (no-op if it doesn't exist). */
    suspend fun deleteDiscoveredNode(id: UUID) = dao.deleteById(id)

    /** Clears every discovered node for a device. */
    suspend fun clearDiscoveredNodes(radioID: UUID) = dao.deleteAll(radioID)

    /** Evicts the oldest-heard rows past [MAX_DISCOVERED_NODES] for a device. */
    private suspend fun enforceCap(radioID: UUID) {
        val count = dao.count(radioID)
        if (count > MAX_DISCOVERED_NODES) {
            dao.fetchOldest(radioID, count - MAX_DISCOVERED_NODES).forEach { dao.delete(it) }
        }
    }

    /**
     * Stash an inbound hop count heard before its node row exists. Applies the same [adoptInboundHop]
     * rule as the live-row path. Evicts an arbitrary entry past [MAX_PENDING_INBOUND_HOPS] so a
     * flood of never-resolved adverts can't grow this without bound.
     */
    private suspend fun bufferPendingInboundHop(radioID: UUID, publicKey: ByteArray, hopCount: Int, advertTimestamp: UInt?) {
        pendingHopsMutex.withLock {
            val key = PendingHopKey(radioID, publicKey.toList())
            val existing = pendingInboundHops[key]
            val adopted = adoptInboundHop(existing?.hopCount, existing?.advertTimestamp, hopCount, advertTimestamp) ?: return@withLock
            if (existing == null && pendingInboundHops.size >= MAX_PENDING_INBOUND_HOPS) {
                pendingInboundHops.keys.firstOrNull()?.let { pendingInboundHops.remove(it) }
            }
            pendingInboundHops[key] = PendingHop(adopted.first, adopted.second)
        }
    }

    private suspend fun takePendingInboundHop(radioID: UUID, publicKey: ByteArray): PendingHop? =
        pendingHopsMutex.withLock { pendingInboundHops.remove(PendingHopKey(radioID, publicKey.toList())) }

    /** Every discovered-node key across [radioIDs] — backup import's existing-row lookup. Ported from `existingDiscoveredNodeKeys`. */
    suspend fun existingDiscoveredNodeKeys(radioIDs: Set<UUID>): Set<String> {
        if (radioIDs.isEmpty()) return emptySet()
        return dao.fetchAll(radioIDs.toList()).mapTo(mutableSetOf()) { BackupDedupKeys.discoveredNodeKey(it.radioID, it.publicKey) }
    }

    /**
     * Drops oldest-by-[DiscoveredNodeDto.lastHeard] new (not-already-local) nodes past
     * [MAX_DISCOVERED_NODES] per radio, then inserts the rest (re-minting `id`, since dedup is
     * `(radioID, publicKey)` and nothing keys off the surrogate id — mirrors
     * [DeviceStore.batchInsertDevices]/[ChannelStore.batchInsertChannels]). Skips
     * `sanitizeDiscoveredNodeForImport`'s field-shape validation (public-key-size rejection,
     * name/out-path truncation, invalid-lat/lon reset) — this port only ever imports its own
     * export, never an untrusted third-party file, so that hardening is deferred rather than
     * ported speculatively; revisit if/when import ever accepts a file this app didn't produce.
     * Ported from `batchInsertDiscoveredNodes`.
     */
    suspend fun batchInsertDiscoveredNodes(dtos: List<DiscoveredNodeDto>, existingKeys: Set<String>): PerTypeCounts {
        val newByRadio = mutableMapOf<UUID, MutableList<DiscoveredNodeDto>>()
        for (dto in dtos) {
            if (BackupDedupKeys.discoveredNodeKey(dto.radioID, dto.publicKey) in existingKeys) continue
            newByRadio.getOrPut(dto.radioID) { mutableListOf() }.add(dto)
        }
        val dropped = mutableSetOf<UUID>()
        for ((radioID, incoming) in newByRadio) {
            val room = maxOf(0, MAX_DISCOVERED_NODES - dao.count(radioID))
            if (incoming.size <= room) continue
            dropped += incoming.sortedByDescending { it.lastHeard }.drop(room).map { it.id }
        }

        val knownKeys = existingKeys.toMutableSet()
        var inserted = 0
        var skipped = 0
        for (dto in dtos) {
            if (dto.id in dropped) continue
            val key = BackupDedupKeys.discoveredNodeKey(dto.radioID, dto.publicKey)
            if (!knownKeys.add(key)) {
                skipped++
                continue
            }
            dao.insert(dto.copy(id = UUID.randomUUID()).toEntity())
            inserted++
        }
        return PerTypeCounts(inserted = inserted, skipped = skipped, dropped = dropped.size)
    }

    companion object {
        /** Per-radio cap for the discover list. Matches `PersistenceStore.maxDiscoveredNodes`. */
        const val MAX_DISCOVERED_NODES = 1000
        private const val MAX_PENDING_INBOUND_HOPS = 256
    }
}
