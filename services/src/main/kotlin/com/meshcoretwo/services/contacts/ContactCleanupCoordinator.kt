// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.contacts

import com.meshcoretwo.services.notifications.NotificationService
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.remotenode.RemoteNodeService
import com.meshcoretwo.services.sync.SyncCoordinator
import java.util.UUID

/**
 * Runs the cross-service side effects of a contact lifecycle change: channel-message deletion on
 * block, notification cleanup on block/delete, and remote node session removal on delete. Ported
 * from `ContactCleanupCoordinator.swift`; built by the composition root and injected into
 * [ContactService].
 *
 * Holds the collaborating services/stores directly (never a composition root), matching the
 * Swift original's reasoning for why a torn-down container cannot be kept alive through this
 * coordinator.
 *
 * **Not ported, with reasons:**
 * - **`refreshBlockedContactsCache`** — superseded by [ContactStore.isBlockedSender]'s direct
 *   (non-cached) query (see [SyncCoordinator]'s class doc); there is no cache to refresh here.
 * - **`updateBadgeCount`** — stock Android has no badge-count equivalent (see
 *   [com.meshcoretwo.services.notifications.NotificationPreferences]'s class doc).
 *
 * Every step is best-effort (a failure in one does not abort the rest), matching Swift's `try?`
 * usage throughout `handleCleanup`.
 *
 * [radioID] (added by upstream `e410eb8d`) scopes the delete-reason session lookup to this
 * device — a contact's public key is mesh-wide, so an unscoped lookup could resolve to (and
 * remove) another radio's session for the same physical node.
 */
class ContactCleanupCoordinator(
    private val contactStore: ContactStore,
    private val messageStore: MessageStore,
    private val remoteNodeSessionStore: RemoteNodeSessionStore,
    private val syncCoordinator: SyncCoordinator,
    private val notificationService: NotificationService,
    private val remoteNodeService: RemoteNodeService,
    private val radioID: UUID,
) : ContactCleanupHandling {
    override suspend fun handleCleanup(contactID: UUID, reason: ContactCleanupReason, publicKey: ByteArray) {
        if (reason == ContactCleanupReason.BLOCKED || reason == ContactCleanupReason.UNBLOCKED) {
            val contact = runCatching { contactStore.fetchContact(contactID) }.getOrNull()
            if (contact != null) {
                if (reason == ContactCleanupReason.BLOCKED) {
                    runCatching { messageStore.deleteChannelMessagesFromSender(contact.radioID, contact.name) }
                }
                syncCoordinator.notifyConversationsChanged()
            }
        }

        if (reason == ContactCleanupReason.BLOCKED || reason == ContactCleanupReason.DELETED) {
            notificationService.removeDeliveredNotificationsForContact(contactID)
        }

        if (reason == ContactCleanupReason.DELETED) {
            val session = runCatching { remoteNodeSessionStore.fetchSession(radioID, publicKey) }.getOrNull()
            if (session != null) {
                runCatching { remoteNodeService.removeSession(session.id, publicKey) }
            }
            syncCoordinator.notifyConversationsChanged()
        }
    }
}
