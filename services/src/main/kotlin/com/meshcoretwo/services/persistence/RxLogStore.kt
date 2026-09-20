// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.protocol.PayloadType
import com.meshcoretwo.protocol.RouteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** A batch decryption result to write back onto an [RxLogEntity]. Ported from the tuple parameter of `batchUpdateRxLogDecryption`. */
data class RxLogDecryptionUpdate(val id: UUID, val channelIndex: UByte?, val channelName: String?, val senderTimestamp: UInt?)

/** A batch region-resolution result to write back onto an [RxLogEntity]. Used by the region-reprocess sweep. */
data class RxLogRegionUpdate(val id: UUID, val regionScope: String?, val regionScopeMatches: List<String>)

/** Batching policy for [RxLogStore.saveRxLogEntry]. Ported from `RxLogPersisting.swift`'s `RxLogRetention` enum. */
object RxLogRetention {
    /** Rows kept after a prune pass. */
    const val KEEP_COUNT: Int = 1000

    /** Extra rows allowed before prune runs, evaluated once per flush (not per insert). */
    const val PRUNE_THRESHOLD: Int = 100

    /** Inserts between RxLog-initiated batch commits. */
    const val BATCH_SIZE: Int = 20

    /** Debounce for a partial batch, in milliseconds. */
    const val FLUSH_INTERVAL_MS: Long = 1000
}

/**
 * RX-log persistence, wrapping [RxLogDao] with the correlation-lookup, retention, and write-batching
 * logic Swift keeps in `PersistenceStore+Diagnostics.swift`'s RxLog section. Scoped to what
 * [com.meshcoretwo.services.rxlog.RxLogService]'s ported methods need — see that class's doc for
 * what's still deferred (region reprocess).
 *
 * **Write batching**, ported from Swift's `8c4eb521` ("perf(logs): batch RxLog saves"): every RF
 * packet the radio hears calls [saveRxLogEntry], and committing that as its own Room transaction
 * (implicit per-call fsync) was showing up as a per-packet disk-write signature at high RX volume.
 * [saveRxLogEntry] instead buffers the entry and commits [RxLogRetention.BATCH_SIZE] inserts (or
 * whatever is pending after [RxLogRetention.FLUSH_INTERVAL_MS]) in one [RxLogDao.insertAll] call —
 * matching Swift's `unsavedRxLogInsertCount`/`modelContext.save()` batching, minus the debug-only
 * fault-injection hooks (this port has no `#if DEBUG` fixture-injection convention).
 *
 * Every read below (`find*`/`fetch*`/[pruneRxLogEntries]/[batchUpdateRxLogDecryption]) merges the
 * unflushed buffer with the committed rows first — the Room equivalent of SwiftData's
 * `modelContext` seeing its own pending inserts in the same fetch, which
 * [com.meshcoretwo.services.rxlog.RxLogService.lookupPathData]'s [findRxLogEntry] call relies on:
 * a decrypted message and its RX-log entry both derive from the same physical receive, so
 * correlation routinely lands inside the same batch window.
 *
 * All buffer access is confined to a single-parallelism dispatcher (this port's usual
 * actor-replacement pattern — see [com.meshcoretwo.services.logging.DebugLogBuffer]'s class doc
 * for the fuller reentrancy rationale), so every public method wraps its whole body in one
 * `withContext(dispatcher)` — internal `Impl` helpers assume they're already confined and must
 * never re-enter that `withContext` themselves.
 */
class RxLogStore(private val database: MeshCoreDatabase) {
    private val dao get() = database.rxLogDao()
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var buffer: MutableList<RxLogDto> = mutableListOf()
    private var flushJob: Job? = null
    private var isFlushScheduled = false

    /** Saves a new RX-log entry. Buffered — see this class's doc — not necessarily an immediate insert. */
    suspend fun saveRxLogEntry(dto: RxLogDto) = withContext(dispatcher) { saveImpl(dto) }

    private fun saveImpl(dto: RxLogDto) {
        // A committed row never carries decodedText (see RxLogDto.decodedText's doc); scrub it
        // here so a pending-buffer read returns the same shape a DB round-trip would.
        buffer.add(if (dto.decodedText != null) dto.copy(decodedText = null) else dto)
        if (buffer.size >= RxLogRetention.BATCH_SIZE) flushNow() else scheduleFlush()
    }

    private fun scheduleFlush() {
        if (isFlushScheduled) return
        isFlushScheduled = true
        flushJob = scope.launch {
            delay(RxLogRetention.FLUSH_INTERVAL_MS)
            isFlushScheduled = false
            // Forget this job before flushing: flushBufferImpl() cancels flushJob, and cancelling
            // the very coroutine that is running the flush makes dao.insertAll throw
            // CancellationException with the buffer already swapped out, silently losing the batch.
            flushJob = null
            flushBufferImpl()
        }
    }

    private fun flushNow() {
        flushJob?.cancel()
        flushJob = null
        isFlushScheduled = false
        scope.launch { flushBufferImpl() }
    }

    /**
     * Commits any buffered inserts (if any) in one transaction and prunes every radio touched by
     * this batch. Ported from `flushPendingRxLogEntries()`. Call on disconnect/background so
     * entries queued at process death don't die with the process — see
     * [com.meshcoretwo.services.ServiceContainer.stopEventMonitoring]'s call site.
     */
    suspend fun flushPendingEntries() = withContext(dispatcher) { flushBufferImpl() }

    private suspend fun flushBufferImpl() {
        flushJob?.cancel()
        flushJob = null
        isFlushScheduled = false
        if (buffer.isEmpty()) return
        val toInsert = buffer
        buffer = mutableListOf()
        dao.insertAll(toInsert.map { it.toEntity() })
        for (radioID in toInsert.map { it.radioID }.distinct()) {
            prunePersistedImpl(radioID)
        }
    }

    /**
     * Finds an [RxLogEntity] matching an incoming message for path correlation: channel messages
     * by `(channelIndex, senderTimestamp)`, direct messages by `senderTimestamp` alone (scoped to
     * text-message payloads with no channel). Ported from `findRxLogEntry`.
     */
    suspend fun findRxLogEntry(radioID: UUID, channelIndex: UByte?, senderTimestamp: UInt): RxLogDto? = withContext(dispatcher) {
        val pendingMatches = buffer.filter { entry ->
            entry.radioID == radioID &&
                entry.senderTimestamp == senderTimestamp &&
                if (channelIndex != null) {
                    entry.channelIndex == channelIndex
                } else {
                    entry.channelIndex == null && entry.payloadType == PayloadType.TEXT_MESSAGE
                }
        }
        val dbMatch = if (channelIndex != null) {
            dao.findByChannelAndTimestamp(radioID, channelIndex.toInt(), senderTimestamp.toLong())
        } else {
            dao.findDmByTimestamp(radioID, PayloadType.TEXT_MESSAGE.value.toInt(), senderTimestamp.toLong())
        }?.toDto()
        (pendingMatches + listOfNotNull(dbMatch)).maxByOrNull { it.receivedAt }
    }

    /**
     * Every logged packet matching a channel message's `(channelIndex, senderTimestamp)`, oldest
     * first — unlike [findRxLogEntry], which keeps only the newest. Several rows can share that
     * pair when the same message reaches this radio over more than one flood path; the caller
     * tells them apart by decrypting and joining on the deduplication key
     * ([com.meshcoretwo.services.utilities.ChannelRXCorrelation]). Ported from `fetchRxLogEntries`.
     */
    suspend fun fetchRxLogEntries(radioID: UUID, channelIndex: UByte, senderTimestamp: UInt): List<RxLogDto> = withContext(dispatcher) {
        val pendingMatches = buffer.filter { it.radioID == radioID && it.channelIndex == channelIndex && it.senderTimestamp == senderTimestamp }
        val dbMatches = dao.fetchByChannelAndTimestamp(radioID, channelIndex.toInt(), senderTimestamp.toLong()).map { it.toDto() }
        (pendingMatches + dbMatches).sortedBy { it.receivedAt }
    }

    /**
     * Fallback DM lookup for when timestamp-based correlation fails (RX-log decryption hadn't
     * extracted the timestamp yet): matches the unencrypted sender-prefix byte at
     * `packetPayload[1]` within a recent receive-time window. Ported from
     * `findRxLogEntryBySenderPrefix`.
     */
    suspend fun findRxLogEntryBySenderPrefix(radioID: UUID, senderPrefixByte: UByte, receivedSince: Instant): RxLogDto? = withContext(dispatcher) {
        fun matchesPrefix(entry: RxLogDto) = entry.packetPayload.size >= 2 && entry.packetPayload[1].toUByte() == senderPrefixByte

        val pendingMatches = buffer.filter { entry ->
            entry.radioID == radioID &&
                entry.channelIndex == null &&
                entry.payloadType == PayloadType.TEXT_MESSAGE &&
                !entry.receivedAt.isBefore(receivedSince) &&
                matchesPrefix(entry)
        }
        val dbMatches = dao.findRecentDmCandidates(radioID, PayloadType.TEXT_MESSAGE.value.toInt(), receivedSince)
            .map { it.toDto() }
            .filter { matchesPrefix(it) }
        (pendingMatches + dbMatches).maxByOrNull { it.receivedAt }
    }

    /** Fetches entries with a given [status] received since [since], oldest first — the reprocess sweeps' candidate pool. */
    suspend fun fetchRecentEntriesByDecryptStatus(radioID: UUID, status: DecryptStatus, since: Instant): List<RxLogDto> = withContext(dispatcher) {
        val pendingMatches = buffer.filter { it.radioID == radioID && it.decryptStatus == status && !it.receivedAt.isBefore(since) }
        val dbEntries = dao.fetchRecentByDecryptStatus(radioID, status.rawValue, since).map { it.toDto() }
        (dbEntries + pendingMatches).sortedBy { it.receivedAt }
    }

    /** Fetches the most recent [limit] entries, newest first — the RX Log viewer's initial load. Ported from `fetchRxLogEntries`. */
    suspend fun fetchEntries(radioID: UUID, limit: Int = 1000): List<RxLogDto> = withContext(dispatcher) {
        val pendingMatches = buffer.filter { it.radioID == radioID }
        val dbEntries = dao.fetchRecent(radioID, limit).map { it.toDto() }
        (pendingMatches + dbEntries).sortedByDescending { it.receivedAt }.take(limit)
    }

    /**
     * Batch-applies successful re-decryptions: each row moves to [DecryptStatus.SUCCESS] with
     * its channel attribution and/or sender timestamp filled in. Ported from
     * `batchUpdateRxLogDecryption`. Updates a still-buffered entry in place rather than falling
     * through to a DB fetch that would miss it.
     */
    suspend fun batchUpdateRxLogDecryption(updates: List<RxLogDecryptionUpdate>) = withContext(dispatcher) {
        val byId = updates.associateBy { it.id }
        for (index in buffer.indices) {
            val entry = buffer[index]
            val update = byId[entry.id] ?: continue
            buffer[index] = entry.copy(
                channelIndex = update.channelIndex,
                channelName = update.channelName,
                decryptStatus = DecryptStatus.SUCCESS,
                senderTimestamp = update.senderTimestamp,
            )
        }
        val bufferedIds = buffer.map { it.id }.toSet()
        for (update in updates) {
            if (update.id in bufferedIds) continue
            val existing = dao.fetch(update.id) ?: continue
            dao.update(
                existing.copy(
                    channelIndex = update.channelIndex?.toInt(),
                    channelName = update.channelName,
                    decryptStatusRawValue = DecryptStatus.SUCCESS.rawValue,
                    senderTimestamp = update.senderTimestamp?.toLong(),
                ),
            )
        }
    }

    /**
     * Every retained entry whose route type carries a transport code, newest first — the region
     * reprocess sweep's candidate pool. Ported from `fetchEntriesWithTransportCode`. Unlike
     * [fetchRecentEntriesByDecryptStatus], there's no time-window cutoff: a `knownRegions` change
     * can make an hours-old entry newly resolvable, so the whole retained window is in scope, just
     * bounded by [limit].
     */
    suspend fun fetchEntriesWithTransportCode(radioID: UUID, limit: Int): List<RxLogDto> = withContext(dispatcher) {
        val pendingMatches = buffer.filter { it.radioID == radioID && it.routeType.hasTransportCode }
        val dbEntries = dao.fetchWithTransportCode(radioID, RouteType.TC_FLOOD.value.toInt(), RouteType.TC_DIRECT.value.toInt(), limit)
            .map { it.toDto() }
        val bufferedIds = pendingMatches.map { it.id }.toSet()
        (pendingMatches + dbEntries.filterNot { it.id in bufferedIds }).sortedByDescending { it.receivedAt }.take(limit)
    }

    /**
     * Batch-applies resolved regions onto [RxLogEntity] rows. Ported from
     * `batchUpdateRxLogRegion`. Updates a still-buffered entry in place rather than falling
     * through to a DB fetch that would miss it, same reasoning as [batchUpdateRxLogDecryption].
     */
    suspend fun batchUpdateRxLogRegion(updates: List<RxLogRegionUpdate>) = withContext(dispatcher) {
        val byId = updates.associateBy { it.id }
        for (index in buffer.indices) {
            val entry = buffer[index]
            val update = byId[entry.id] ?: continue
            buffer[index] = entry.copy(regionScope = update.regionScope, regionScopeMatches = update.regionScopeMatches)
        }
        val bufferedIds = buffer.map { it.id }.toSet()
        val dbUpdates = updates.filter { it.id !in bufferedIds }
        if (dbUpdates.isEmpty()) return@withContext
        val entities = dbUpdates.mapNotNull { update ->
            dao.fetch(update.id)?.copy(regionScope = update.regionScope, regionScopeMatches = update.regionScopeMatches.joinToString(","))
        }
        if (entities.isNotEmpty()) dao.updateAll(entities)
    }

    /**
     * Deletes the oldest entries once the log materially exceeds [keepCount] + [pruneThreshold],
     * bringing it back down to [keepCount]. Flushes the buffer first so a freshly-inserted burst
     * counts toward the threshold immediately. Ported from `pruneRxLogEntries`.
     */
    suspend fun pruneRxLogEntries(radioID: UUID, keepCount: Int = RxLogRetention.KEEP_COUNT, pruneThreshold: Int = RxLogRetention.PRUNE_THRESHOLD) =
        withContext(dispatcher) {
            flushBufferImpl()
            prunePersistedImpl(radioID, keepCount, pruneThreshold)
        }

    private suspend fun prunePersistedImpl(radioID: UUID, keepCount: Int = RxLogRetention.KEEP_COUNT, pruneThreshold: Int = RxLogRetention.PRUNE_THRESHOLD) {
        val count = dao.countForRadio(radioID)
        if (count <= keepCount + pruneThreshold) return
        dao.deleteOldest(radioID, count - keepCount)
    }

    /** Deletes every RX-log entry for a device, including anything still buffered for it. */
    suspend fun clearRxLogEntries(radioID: UUID) = withContext(dispatcher) {
        buffer.removeAll { it.radioID == radioID }
        dao.deleteAll(radioID)
    }

    /**
     * Flushes any pending buffer, then stops the background scope that schedules flushes. Call
     * before closing the underlying [MeshCoreDatabase] — a scheduled flush is a real-time timer
     * (not tied to any structured-concurrency scope a caller controls), so without this, one that
     * fires after the database closes can wedge Room's `ProcessLock` for every other database in
     * the process, not just this one. Production never closes the database, so this exists mainly
     * for tests that build a fresh in-memory database per test.
     */
    suspend fun shutdown() {
        flushPendingEntries()
        scope.cancel()
    }
}
