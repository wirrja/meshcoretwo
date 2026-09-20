// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import kotlin.math.max
import kotlin.math.min

/**
 * Sanitizes the firmware `suggested_timeout_ms` hint used by trace, ping, and path-discovery
 * waits. Scales the hint for slack and clamps it into a per-use-case band. A missing hint (0)
 * uses the profile default so the wait does not expire immediately. Ported from
 * `FirmwareSuggestedTimeout.swift` (`MC1/Utilities`).
 *
 * Only [Profile.FLOOD] has a caller so far ([TracePathViewModel]'s trace/batch-trace execution);
 * [Profile.ZERO_HOP] and the path-discovery helpers are ported alongside it since they're the
 * same small Swift file, ready for the future ping (`PingHelper.swift`, not yet ported) and
 * Discover Path ports.
 */
object FirmwareSuggestedTimeout {
    private const val MULTIPLIER = 1.2
    private const val MILLISECONDS_PER_SECOND = 1000.0

    /**
     * Per-use-case bounds. A single-neighbor round trip and a mesh-wide flood need different
     * bands, so callers pick the profile that matches their send.
     */
    data class Profile(
        val minimumSeconds: Double,
        val defaultSeconds: Double,
        val maximumSeconds: Double,
        /**
         * Fixed slack added to the scaled hint before clamping. The hint is based on outbound
         * airtime; the response leg re-crosses the mesh with delays the estimate does not
         * include.
         */
        val graceSeconds: Double,
    ) {
        companion object {
            /**
             * Single-neighbor ping to a direct contact. Honors a small valid hint rather than
             * inflating a fast link to a slow-link default.
             */
            val ZERO_HOP = Profile(minimumSeconds = 1.0, defaultSeconds = 5.0, maximumSeconds = 30.0, graceSeconds = 0.0)

            /**
             * Flood path discovery and multi-hop traces. Flood `est_timeout` is outbound airtime
             * only and hop-blind; grace covers return-leg jitter.
             */
            val FLOOD = Profile(minimumSeconds = 5.0, defaultSeconds = 30.0, maximumSeconds = 60.0, graceSeconds = 8.0)
        }
    }

    /**
     * Floor for Discover Path overall wait. Flood `est_timeout` underestimates multi-hop return,
     * so small hints still get a useful budget.
     */
    const val PATH_DISCOVERY_MINIMUM_OVERALL_SECONDS = 20.0

    /**
     * Retransmit spacing multiplier on firmware est. Companion holds one `pending_discovery` tag
     * and replaces it on each send; headroom 2 leaves room for a first reply before the next
     * resend.
     */
    const val PATH_DISCOVERY_RETRANSMIT_RTT_HEADROOM = 2

    /**
     * Minimum spacing between path-discovery resends, in milliseconds. Flood is expensive; never
     * pace faster than this even when 2x est is shorter.
     */
    const val PATH_DISCOVERY_RETRANSMIT_FLOOR_MS = 5_000L

    /**
     * Scaled hint before clamping. Used for logging the raw estimate next to the value actually
     * applied.
     */
    fun candidateSeconds(suggestedTimeoutMs: UInt): Double = suggestedTimeoutMs.toDouble() / MILLISECONDS_PER_SECOND * MULTIPLIER

    /**
     * Scaled hint plus profile grace, clamped into the profile band. A missing hint (0) yields
     * the profile default.
     */
    fun sanitizedSeconds(suggestedTimeoutMs: UInt, profile: Profile): Double {
        if (suggestedTimeoutMs <= 0u) return profile.defaultSeconds
        val candidate = candidateSeconds(suggestedTimeoutMs) + profile.graceSeconds
        return min(max(candidate, profile.minimumSeconds), profile.maximumSeconds)
    }

    /** Discover Path overall wait: flood-sanitized hint, at least [PATH_DISCOVERY_MINIMUM_OVERALL_SECONDS]. */
    fun pathDiscoverySeconds(suggestedTimeoutMs: UInt): Double =
        max(PATH_DISCOVERY_MINIMUM_OVERALL_SECONDS, sanitizedSeconds(suggestedTimeoutMs, Profile.FLOOD))

    /**
     * Spacing between path-discovery resends in milliseconds, or `null` when firmware gave no
     * hint (single send for the whole budget).
     */
    fun pathDiscoveryRetransmitIntervalMs(suggestedTimeoutMs: UInt): Long? {
        if (suggestedTimeoutMs <= 0u) return null
        val headroomed = suggestedTimeoutMs.toLong() * PATH_DISCOVERY_RETRANSMIT_RTT_HEADROOM
        return max(PATH_DISCOVERY_RETRANSMIT_FLOOR_MS, headroomed)
    }
}
