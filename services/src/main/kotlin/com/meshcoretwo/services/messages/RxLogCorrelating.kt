// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.services.persistence.RxLogDto
import java.util.UUID

/** The path-correlation result [RxLogCorrelating.lookupPathData] returns. Ported from `SyncCoordinator.RxLogLookupResult`, trimmed to what this port's [com.meshcoretwo.services.persistence.MessageDto] carries (no `packetHash` field — see [RxLogCorrelating]'s doc). */
data class RxLogPathData(
    val pathNodes: ByteArray?,
    val pathLength: UByte,
    val routeType: RouteType?,
    /** See [com.meshcoretwo.services.persistence.MessageDto.regionScope]'s doc. `null` when uncorrelated, same as [pathNodes]/[routeType]. */
    val regionScope: String? = null,
    /** See [com.meshcoretwo.services.persistence.MessageDto.regionScopeMatches]'s doc. */
    val regionScopeMatches: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RxLogPathData) return false
        return (pathNodes?.contentEquals(other.pathNodes ?: ByteArray(0)) ?: (other.pathNodes == null)) &&
            pathLength == other.pathLength &&
            routeType == other.routeType &&
            regionScope == other.regionScope &&
            regionScopeMatches == other.regionScopeMatches
    }

    override fun hashCode(): Int {
        var result = pathNodes?.contentHashCode() ?: 0
        result = 31 * result + pathLength.hashCode()
        result = 31 * result + (routeType?.hashCode() ?: 0)
        result = 31 * result + (regionScope?.hashCode() ?: 0)
        result = 31 * result + regionScopeMatches.hashCode()
        return result
    }
}

/**
 * Narrow interface [IncomingMessageService] uses to correlate an incoming message with the
 * logged RF packet it decrypted from, filling in [com.meshcoretwo.services.persistence.MessageDto.pathNodes]/
 * `pathLength`/`routeType`/`regionScope`/`regionScopeMatches`. Satisfied by `RxLogService` (in the
 * `rxlog` package) — same narrow-interface trick as [ReactionHandling] and
 * [IncomingMessageService]'s `pendingAdvertResolver`, so `messages` doesn't need to know the
 * `rxlog` package exists when a caller has no use for it.
 */
interface RxLogCorrelating {
    /**
     * Looks up path data for an incoming message. [defaultPathLength] is echoed back verbatim
     * (with `pathNodes = null`, `routeType = null`) when no correlated entry is found — this
     * makes the whole feature strictly additive: a miss reproduces exactly what
     * `IncomingMessageService` did before this interface existed.
     */
    suspend fun lookupPathData(
        radioID: UUID,
        channelIndex: UByte?,
        senderTimestamp: UInt,
        senderPublicKeyPrefix: ByteArray?,
        defaultPathLength: UByte,
        /**
         * The incoming channel message's content-based key. When supplied, channel correlation
         * decrypts every logged packet sharing `(channelIndex, senderTimestamp)` and takes the
         * *oldest* one whose plaintext hashes to this key, instead of the newest row matching
         * that pair — the newest may well be a different message, or a later flood copy whose
         * path isn't the one this message first arrived over. `null` (and every direct message)
         * keeps the pre-existing lookup. Ported from `lookupRxLogEntry`'s
         * `channelDeduplicationKey`.
         */
        channelDeduplicationKey: String? = null,
    ): RxLogPathData

    /**
     * Every logged packet carrying the same channel message, decrypted — the extra-flood-path
     * harvest's input. Fetch and decrypt live behind one call because the RX log never persists
     * plaintext (see [com.meshcoretwo.services.persistence.RxLogDto.decodedText]), so a caller
     * outside the `rxlog` package can't decrypt the rows itself. Ported from the
     * `fetchRxLogEntries` + `decodedEntries` pair in `harvestAndRefreshChannelMessage`.
     */
    suspend fun decodedChannelEntries(radioID: UUID, channelIndex: UByte, senderTimestamp: UInt): List<RxLogDto>
}
