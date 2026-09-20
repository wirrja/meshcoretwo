// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.MeshCoreError

/**
 * Errors [RemoteNodeService] can throw. Ported from `RemoteNodeError.swift`, trimmed to the cases
 * this codebase's `RemoteNodeService` files actually construct (confirmed by grep across all of
 * `RemoteNodeService*.swift`) — `notConnected`/`sendFailed`/`pathDiscoveryFailed` are declared in
 * Swift but never constructed anywhere in the class, dead cases even there; add them if a ported
 * method ever needs to throw them. [PermissionDenied] was added alongside the "+CLI" slice, which
 * is the only place that constructs it.
 */
sealed class RemoteNodeError(message: String) : Exception(message) {
    data class LoginFailed(val reason: String) : RemoteNodeError("Login failed: $reason")
    object InvalidResponse : RemoteNodeError("Invalid response from remote node")

    /** Non-admin session attempted an admin-only CLI command. */
    object PermissionDenied : RemoteNodeError("Permission denied")
    object Timeout : RemoteNodeError("Request timed out")
    object SessionNotFound : RemoteNodeError("Remote node session not found")
    object PasswordNotFound : RemoteNodeError("Password not found in keychain")

    /** Keep-alive requires a direct routing path. */
    object FloodRouted : RemoteNodeError("Keep-alive requires direct routing path")
    object ContactNotFound : RemoteNodeError("Contact not found in database")

    /** The radio's contact table is full; cannot auto-add a missing node during login healing. */
    object RadioContactsFull : RemoteNodeError("Radio contact list is full")

    /** Login cancelled due to a duplicate attempt or shutdown. */
    object Cancelled : RemoteNodeError("Login cancelled")
    data class SessionError(val error: MeshCoreError) : RemoteNodeError(error.message ?: "session error")
}
