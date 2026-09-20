// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.services.backup.BackupDedupKeys
import com.meshcoretwo.services.backup.PerTypeCounts
import java.time.Instant
import java.util.UUID

/**
 * Persistence for saved trace paths and their run history — the Room counterpart of Swift's
 * `PersistenceStore` conformance to `TracePathPersisting`. Scoped to exactly the six operations
 * that protocol declares; the future `TracePathViewModel` port drives everything else (path
 * building, trace execution/correlation, hop-to-contact-name resolution) from `app`, same as Swift
 * keeps that out of the persistence actor.
 */
class TracePathStore(private val database: MeshCoreDatabase) {
    private val pathDao get() = database.tracePathDao()
    private val runDao get() = database.tracePathRunDao()

    /** Fetches every saved path for a device, newest first. Ported from `fetchSavedTracePaths`. */
    suspend fun fetchSavedTracePaths(radioID: UUID): List<TracePathDto> =
        pathDao.fetchForRadio(radioID).map { entity -> entity.toDto(runDao.fetchForPath(entity.id).map { it.toDto() }) }

    /** Every saved path across every device, with its run history — backup export's unscoped fetch. Ported from `fetchAllSavedTracePaths`. */
    suspend fun fetchAllSavedTracePaths(): List<TracePathDto> =
        pathDao.fetchAll().map { entity -> entity.toDto(runDao.fetchForPath(entity.id).map { it.toDto() }) }

    /** Fetches a single saved path with its runs, or null if it doesn't exist. Ported from `fetchSavedTracePath`. */
    suspend fun fetchSavedTracePath(id: UUID): TracePathDto? {
        val entity = pathDao.fetch(id) ?: return null
        return entity.toDto(runDao.fetchForPath(id).map { it.toDto() })
    }

    /**
     * Creates a new saved path, optionally recording an [initialRun] alongside it. Ported from
     * `createSavedTracePath`.
     */
    suspend fun createSavedTracePath(
        radioID: UUID,
        name: String,
        pathBytes: ByteArray,
        hashSize: Int,
        initialRun: TracePathRunDto?,
        id: UUID = UUID.randomUUID(),
        createdDate: Instant = Instant.now(),
    ): TracePathDto {
        val entity = TracePathEntity(id = id, radioID = radioID, name = name, pathBytes = pathBytes, hashSize = hashSize, createdDate = createdDate)
        pathDao.insert(entity)
        if (initialRun != null) runDao.insert(initialRun.toEntity(id))
        return entity.toDto(if (initialRun != null) listOf(initialRun) else emptyList())
    }

    /** Renames a saved path. A no-op if [id] doesn't exist. Ported from `updateSavedTracePathName`. */
    suspend fun updateSavedTracePathName(id: UUID, name: String) {
        val entity = pathDao.fetch(id) ?: return
        pathDao.update(entity.copy(name = name))
    }

    /**
     * Deletes a saved path and every run in its history. Room has no cascading FK here (see
     * [TracePathEntity]'s class doc), so both tables are cleared explicitly, runs first. Ported
     * from `deleteSavedTracePath`.
     */
    suspend fun deleteSavedTracePath(id: UUID) {
        val entity = pathDao.fetch(id) ?: return
        runDao.deleteForPath(id)
        pathDao.delete(entity)
    }

    /** Appends a run to a saved path's history. Ported from `appendTracePathRun`. */
    suspend fun appendTracePathRun(pathID: UUID, run: TracePathRunDto) = runDao.insert(run.toEntity(pathID))

    /**
     * Reconciles backup saved paths against local ones by `(radioID, pathBytes, hashSize)`: a
     * match appends any run whose `id` hasn't been seen anywhere yet (run ids are globally
     * unique, so dedup is store-wide, not per-path); anything else inserts as a new path (keeping
     * its backup `id` — unlike [ChannelStore.batchInsertChannels]/[DeviceStore.batchInsertDevices],
     * nothing else keys off it that a re-mint would need to protect). Ported from
     * `batchInsertSavedTracePaths`.
     */
    suspend fun batchInsertSavedTracePaths(dtos: List<TracePathDto>): PerTypeCounts {
        if (dtos.isEmpty()) return PerTypeCounts.ZERO

        var inserted = 0
        var skipped = 0
        var merged = 0

        val existingPathsByKey = dtos.map { it.radioID }.distinct()
            .flatMap { pathDao.fetchForRadio(it) }
            .associateByTo(mutableMapOf()) { BackupDedupKeys.savedTracePathKey(it.radioID, it.pathBytes, it.hashSize) }
        val seenRunIds = runDao.fetchAllIds().toMutableSet()

        for (dto in dtos) {
            val key = BackupDedupKeys.savedTracePathKey(dto.radioID, dto.pathBytes, dto.hashSize)
            val existingPath = existingPathsByKey[key]
            if (existingPath != null) {
                skipped++
                var appendedRun = false
                for (runDto in dto.runs) {
                    if (!seenRunIds.add(runDto.id)) continue
                    runDao.insert(runDto.toEntity(existingPath.id))
                    appendedRun = true
                }
                if (appendedRun) merged++
                continue
            }

            val pathEntity = dto.toEntity()
            pathDao.insert(pathEntity)
            for (runDto in dto.runs) {
                if (!seenRunIds.add(runDto.id)) continue
                runDao.insert(runDto.toEntity(dto.id))
            }
            existingPathsByKey[key] = pathEntity
            inserted++
        }
        return PerTypeCounts(inserted = inserted, merged = merged, skipped = skipped)
    }
}
