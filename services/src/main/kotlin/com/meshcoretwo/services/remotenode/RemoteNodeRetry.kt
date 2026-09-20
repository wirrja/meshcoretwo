// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.services.diagnostics.BinaryProtocolError
import com.meshcoretwo.services.messages.FirmwareDeviceErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Transient-retry machinery for remote-node section requests (status/telemetry/neighbours/owner
 * info), shared by `RoomStatusViewModel` and `RepeaterStatusViewModel`. Ported from the retry half
 * of `NodeStatusViewModel.swift`'s `performWithTransientRetries`/`isTransientError` — the display-
 * formatter half of that Swift file stays split per-role (`NodeStatusDisplay.kt` for the shared
 * pieces, each view model's own companion for role-only ones), matching this port's
 * StateFlow-per-ViewModel architecture rather than Swift's `@Observable` object composition.
 *
 * Extracted in the "RepeaterStatusScreen" slice once a second consumer existed — see
 * `RoomStatusViewModel`'s original class doc for why it wasn't split out for "RoomStatusScreen"
 * alone.
 */
object RemoteNodeRetry {
    private val TRANSIENT_RETRY_DELAYS_MS = listOf(500L, 1_000L, 2_000L)
    private val REQUEST_TIMEOUT_MS = RemoteOperationTimeoutPolicy.BINARY_MAXIMUM_MS

    suspend fun <T> performWithTransientRetries(operation: suspend (timeoutMs: Long) -> T): T {
        val deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
        val delays = TRANSIENT_RETRY_DELAYS_MS.iterator()
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw RemoteNodeError.Timeout
            try {
                return operation(remaining)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isTransientError(error) || !delays.hasNext()) throw error
                val waitRemaining = deadline - System.currentTimeMillis()
                if (waitRemaining <= 0) throw RemoteNodeError.Timeout
                delay(minOf(delays.next(), waitRemaining))
            }
        }
    }

    fun isTransientError(error: Throwable): Boolean {
        val meshError = when (error) {
            is RemoteNodeError.SessionError -> error.error
            is BinaryProtocolError.SessionError -> error.error
            else -> return false
        }
        return meshError is MeshCoreError.DeviceError && meshError.code == FirmwareDeviceErrorCode.REMOTE_NODE_NO_RESPONSE_YET
    }
}
