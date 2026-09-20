// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.MessageSentInfo

/**
 * Ported from `RemoteOperationTimeoutPolicy.swift`. `defaultCLITimeout`/`fireAndForgetCLI` were
 * originally dropped here (both `public` in Swift but unreferenced at the time — `sendCLICommand`'s
 * default timeout was a literal `10` on both sides). [FIRE_AND_FORGET_CLI_MS] came back with the
 * "Route incoming CLI messages" + `NodeCLIViewModel` slices — see [FIRE_AND_FORGET_CLI_MS]'s doc;
 * `defaultCLITimeout` stays a literal `10_000L` default parameter value, matching Swift's own
 * `sendCommand`/`sendRawCommand` signatures rather than a named constant reference. All durations
 * are milliseconds ([Long]) rather than Swift's `Duration`, matching this codebase's established
 * convention (see `MessageServiceConfig`).
 */
object RemoteOperationTimeoutPolicy {
    private const val FIRMWARE_ROUND_TRIP_MULTIPLIER = 2
    const val LOGIN_MAXIMUM_MS = 20_000L

    /** Floor for login retransmit spacing while waiting for `loginSuccess`. Raised to firmware `suggestedTimeoutMs` when larger. */
    const val LOGIN_RETRANSMIT_INTERVAL_MS = 1_000L

    /** Outer cap for remote binary/status/telemetry waits: one exchange plus a small BLE margin. */
    const val BINARY_MAXIMUM_MS = 45_000L

    const val CLI_MAXIMUM_MS = 15_000L
    const val POLL_INTERVAL_MS = 500L

    /**
     * Timeout for a command whose node never replies (`reboot`), used by `app`'s
     * `NodeCLIViewModel` so a caller can treat the resulting [RemoteNodeError.Timeout] as success
     * rather than waiting the full [CLI_MAXIMUM_MS]. Ported from
     * `RemoteOperationTimeoutPolicy.fireAndForgetCLI` (2 seconds).
     */
    const val FIRE_AND_FORGET_CLI_MS = 2_000L

    private fun firmwareRoundTripTimeoutMs(sentInfo: MessageSentInfo): Long =
        sentInfo.suggestedTimeoutMs.toLong() * FIRMWARE_ROUND_TRIP_MULTIPLIER

    fun loginTimeoutMs(sentInfo: MessageSentInfo, pathLength: UByte): Long =
        minOf(maxOf(firmwareRoundTripTimeoutMs(sentInfo), LoginTimeoutConfig.timeoutMs(pathLength)), LOGIN_MAXIMUM_MS)

    fun cliTimeoutMs(sentInfo: MessageSentInfo, requestedTimeoutMs: Long): Long =
        minOf(maxOf(requestedTimeoutMs, firmwareRoundTripTimeoutMs(sentInfo)), CLI_MAXIMUM_MS)
}
