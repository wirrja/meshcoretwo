// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.time.Instant
import java.util.UUID

/** Room DAO for [RxLogEntity] — dumb CRUD only, matching this module's other DAOs. */
@Dao
interface RxLogDao {
    @Insert
    suspend fun insert(entry: RxLogEntity)

    /** Batch insert — Room wraps a collection [Insert] in a single transaction, so this is one commit for the whole list. */
    @Insert
    suspend fun insertAll(entries: List<RxLogEntity>)

    @Update
    suspend fun update(entry: RxLogEntity)

    /** Batch update — Room wraps a collection [Update] in a single transaction, same as [insertAll]. Used by the region-reprocess sweep. */
    @Update
    suspend fun updateAll(entries: List<RxLogEntity>)

    @Query("SELECT * FROM rx_log_entries WHERE id = :id LIMIT 1")
    suspend fun fetch(id: UUID): RxLogEntity?

    @Query(
        "SELECT * FROM rx_log_entries WHERE radioID = :radioID AND channelIndex = :channelIndex AND senderTimestamp = :senderTimestamp " +
            "ORDER BY receivedAt DESC LIMIT 1",
    )
    suspend fun findByChannelAndTimestamp(radioID: UUID, channelIndex: Int, senderTimestamp: Long): RxLogEntity?

    /** Same predicate as [findByChannelAndTimestamp] with no limit, oldest first — the extra-flood-path harvest's candidate pool. Ported from `fetchRxLogEntries`. */
    @Query(
        "SELECT * FROM rx_log_entries WHERE radioID = :radioID AND channelIndex = :channelIndex AND senderTimestamp = :senderTimestamp " +
            "ORDER BY receivedAt ASC",
    )
    suspend fun fetchByChannelAndTimestamp(radioID: UUID, channelIndex: Int, senderTimestamp: Long): List<RxLogEntity>

    @Query(
        "SELECT * FROM rx_log_entries WHERE radioID = :radioID AND channelIndex IS NULL " +
            "AND payloadTypeRawValue = :textMessageType AND senderTimestamp = :senderTimestamp " +
            "ORDER BY receivedAt DESC LIMIT 1",
    )
    suspend fun findDmByTimestamp(radioID: UUID, textMessageType: Int, senderTimestamp: Long): RxLogEntity?

    @Query(
        "SELECT * FROM rx_log_entries WHERE radioID = :radioID AND channelIndex IS NULL " +
            "AND payloadTypeRawValue = :textMessageType AND receivedAt >= :since " +
            "ORDER BY receivedAt DESC LIMIT 20",
    )
    suspend fun findRecentDmCandidates(radioID: UUID, textMessageType: Int, since: Instant): List<RxLogEntity>

    @Query(
        "SELECT * FROM rx_log_entries WHERE radioID = :radioID AND decryptStatusRawValue = :decryptStatusRawValue AND receivedAt >= :since " +
            "ORDER BY receivedAt ASC",
    )
    suspend fun fetchRecentByDecryptStatus(radioID: UUID, decryptStatusRawValue: Int, since: Instant): List<RxLogEntity>

    @Query("SELECT * FROM rx_log_entries WHERE radioID = :radioID ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun fetchRecent(radioID: UUID, limit: Int): List<RxLogEntity>

    /**
     * Every retained entry whose route type carries a transport code (`TC_FLOOD`/`TC_DIRECT` —
     * see [com.meshcoretwo.protocol.RouteType.hasTransportCode]), newest first — the region
     * reprocess sweep's candidate pool. [limit] mirrors iOS's `regionReprocessFetchLimit`
     * (retention `keepCount` + `pruneThreshold`, see [RxLogRetention]) so the sweep never reads
     * more rows than a prune pass would ever retain.
     */
    @Query(
        "SELECT * FROM rx_log_entries WHERE radioID = :radioID AND routeTypeRawValue IN (:tcFloodValue, :tcDirectValue) " +
            "ORDER BY receivedAt DESC LIMIT :limit",
    )
    suspend fun fetchWithTransportCode(radioID: UUID, tcFloodValue: Int, tcDirectValue: Int, limit: Int): List<RxLogEntity>

    @Query("SELECT COUNT(*) FROM rx_log_entries WHERE radioID = :radioID")
    suspend fun countForRadio(radioID: UUID): Int

    @Query("DELETE FROM rx_log_entries WHERE id IN (SELECT id FROM rx_log_entries WHERE radioID = :radioID ORDER BY receivedAt ASC LIMIT :n)")
    suspend fun deleteOldest(radioID: UUID, n: Int)

    @Query("DELETE FROM rx_log_entries WHERE radioID = :radioID")
    suspend fun deleteAll(radioID: UUID)
}
