// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

/**
 * Tuning for [withPoolBackoff], which absorbs short bursts of firmware pool-exhaustion errors
 * before re-throwing. Ported from `PoolBackoffConfig` (`MessageServiceConfig.swift`).
 */
data class PoolBackoffConfig(
    /** Maximum in-loop retries before re-throwing the transient device error. */
    val attemptCap: Int = 3,
    /** Delay for the first in-loop retry, in milliseconds (multiplied by [exponentBase] for later attempts, then jittered). */
    val baseDelayMs: Long = 500,
    /** Exponential growth factor applied to [baseDelayMs] per attempt. */
    val exponentBase: Double = 2.0,
    /** Multiplicative jitter envelope sampled per retry. */
    val jitterRange: ClosedFloatingPointRange<Double> = 0.8..1.2,
)

/**
 * Configuration for [MessageService]. Ported from `MessageServiceConfig.swift`. Two fields are
 * still trimmed — `floodFallbackOnRetry`/`triggerPathDiscoveryAfterFlood` — because they're dead
 * even on the Swift side: declared on the struct but never read anywhere in
 * `MessageService+SendDM.swift`/`+SendChannel.swift`/`+SendHelpers.swift` (grep-verified), so
 * porting them would be speculative surface with no behavior behind it.
 */
data class MessageServiceConfig(
    /**
     * Floor and post-loop grace for the give-up deadline (milliseconds) on fast presets. The
     * effective deadline for each pending entry is `max(ackGiveUpWindowMs, PendingAck.timeoutMs)`.
     */
    val ackGiveUpWindowMs: Long = 30_000,
    /** Tuning for the in-loop pool-exhaustion backoff ([withPoolBackoff]). */
    val poolBackoff: PoolBackoffConfig = PoolBackoffConfig(),
    /** Maximum total send attempts for automatic retry (direct + flood combined). */
    val maxAttempts: Int = 5,
    /** Maximum attempts to make after switching to flood routing. */
    val maxFloodAttempts: Int = 1,
    /** Number of direct attempts before switching to flood routing. */
    val floodAfter: Int = 4,
    /** Minimum per-attempt ACK wait, in milliseconds (floor for the device-suggested timeout). */
    val minTimeoutMs: Long = 0,
) {
    init {
        // 5 = 4 direct + 1 flood. AckCodeBuilder.expectedAck documents why attempt indices
        // through 4 stay ACK-unambiguous; past that the cap bounds airtime.
        require(maxAttempts <= 5) { "maxAttempts must be <= 5 (4 direct + 1 flood)" }
    }
}
