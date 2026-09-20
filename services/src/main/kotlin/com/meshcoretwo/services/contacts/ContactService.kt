// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.contacts

import android.net.Uri
import android.util.Log
import com.meshcoretwo.protocol.ContactSessionOps
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.ErrorCode
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.decodeHex
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.BlockedChannelSenderDto
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DeviceStore
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.toFloodedMeshContact
import com.meshcoretwo.services.sync.SyncCoordinator
import com.meshcoretwo.services.utilities.VContactIdentity
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Service for managing mesh network contacts: discovery, sync, add/update/remove. Ported from
 * `ContactService.swift`, covering the first vertical slice of its ~30-method surface: sync,
 * read, add/update, remove. Declares [ContactSessionOps] rather than the broader
 * `MeshCoreSessionProtocol` since that's all this slice touches (see that interface's own doc on
 * narrow role declarations); of the fuller Swift service's path-discovery/share/export/import
 * pass-throughs none are ported here yet, and [resetPath] is (added for the login screen's flood
 * toggle).
 *
 * [syncCoordinator]/[cleanupCoordinator] are now wired (both nullable, defaulting to `null` for
 * callers/tests that don't need the notification chain — e.g. the standalone `ContactServiceTest`
 * fixtures predating this wiring): [pruneOrphans]/[removeContact] run the full delete cleanup
 * chain, and [updateContactPreferences] runs the full block/unblock chain through
 * [ContactCleanupCoordinator] when one is supplied, falling back to the bare channel-message
 * deletion it always did when it isn't. `notifyContactsChanged`/`notifyConversationsChanged` fire
 * as in Swift; the `.nodeDeleted` event broadcast (`removeContact`'s "clear storage-full flag" UI
 * hint) has no equivalent here yet since [ContactService] has no event stream of its own — that,
 * plus `getConversations`, remains deferred (tracked in PLAN.md's Phase 3 status).
 *
 * **Deferred, not yet ported** (tracked in PLAN.md's Phase 3 status):
 * - `getConversations` (needs conversation-list aggregation across Message tables),
 *   favorite/mute/nickname toggles beyond what [updateContactPreferences] covers, path
 *   discovery, share/export/import (thin session pass-throughs, lower priority than the
 *   persistence-backed methods).
 *
 * **Mention tracking / blocked-sender lists** (added for the mention-count/blocked-sender
 * backfill slice) *are* ported: [clearContactMessages] mirrors `clearContactMessages`, and
 * [updateContactPreferences]/[blockChannelSender]/[unblockChannelSender]/[getBlockedChannelSenders]
 * mirror the `updateContactPreferences`/`BlockedChannelSendersView`/`ChatConversationView.performBlock`
 * behavior Swift splits across `ContactService` and direct `dataStore` calls from SwiftUI — this
 * port keeps all of it behind the service, matching the rest of this codebase's UI-stays-thin
 * convention. See [com.meshcoretwo.services.messages.IncomingMessageService]'s class doc for how
 * the actual mention detection and blocked-sender filtering on the ingestion path is wired.
 */
class ContactService(
    private val session: ContactSessionOps,
    private val contactStore: ContactStore,
    private val deviceStore: DeviceStore,
    private val messageStore: MessageStore,
    private val syncCoordinator: SyncCoordinator? = null,
    private val cleanupCoordinator: ContactCleanupHandling? = null,
) {
    companion object {
        private const val SYNC_LOG_TAG = "ContactSync"

        private const val CONTACT_URI_SCHEME = "meshcore"
        private const val CONTACT_URI_HOST = "contact"
        private const val CONTACT_URI_PATH = "/add"
        private const val CONTACT_URI_NAME_KEY = "name"
        private const val CONTACT_URI_PUBLIC_KEY_KEY = "public_key"
        private const val CONTACT_URI_TYPE_KEY = "type"

        /** Builds a shareable `meshcore://contact/add` URI. Ported from `ContactService.exportContactURI`. */
        fun exportContactURI(name: String, publicKey: ByteArray, type: ContactType): String =
            Uri.Builder()
                .scheme(CONTACT_URI_SCHEME)
                .authority(CONTACT_URI_HOST)
                .path(CONTACT_URI_PATH)
                .appendQueryParameter(CONTACT_URI_NAME_KEY, name)
                .appendQueryParameter(CONTACT_URI_PUBLIC_KEY_KEY, publicKey.hexString.uppercase())
                .appendQueryParameter(CONTACT_URI_TYPE_KEY, type.value.toString())
                .build()
                .toString()

        /**
         * Decodes a `meshcore://contact/add?name=&public_key=&type=` URI built by
         * [exportContactURI] — the scanned-QR/pasted-link counterpart, ported from
         * `MeshCoreURLParser.parseContactURL(_:)`. Returns `null` for any malformed input: wrong
         * scheme/host/path, an empty `name`, or a `public_key` that isn't valid hex of the right
         * byte length. An unrecognized/missing `type` defaults to [ContactType.CHAT], matching
         * Swift's fallback.
         */
        fun parseContactURI(uri: String): ContactImportResult? {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
            if (parsed.scheme != CONTACT_URI_SCHEME || parsed.host != CONTACT_URI_HOST || parsed.path != CONTACT_URI_PATH) return null
            val name = parsed.getQueryParameter(CONTACT_URI_NAME_KEY)?.takeIf { it.isNotEmpty() } ?: return null
            val publicKey = parsed.getQueryParameter(CONTACT_URI_PUBLIC_KEY_KEY)?.decodeHex()
                ?.takeIf { it.size == PacketBuilder.PUBLIC_KEY_SIZE } ?: return null
            val typeValue = parsed.getQueryParameter(CONTACT_URI_TYPE_KEY)?.toIntOrNull()?.takeIf { it in 0..255 }
            val type = typeValue?.let { ContactType.fromValue(it.toUByte()) } ?: ContactType.CHAT
            return ContactImportResult(name, publicKey, type)
        }

        /**
         * Matches a `<64-hex:digits:name>` contact share token — see [formatContactShareToken].
         * Public so callers that need every token's range (e.g. [com.meshcoretwo.android.chat
         * .linkify.MessageLinkTokenizer]) don't duplicate the pattern; [parseContactShareToken]
         * still does its own validation per match, since a regex match alone doesn't guarantee a
         * valid public key/type.
         */
        val contactShareTokenRegex = Regex("""<[0-9a-fA-F]{64}:\d+:[^>]+>""")

        /**
         * Formats a contact into an inline `<publicKeyHex:type:name>` share token — the format a
         * mesh message body embeds to share a contact inline (distinct from [exportContactURI]'s
         * `meshcore://contact/add` link, used for QR codes). Ported from
         * `ContactShareUtilities.formatShare`. `>` is reserved as the token terminator and is
         * stripped from the name; the name may otherwise contain `:`.
         */
        fun formatContactShareToken(name: String, publicKey: ByteArray, type: ContactType): String =
            "<${publicKey.hexString.uppercase()}:${type.value}:${name.filter { it != '>' }}>"

        /**
         * Parses the first `<publicKeyHex:type:name>` share token found in [text]. Ported from
         * `ContactShareUtilities.parseShare`. Unlike [parseContactURI], an unrecognized `type`
         * fails the parse rather than defaulting to [ContactType.CHAT] — this token format encodes
         * the type as a plain digit with no Swift-side fallback to mirror.
         */
        fun parseContactShareToken(text: String): ContactImportResult? {
            val match = contactShareTokenRegex.find(text) ?: return null
            val interior = match.value.substring(1, match.value.length - 1)
            val fields = interior.split(':', limit = 3)
            if (fields.size != 3) return null
            val publicKey = fields[0].decodeHex()?.takeIf { it.size == PacketBuilder.PUBLIC_KEY_SIZE } ?: return null
            val type = fields[1].toIntOrNull()?.takeIf { it in 0..255 }?.let { ContactType.fromValue(it.toUByte()) } ?: return null
            val name = fields[2].takeIf { it.isNotEmpty() } ?: return null
            return ContactImportResult(name, publicKey, type)
        }
    }

    /**
     * Syncs all contacts from the device, upserting into local storage. On a full sync
     * (`since == null`) also prunes local contacts a complete device snapshot proves are gone —
     * see [pruneOrphans]. Ported from `syncContacts(radioID:since:)`.
     */
    suspend fun syncContacts(radioID: UUID, since: Instant? = null): ContactSyncResult {
        Log.d(SYNC_LOG_TAG, "syncContacts start: radioID=$radioID since=$since")
        try {
            val fetchResult = session.getContactsReportingTotal(since)
            val meshContacts = fetchResult.contacts
            val devicePublicKeys = meshContacts.map { it.publicKey.toList() }.toSet()

            val receivedCount = contactStore.batchSaveContacts(radioID, meshContacts)
            val lastTimestamp = meshContacts.maxOfOrNull { it.lastModified.epochSecond.toUInt() } ?: 0u
            Log.d(
                SYNC_LOG_TAG,
                "syncContacts fetched: reportedTotal=${fetchResult.reportedTotal} " +
                    "fetchedCount=${meshContacts.size} receivedCount=$receivedCount",
            )

            if (since == null) {
                pruneOrphans(radioID, devicePublicKeys, fetchResult.reportedTotal)
            }

            return ContactSyncResult(
                contactsReceived = receivedCount,
                lastSyncTimestamp = lastTimestamp,
                isIncremental = since != null,
            )
        } catch (error: MeshCoreError) {
            Log.e(SYNC_LOG_TAG, "syncContacts failed: since=$since", error)
            throw ContactServiceError.SessionError(error)
        }
    }

    /**
     * Removes local contacts that a full fetch proves are gone from the device.
     *
     * Prunes only on a complete snapshot: [devicePublicKeys]' count must meet [reportedTotal].
     * A `null` [reportedTotal] means the reply carried no `contactsStart` header, so the device
     * total is unknown and the snapshot cannot be proven complete — the prune skips. Without a
     * usable self public key (from [deviceStore]) the ZephCore V-contact cannot be identified,
     * so the prune skips rather than risk deleting a local V-contact row.
     */
    private suspend fun pruneOrphans(radioID: UUID, devicePublicKeys: Set<List<Byte>>, reportedTotal: Int?) {
        if (reportedTotal == null || devicePublicKeys.size < reportedTotal) return
        val selfPublicKey = deviceStore.fetchDeviceByRadioId(radioID)?.publicKey ?: return
        if (selfPublicKey.size != PacketBuilder.PUBLIC_KEY_SIZE) return

        val orphans = contactStore.fetchContacts(radioID).filter { contact ->
            !devicePublicKeys.contains(contact.publicKey.toList()) &&
                !VContactIdentity.isVContact(contact.publicKey, selfPublicKey)
        }
        for (orphan in orphans) {
            contactStore.deleteContact(orphan.id)
            cleanupCoordinator?.handleCleanup(orphan.id, ContactCleanupReason.DELETED, orphan.publicKey)
        }
    }

    /** Gets a specific contact by public key from local storage. */
    suspend fun getContact(radioID: UUID, publicKey: ByteArray): ContactDto? =
        contactStore.fetchContact(radioID, publicKey)

    /** Gets a contact by its local id from local storage. */
    suspend fun getContactById(id: UUID): ContactDto? = contactStore.fetchContact(id)

    /** Gets all contacts for a device from local storage. */
    suspend fun getContacts(radioID: UUID): List<ContactDto> = contactStore.fetchContacts(radioID)

    /**
     * Live version of [getContacts] — re-emits on any write to the `contacts` table (new contact,
     * unread-counter change, block/favorite toggle, ...). No Swift equivalent: `ContactService`
     * there has no reactive stream either (see this class's doc), but `app`'s `ChatListViewModel`
     * needs one since — unlike iOS's monolithic `ChatViewModel`, which owns both the list and the
     * open conversation and can just mutate its in-memory array — Android splits list and
     * conversation into separate view models with no shared state to optimistically update.
     */
    fun observeContacts(radioID: UUID): Flow<List<ContactDto>> = contactStore.observeContacts(radioID)

    /** Adds or updates a contact on the device, then mirrors the change into local storage. */
    suspend fun addOrUpdateContact(radioID: UUID, contact: MeshContact) {
        try {
            session.addContact(contact)
            contactStore.saveContact(radioID, contact)
        } catch (error: MeshCoreError) {
            if (error.isDeviceError(ErrorCode.TABLE_FULL)) throw ContactServiceError.ContactTableFull
            throw ContactServiceError.SessionError(error)
        }
    }

    /** Removes a contact from the device, then deletes its local row if one exists. */
    suspend fun removeContact(radioID: UUID, publicKey: ByteArray) {
        try {
            session.removeContact(publicKey)
            contactStore.fetchContact(radioID, publicKey)?.let { contact ->
                contactStore.deleteContact(contact.id)
                cleanupCoordinator?.handleCleanup(contact.id, ContactCleanupReason.DELETED, publicKey)
            }
            syncCoordinator?.notifyContactsChanged()
        } catch (error: MeshCoreError) {
            if (error.isDeviceError(ErrorCode.NOT_FOUND)) throw ContactServiceError.ContactNotFound
            throw ContactServiceError.SessionError(error)
        }
    }

    /**
     * Deletes a contact's local row without touching the device — used when the device has already
     * forgotten it ([ContactServiceError.ContactNotFound] from [removeContact]) so local state still
     * converges. Ported from `ContactService.removeLocalContact`, trimmed of its V-contact re-check:
     * [com.meshcoretwo.services.connection.removeStaleNodes]/[com.meshcoretwo.services.connection.removeUnfavoritedNodes]
     * (its only callers) already filter V-contacts out via
     * [com.meshcoretwo.services.utilities.VContactIdentity] before either removal path runs.
     */
    suspend fun removeLocalContact(contactID: UUID, publicKey: ByteArray) {
        contactStore.deleteContact(contactID)
        cleanupCoordinator?.handleCleanup(contactID, ContactCleanupReason.DELETED, publicKey)
        syncCoordinator?.notifyContactsChanged()
    }

    /**
     * Clears the radio's stored out-path to a contact, so the next packet to it is flood-routed,
     * and mirrors flood routing on the local row. Ported from `ContactService.resetPath`. The
     * local save goes through [toFloodedMeshContact], so the raw type byte survives even for a
     * contact type this port doesn't model.
     */
    suspend fun resetPath(radioID: UUID, publicKey: ByteArray) {
        try {
            session.resetPath(publicKey)
            contactStore.fetchContact(radioID, publicKey)?.let { contact ->
                contactStore.saveContact(radioID, contact.toFloodedMeshContact(Instant.now()))
            }
        } catch (error: MeshCoreError) {
            if (error.isDeviceError(ErrorCode.NOT_FOUND)) throw ContactServiceError.ContactNotFound
            throw ContactServiceError.SessionError(error)
        }
    }

    /**
     * Resets a contact's unread/mention badges without touching its messages — the "conversation
     * was viewed" side effect Swift's `ChatViewModel.loadMessages` runs after every successful
     * message load. Callers should re-run this on every reload while the conversation is open (it's
     * idempotent once the badges are already zero); it's a separate method from [clearContactMessages]
     * because that one also deletes the messages, an explicit user action this is not.
     */
    suspend fun markConversationRead(contactID: UUID) {
        contactStore.clearUnreadCount(contactID)
        contactStore.clearUnreadMentionCount(contactID)
    }

    /**
     * Deletes all of a contact's messages and resets its unread badges, leaving the contact row
     * itself in place. Ported from `clearContactMessages` — see [MessageStore.deleteMessagesForContact]
     * for what cascade it drops relative to Swift.
     */
    suspend fun clearContactMessages(contactID: UUID) {
        messageStore.deleteMessagesForContact(contactID)
        contactStore.clearUnreadCount(contactID)
        contactStore.clearUnreadMentionCount(contactID)
    }

    /**
     * Updates local contact preferences (nickname, blocked, favorite). `nickname == null` leaves
     * the existing nickname unchanged; an empty/whitespace-only string clears it; otherwise it's
     * trimmed and stored. Ported from `updateContactPreferences` — see this class's doc for which
     * of Swift's `ContactCleanupCoordinator` block/unblock side effects are (not) run here.
     *
     * @throws ContactServiceError.ContactNotFound if no local row matches [contactID].
     */
    suspend fun updateContactPreferences(
        contactID: UUID,
        nickname: String? = null,
        isBlocked: Boolean? = null,
        isFavorite: Boolean? = null,
    ) {
        val existing = contactStore.fetchContact(contactID) ?: throw ContactServiceError.ContactNotFound

        val resolvedNickname = if (nickname != null) {
            nickname.trim().ifEmpty { null }
        } else {
            existing.nickname
        }

        val isBeingBlocked = isBlocked == true && !existing.isBlocked
        val isBeingUnblocked = isBlocked == false && existing.isBlocked

        contactStore.updateContactPreferences(
            contactID = contactID,
            nickname = resolvedNickname,
            isBlocked = isBlocked ?: existing.isBlocked,
            isFavorite = isFavorite ?: existing.isFavorite,
            // Blocking zeroes unread (matching Swift); unreadMentionCount is left untouched
            // there too — ported as-is, not "fixed", since this is a behavior port.
            unreadCount = if (isBeingBlocked) 0 else existing.unreadCount,
        )

        if (isBeingBlocked) {
            if (cleanupCoordinator != null) {
                cleanupCoordinator.handleCleanup(contactID, ContactCleanupReason.BLOCKED, existing.publicKey)
            } else {
                // Fallback when no cleanup coordinator is wired: at least drop the sender's channel messages.
                messageStore.deleteChannelMessagesFromSender(existing.radioID, existing.name)
            }
        } else if (isBeingUnblocked) {
            cleanupCoordinator?.handleCleanup(contactID, ContactCleanupReason.UNBLOCKED, existing.publicKey)
        }
        syncCoordinator?.notifyContactsChanged()
    }

    /**
     * Updates a contact's OCV (battery curve) settings. Ported from
     * `ContactService.updateContactOCVSettings(contactID:preset:customArray:)`.
     *
     * @param preset An [com.meshcoretwo.services.remotenode.OCVPreset.rawValue].
     * @param customArray Comma-separated 11-value OCV array string; only meaningful when [preset]
     * is the `custom` raw value.
     * @throws ContactServiceError.ContactNotFound if no local row matches [contactID].
     */
    suspend fun updateContactOCVSettings(contactID: UUID, preset: String, customArray: String?) {
        contactStore.fetchContact(contactID) ?: throw ContactServiceError.ContactNotFound
        contactStore.updateContactOCVSettings(contactID, preset, customArray)
    }

    /**
     * Blocks a channel sender name on [radioID]: records the block, deletes that sender's
     * existing channel messages, and blocks any matching local contacts by id (so future DMs from
     * the same person are suppressed too, matching Swift's `isBlocked` toggle). Ported from
     * `ChatConversationView.performBlock`, minus the `SyncCoordinator` cache-refresh/UI-notify
     * tail (no `SyncCoordinator` yet).
     */
    suspend fun blockChannelSender(radioID: UUID, senderName: String, contactIDs: Set<UUID> = emptySet()) {
        contactStore.saveBlockedChannelSender(BlockedChannelSenderDto(id = UUID.randomUUID(), name = senderName, radioID = radioID, dateBlocked = Instant.now()))
        messageStore.deleteChannelMessagesFromSender(radioID, senderName)
        for (contactID in contactIDs) {
            updateContactPreferences(contactID, isBlocked = true)
        }
    }

    /** Unblocks a channel sender name on [radioID]. Ported from `BlockedChannelSendersView`'s unblock action. */
    suspend fun unblockChannelSender(radioID: UUID, senderName: String) = contactStore.deleteBlockedChannelSender(radioID, senderName)

    /** Lists every blocked channel sender for a device, most-recently-blocked first. */
    suspend fun getBlockedChannelSenders(radioID: UUID) = contactStore.fetchBlockedChannelSenders(radioID)
}

private fun MeshCoreError.isDeviceError(code: ErrorCode): Boolean =
    (this as? MeshCoreError.DeviceError)?.deviceErrorCode == code

/**
 * A contact decoded from a `meshcore://contact/add` link — see [ContactService.parseContactURI].
 * The scanned-QR/pasted-link counterpart of a manually entered name/public-key/type triple.
 */
data class ContactImportResult(val name: String, val publicKey: ByteArray, val contactType: ContactType) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactImportResult) return false
        return name == other.name && publicKey.contentEquals(other.publicKey) && contactType == other.contactType
    }

    override fun hashCode(): Int = 31 * (31 * name.hashCode() + publicKey.contentHashCode()) + contactType.hashCode()
}
