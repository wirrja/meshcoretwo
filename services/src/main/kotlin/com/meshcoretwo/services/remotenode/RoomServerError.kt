// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.MeshCoreError

/**
 * Errors [RoomServerService] can throw. Ported from `RoomServerError.swift`, trimmed to the
 * cases this port's `RoomServerService.kt` actually constructs — `notConnected`/`invalidResponse`
 * are declared in Swift but never constructed in `RoomServerService.swift` itself (it throws
 * [com.meshcoretwo.services.remotenode.RemoteNodeError.SessionNotFound]/`InvalidResponse` for
 * those cases instead); add them here if a future slice needs to construct them directly.
 */
sealed class RoomServerError(message: String) : Exception(message) {
    object SessionNotFound : RoomServerError("Room session not found.")
    data class SendFailed(val reason: String) : RoomServerError("Send failed: $reason")
    object PermissionDenied : RoomServerError("Permission denied.")
    data class SessionError(val error: MeshCoreError) : RoomServerError(error.message ?: "session error")
}
