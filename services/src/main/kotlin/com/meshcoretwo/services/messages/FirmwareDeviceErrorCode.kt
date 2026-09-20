// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

/**
 * Firmware device-error codes the send classifier treats as transient. Ported from
 * `FirmwareDeviceErrorCode.swift`.
 *
 * These are MC1's retry-policy interpretations of [com.meshcoretwo.protocol.MeshCoreError.DeviceError]
 * values the radio surfaces. They live here (not in `protocol`) because the transient-vs-terminal
 * taxonomy is an app send-queue policy, not a protocol-level constant — even though the byte
 * values happen to coincide with [com.meshcoretwo.protocol.ErrorCode].
 */
object FirmwareDeviceErrorCode {
    /** `TABLE_FULL` on the direct-message path: the radio's outbound DM pool is briefly exhausted. */
    const val DIRECT_MESSAGE_TABLE_FULL: UByte = 3u

    /** `NOT_FOUND` on the channel broadcast path: pool exhaustion (transient) or a stale channel index (terminal). */
    const val CHANNEL_MESSAGE_NOT_FOUND: UByte = 2u

    /**
     * `RESP_CODE_NO_MORE_MESSAGES` on the remote-node status/telemetry request path: the
     * companion radio's offline-message queue is empty, meaning the awaited repeater/room reply
     * hasn't arrived yet. Used by `RoomStatusViewModel`'s (and future `RepeaterStatusViewModel`'s)
     * section-request retry loop, not the send queue — added alongside the "RoomStatusScreen"
     * slice, this constant was previously not ported since nothing needed it yet.
     */
    const val REMOTE_NODE_NO_RESPONSE_YET: UByte = 10u
}
