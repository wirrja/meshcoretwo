// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.contacts

import com.meshcoretwo.protocol.MeshCoreError

/**
 * Errors [ContactService] can throw. Ported from `ContactServiceError.swift`, trimmed to the
 * cases this vertical slice's methods actually throw — `notConnected`/`sendFailed`/
 * `invalidResponse`/`syncInterrupted`/`shareContactUnavailable` are added when the
 * `ContactService` methods that throw them (`syncContactsForRefresh`, `shareContact`, ...) are
 * ported.
 */
sealed class ContactServiceError(message: String) : Exception(message) {
    /** No local contact row matched a public key the device operation otherwise succeeded for. */
    object ContactNotFound : ContactServiceError("Contact not found on device")

    /** The device's contact table has no room for another entry. */
    object ContactTableFull : ContactServiceError("Device node list is full")

    /** The underlying session operation failed; [error] carries the specific reason. */
    data class SessionError(val error: MeshCoreError) : ContactServiceError(error.message ?: "session error")
}
