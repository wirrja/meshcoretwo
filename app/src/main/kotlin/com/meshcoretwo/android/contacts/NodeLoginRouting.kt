// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.remotenode.RemoteNodeError

/**
 * Chooses the route for each login attempt from the repeater/room login screen, and falls back
 * to flood routing once when the stored route gets no reply. Ported from the routing half of
 * `NodeAuthenticationSheet.authenticate()`. Pulled out of [NodeAuthViewModel] so it's testable
 * without a live `ConnectionManager`: the radio calls come in as lambdas.
 *
 * The one piece of state kept between attempts is [didResetPath]. Once any reset has run, the
 * radio's stored path is gone regardless of what the screen's contact snapshot says, so every
 * later attempt is flood and skips the extra BLE round-trip.
 */
class NodeLoginRouting {
    var didResetPath: Boolean = false
        private set

    /**
     * @param resetPath clears the radio's stored path to [contact] (`ContactService.resetPath`).
     * @param onFloodRetry called right before the automatic flood retry, so the screen can say
     *   why the login is taking longer.
     * @param performLogin sends the login with the given encoded `path_len`
     *   ([PacketBuilder.FLOOD_PATH_SENTINEL] for flood).
     */
    suspend fun <T> login(
        contact: ContactDto,
        useFloodRouting: Boolean,
        resetPath: suspend () -> Unit,
        onFloodRetry: () -> Unit,
        performLogin: suspend (pathLength: UByte) -> T,
    ): T {
        val pathLength = when {
            useFloodRouting && !contact.isFloodRouted && !didResetPath -> {
                resetPath()
                didResetPath = true
                PacketBuilder.FLOOD_PATH_SENTINEL
            }
            useFloodRouting || didResetPath -> PacketBuilder.FLOOD_PATH_SENTINEL
            else -> contact.outPathLength
        }

        return try {
            performLogin(pathLength)
        } catch (error: RemoteNodeError.Timeout) {
            if (pathLength == PacketBuilder.FLOOD_PATH_SENTINEL) throw error
            // The stored route produced no reply; clear it and let the network find a path,
            // mirroring what the flood toggle would have done.
            onFloodRetry()
            resetPath()
            didResetPath = true
            performLogin(PacketBuilder.FLOOD_PATH_SENTINEL)
        }
    }
}
