// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.RxLogDto
import java.time.Instant
import java.util.UUID

/**
 * Narrow interface [IncomingMessageService] uses to attach *extra* observations of an incoming
 * message — the same packet reaching this radio again over a different flood path — to the
 * message row that already exists. Satisfied by
 * `com.meshcoretwo.services.repeats.HeardRepeatsService`, same narrow-interface trick as
 * [RxLogCorrelating]/[ReactionHandling] so `messages` doesn't need to know the `repeats` package
 * exists when a caller has no use for it (and so this stays strictly additive: a `null` harvester
 * reproduces the pre-existing "drop the duplicate" behavior exactly).
 *
 * Ported from the `dependencies.heardRepeatsService` calls
 * `SyncCoordinator+MessageHandlers.swift` makes in `recordArrivalAndSkipDuplicate` and
 * `harvestAndRefreshChannelMessage`.
 */
interface IncomingPathHarvesting {
    /**
     * Records one distinct extra arrival of [message]. Ported from `recordDistinctPathIfNeeded`.
     *
     * A message whose own path is still unknown adopts [pathNodes] instead of gaining an extra —
     * `null` means "not correlated", not "0 hops". A path identical to the message's own, or one
     * already recorded, is ignored.
     *
     * @return The message's new arrival count, or `null` when nothing was recorded.
     */
    suspend fun recordDistinctPathIfNeeded(
        message: MessageDto,
        pathNodes: ByteArray,
        pathLength: UByte,
        snr: Double?,
        rssi: Int?,
        receivedAt: Instant,
        rxLogEntryID: UUID?,
    ): Int?

    /**
     * Records every distinct path among [decodedCandidates] that belongs to [message] — the
     * copies that reached the radio *before* the message itself was saved, which no live
     * duplicate-drop could have caught. Ported from `harvestIncomingPaths`.
     */
    suspend fun harvestIncomingPaths(message: MessageDto, decodedCandidates: List<RxLogDto>)
}
