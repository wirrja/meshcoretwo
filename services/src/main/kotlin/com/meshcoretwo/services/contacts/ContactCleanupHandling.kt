// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.contacts

import java.util.UUID

/**
 * Cross-service side effects of a contact lifecycle change (block, unblock, delete). Ported from
 * `ContactCleanupHandling.swift`. [ContactService] invokes this after its own database writes;
 * [ContactCleanupCoordinator] is the production implementation.
 */
interface ContactCleanupHandling {
    /**
     * Runs the cleanup chain for one contact.
     *
     * @param contactID The affected contact's local ID.
     * @param reason Which lifecycle change triggered the cleanup.
     * @param publicKey The contact's public key, used to locate any associated remote node session.
     */
    suspend fun handleCleanup(contactID: UUID, reason: ContactCleanupReason, publicKey: ByteArray)
}
