// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.diagnostics

import com.meshcoretwo.protocol.MeshCoreError

/**
 * Errors [BinaryProtocolService] can throw. Ported from `BinaryProtocolError.swift`, trimmed to
 * the cases this file's methods actually throw — `notConnected`/`sendFailed`/`timeout`/
 * `invalidResponse` are declared in Swift but never constructed by `BinaryProtocolService.swift`
 * itself (session-layer failures always surface as [SessionError] instead, including mesh
 * timeouts); add them if a ported method needs to throw them.
 */
sealed class BinaryProtocolError(message: String) : Exception(message) {
    /** The underlying session operation failed; [error] carries the specific reason. */
    data class SessionError(val error: MeshCoreError) : BinaryProtocolError(error.message ?: "session error")
}
