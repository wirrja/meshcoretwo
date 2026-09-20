// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rxlog

import com.meshcoretwo.services.persistence.RxLogDto

/**
 * Narrow seam [RxLogService] calls into after persisting a decrypted RX-log entry, so this
 * package doesn't need to know about [com.meshcoretwo.services.repeats.HeardRepeatsService] —
 * the same narrow-interface pattern as
 * [com.meshcoretwo.services.messages.RxLogCorrelating]/[com.meshcoretwo.services.messages.ReactionHandling],
 * just owned by the caller's package instead of the callee's since here the dependency points the
 * other way (rxlog calling out, not messages calling in).
 */
interface HeardRepeatProcessing {
    /**
     * Checks whether [entry] (which must carry a non-null [RxLogDto.decodedText]) echoes a sent
     * channel message, and if so records a heard repeat. Returns the updated heard-repeat count,
     * or `null` if no match was found. Ported from `HeardRepeatsService.processForRepeats`.
     */
    suspend fun processForRepeats(entry: RxLogDto): Int?
}
