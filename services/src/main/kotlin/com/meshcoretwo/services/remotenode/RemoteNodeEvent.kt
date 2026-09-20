// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import java.util.UUID

/**
 * Remote-node session notifications broadcast by [RemoteNodeService.events]. Ported from
 * `RemoteNodeEvent.swift`. The stream is multicast via `MutableSharedFlow` — every subscriber
 * receives every event.
 */
sealed class RemoteNodeEvent {
    /** A remote-node session's connection state changed (login, logout, keep-alive failure, or BLE loss). */
    data class SessionStateChanged(val sessionID: UUID, val isConnected: Boolean) : RemoteNodeEvent()
}
