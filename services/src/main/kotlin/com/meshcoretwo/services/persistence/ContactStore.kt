// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.withTransaction
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.services.backup.BackupDedupKeys
import com.meshcoretwo.services.backup.ContactBatchInsertResult
import com.meshcoretwo.services.backup.PerTypeCounts
import com.meshcoretwo.services.backup.mergeContactBackupMetadata
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Contact persistence, wrapping [ContactDao] with the upsert business logic Swift keeps in
 * `PersistenceStore+Contacts.swift`. Scoped to the operations [com.meshcoretwo.services.contacts.ContactService]'s
 * ported slices need (sync, get, add/remove, unread/mention counters, blocked-contact/
 * blocked-channel-sender lookups) — orphaned-DM adoption is still deferred until the "Discover"
 * feature it belongs to is ported (see PLAN.md's Phase 3 status).
 *
 * **Blocked channel senders** ([saveBlockedChannelSender]/[deleteBlockedChannelSender]/
 * [fetchBlockedChannelSenders]) live here, wrapping the new [BlockedChannelSenderDao], matching
 * Swift's placement in `ContactPersisting` (despite the model's own file being named
 * `PersistenceStore+Channels.swift` — channel messages are the *effect* of a block, not the
 * *subject* being blocked).
 *
 * A single implementation exists, so this is a concrete class rather than an interface +
 * fake — [ContactServiceTest] exercises it directly against a real in-memory Room database via
 * Robolectric (Room, unlike `EncryptedSharedPreferences`'s `AndroidKeyStore` dependency, works
 * fully under Robolectric), the same reasoning as [com.meshcoretwo.services.transport.BleStateMachine] not getting
 * a speculative protocol interface yet.
 */
class ContactStore(private val database: MeshCoreDatabase) {
    private val dao get() = database.contactDao()
    private val blockedSenderDao get() = database.blockedChannelSenderDao()

    /** Fetch all contacts for a device, sorted by name. */
    suspend fun fetchContacts(radioID: UUID): List<ContactDto> = dao.fetchContacts(radioID).map { it.toDto() }

    /** Every contact across every device — backup export's unscoped fetch. Ported from `fetchAllContacts`. */
    suspend fun fetchAllContacts(): List<ContactDto> = dao.fetchAll().map { it.toDto() }

    /** Live version of [fetchContacts] — re-emits whenever any row in `contacts` changes. */
    fun observeContacts(radioID: UUID): Flow<List<ContactDto>> =
        dao.observeContacts(radioID).map { entities -> entities.map { it.toDto() } }

    /** Fetch a contact by its local id. */
    suspend fun fetchContact(id: UUID): ContactDto? = dao.fetchContact(id)?.toDto()

    /** Fetch a contact by its device and public key. */
    suspend fun fetchContact(radioID: UUID, publicKey: ByteArray): ContactDto? =
        dao.fetchContact(radioID, publicKey)?.toDto()

    /**
     * Fetch a contact by its device and public-key prefix (e.g. the 6-byte sender prefix on an
     * inbound message). Matches Swift's `fetchContact(radioID:publicKeyPrefix:)`: a linear scan
     * over the device's contacts rather than a SQL `LIKE`/`substr` query — this is a direct
     * behavior port, not a claim that the scan is the fastest possible implementation. Named
     * distinctly from [fetchContact] rather than overloaded on it: Kotlin/JVM resolves overloads
     * by parameter type, not name, and both would otherwise be `(UUID, ByteArray)`.
     */
    suspend fun fetchContactByPrefix(radioID: UUID, publicKeyPrefix: ByteArray): ContactDto? =
        fetchContacts(radioID).firstOrNull { it.publicKeyPrefix.contentEquals(publicKeyPrefix) }

    /**
     * Saves or updates a contact from a freshly-received wire record, matching by
     * `(radioID, publicKey)`. Returns the row's id and whether it was newly inserted — see
     * [ContactEntity.fromMeshContact]/[ContactEntity.updatedFrom] for exactly which fields an
     * update touches.
     */
    suspend fun saveContact(radioID: UUID, contact: MeshContact): Pair<UUID, Boolean> {
        val existing = dao.fetchContact(radioID, contact.publicKey)
        return if (existing != null) {
            dao.update(existing.updatedFrom(contact))
            existing.id to false
        } else {
            val id = UUID.randomUUID()
            dao.insert(ContactEntity.fromMeshContact(id, radioID, contact))
            id to true
        }
    }

    /**
     * Upserts contacts from wire records in a single transaction, matching local rows by
     * `(radioID, publicKey)`. Commits once for the whole batch rather than once per contact,
     * the dominant cost of a full contact sync over BLE. Returns the number of contacts
     * persisted. A public key repeated within [contacts] updates the same row in sequence
     * rather than racing a duplicate insert against the table's unique index.
     */
    suspend fun batchSaveContacts(radioID: UUID, contacts: List<MeshContact>): Int {
        if (contacts.isEmpty()) return 0
        database.withTransaction {
            val byKey = dao.fetchContacts(radioID).associateByTo(mutableMapOf()) { it.publicKey.toList() }
            for (contact in contacts) {
                val key = contact.publicKey.toList()
                val existing = byKey[key]
                val row = if (existing != null) {
                    existing.updatedFrom(contact).also { dao.update(it) }
                } else {
                    ContactEntity.fromMeshContact(UUID.randomUUID(), radioID, contact).also { dao.insert(it) }
                }
                byKey[key] = row
            }
        }
        return contacts.size
    }

    /** Deletes a contact by its local id. */
    suspend fun deleteContact(id: UUID) = dao.deleteContact(id)

    /**
     * Writes a contact's app-only preference fields in one update (no-op if the row doesn't
     * exist). Backs [com.meshcoretwo.services.contacts.ContactService.updateContactPreferences] —
     * see that method for the resolved-value/blocking-transition logic this just persists.
     */
    suspend fun updateContactPreferences(contactID: UUID, nickname: String?, isBlocked: Boolean, isFavorite: Boolean, unreadCount: Int) {
        dao.fetchContact(contactID)?.let {
            dao.update(it.copy(nickname = nickname, isBlocked = isBlocked, isFavorite = isFavorite, unreadCount = unreadCount))
        }
    }

    /** Updates a contact's last-message timestamp (`null` clears it), used to sort/preview conversations. */
    suspend fun updateContactLastMessage(contactID: UUID, date: Instant?) {
        dao.fetchContact(contactID)?.let { dao.update(it.copy(lastMessageDate = date)) }
    }

    /**
     * Writes a contact's OCV (battery curve) settings (no-op if the row doesn't exist). Backs
     * [com.meshcoretwo.services.contacts.ContactService.updateContactOCVSettings].
     */
    suspend fun updateContactOCVSettings(contactID: UUID, preset: String, customArray: String?) {
        dao.fetchContact(contactID)?.let { dao.update(it.copy(ocvPreset = preset, customOCVArrayString = customArray)) }
    }

    /**
     * Stamps phone-clock recency for a contact heard on air (an advertisement, path update, or
     * materialized pending advert). Ported from `touchContactHeard(radioID:publicKey:at:)`,
     * trimmed to the Contact-table concern — Swift's same-transaction `DiscoveredNode` upsert
     * ("a known contact can lack a Discover row... hearing it on air makes it discoverable") is
     * dropped along with the rest of the Discover/Map feature, which this port doesn't have.
     *
     * @return `true` if a contact row existed and was stamped, `false` if none matched.
     */
    suspend fun touchContactHeard(radioID: UUID, publicKey: ByteArray, at: Instant): Boolean {
        val existing = dao.fetchContact(radioID, publicKey) ?: return false
        val stamp = clampedPhoneClockTimestamp(at.epochSecond.toULong(), now = at)
        dao.update(existing.copy(lastHeardTimestamp = maxOf(existing.lastHeardTimestamp, stamp.toLong())))
        return true
    }

    /** The set of public keys (as byte lists, for `Set`/`Map` usability) currently on record for a device. */
    suspend fun fetchContactPublicKeys(radioID: UUID): Set<List<Byte>> =
        fetchContacts(radioID).mapTo(mutableSetOf()) { it.publicKey.toList() }

    /**
     * Every contact public key for a device, grouped by its first byte — feeds
     * [com.meshcoretwo.services.rxlog.RxLogService.updateContactPublicKeys], which narrows RX-log
     * decryption candidates by a packet's leading sender-prefix byte. Ported from
     * `fetchContactPublicKeysByPrefix`.
     */
    suspend fun fetchContactPublicKeysByPrefix(radioID: UUID): Map<UByte, List<ByteArray>> =
        fetchContacts(radioID)
            .filter { it.publicKey.isNotEmpty() }
            .groupBy({ it.publicKey[0].toUByte() }, { it.publicKey })

    // MARK: - Unread / Mention Counters

    /** Increments a contact's unread-message counter (no-op if the row doesn't exist). Ported from `incrementUnreadCount`. */
    suspend fun incrementUnreadCount(contactID: UUID) {
        dao.fetchContact(contactID)?.let { dao.update(it.copy(unreadCount = it.unreadCount + 1)) }
    }

    /** Zeroes a contact's unread-message counter. Ported from `clearUnreadCount`. */
    suspend fun clearUnreadCount(contactID: UUID) {
        dao.fetchContact(contactID)?.let { dao.update(it.copy(unreadCount = 0)) }
    }

    /** Increments a contact's unread-mention counter (no-op if the row doesn't exist). Ported from `incrementUnreadMentionCount`. */
    suspend fun incrementUnreadMentionCount(contactID: UUID) {
        dao.fetchContact(contactID)?.let { dao.update(it.copy(unreadMentionCount = it.unreadMentionCount + 1)) }
    }

    /** Decrements a contact's unread-mention counter, clamped at 0. Ported from `decrementUnreadMentionCount`. */
    suspend fun decrementUnreadMentionCount(contactID: UUID) {
        dao.fetchContact(contactID)?.let { dao.update(it.copy(unreadMentionCount = maxOf(0, it.unreadMentionCount - 1))) }
    }

    /** Zeroes a contact's unread-mention counter. Ported from `clearUnreadMentionCount`. */
    suspend fun clearUnreadMentionCount(contactID: UUID) {
        dao.fetchContact(contactID)?.let { dao.update(it.copy(unreadMentionCount = 0)) }
    }

    // MARK: - Blocking

    /** Fetch every blocked contact for a device. Ported from `fetchBlockedContacts`. */
    suspend fun fetchBlockedContacts(radioID: UUID): List<ContactDto> = dao.fetchBlockedContacts(radioID).map { it.toDto() }

    /**
     * Whether [name] is blocked on [radioID] — either as a blocked contact's mesh-advertised
     * [ContactDto.name] (**not** [ContactDto.displayName]/nickname: a channel message's
     * `senderNodeName` is parsed from the wire, so it can only ever match the advertised name) or
     * a blocked channel sender name. Exact, case-sensitive string match, matching Swift's
     * `SyncCoordinator.isBlockedSender` (`Set(blockedContacts.map(\.name))`). Unlike Swift's
     * `blockedNames` cache (refreshed once per sync round via `refreshBlockedContactsCache`),
     * this queries the tables directly on every call — this port has no `SyncCoordinator` to own
     * a cache yet, and a direct lookup can't go stale, only slightly slower.
     */
    suspend fun isBlockedSender(radioID: UUID, name: String?): Boolean {
        if (name == null) return false
        if (fetchBlockedContacts(radioID).any { it.name == name }) return true
        return blockedSenderDao.fetch(radioID, name) != null
    }

    /** Saves (or updates the [BlockedChannelSenderDto.dateBlocked] of) a blocked channel sender, upserting by `(radioID, name)`. Ported from `saveBlockedChannelSender`. */
    suspend fun saveBlockedChannelSender(dto: BlockedChannelSenderDto) {
        val existing = blockedSenderDao.fetch(dto.radioID, dto.name)
        if (existing != null) {
            blockedSenderDao.update(existing.copy(dateBlocked = dto.dateBlocked))
        } else {
            blockedSenderDao.insert(BlockedChannelSenderEntity(dto.id, dto.name, dto.radioID, dto.dateBlocked))
        }
    }

    /** Deletes a blocked channel sender by exact `(radioID, name)` match (no-op if none exists). Ported from `deleteBlockedChannelSender`. */
    suspend fun deleteBlockedChannelSender(radioID: UUID, name: String) = blockedSenderDao.delete(radioID, name)

    /** Fetches every blocked channel sender for a device, most-recently-blocked first. Ported from `fetchBlockedChannelSenders`. */
    suspend fun fetchBlockedChannelSenders(radioID: UUID): List<BlockedChannelSenderDto> =
        blockedSenderDao.fetchAll(radioID).map { it.toDto() }

    /** Every blocked channel sender across every device — backup export's unscoped fetch. Ported from `fetchAllBlockedChannelSenders`. */
    suspend fun fetchAllBlockedChannelSenders(): List<BlockedChannelSenderDto> = blockedSenderDao.fetchAll().map { it.toDto() }

    /**
     * Every contact across [radioIDs], keyed by [BackupDedupKeys.contactKey] — backup import's
     * existing-row lookup. Ported from `fetchExistingContactsByKey`.
     */
    suspend fun fetchExistingContactsByKey(radioIDs: Set<UUID>): Map<String, ContactDto> {
        if (radioIDs.isEmpty()) return emptyMap()
        return dao.fetchAll(radioIDs.toList()).associate { BackupDedupKeys.contactKey(it.radioID, it.publicKey) to it.toDto() }
    }

    /**
     * Reconciles backup contacts against local contacts by `(radioID, publicKey)`: a match merges
     * backup metadata into the existing row ([mergeContactBackupMetadata]); anything else inserts
     * as a new contact, seeded with the same future-clock-clamped [ContactDto.lastHeardTimestamp]
     * the merge path uses. Ported from `batchInsertContacts`.
     */
    suspend fun batchInsertContacts(dtos: List<ContactDto>, radioIDs: Set<UUID>): ContactBatchInsertResult {
        val existingByKey = fetchExistingContactsByKey(radioIDs).toMutableMap()
        val contactIdsByKey = mutableMapOf<String, UUID>()
        var inserted = 0
        var merged = 0
        var skipped = 0
        val now = Instant.now()
        for (dto in dtos) {
            val key = BackupDedupKeys.contactKey(dto.radioID, dto.publicKey)
            val existing = existingByKey[key]
            if (existing != null) {
                val (mergedDto, changed) = mergeContactBackupMetadata(existing, dto, now)
                if (changed) {
                    dao.update(mergedDto.toEntity())
                    existingByKey[key] = mergedDto
                    merged++
                }
                skipped++
                contactIdsByKey[key] = existing.id
                continue
            }
            val seeded = dto.copy(lastHeardTimestamp = clampedPhoneClockTimestamp(dto.lastHeardTimestamp.toULong(), now).toUInt())
            dao.insert(seeded.toEntity())
            existingByKey[key] = seeded
            contactIdsByKey[key] = seeded.id
            inserted++
        }
        return ContactBatchInsertResult(PerTypeCounts(inserted = inserted, merged = merged, skipped = skipped), contactIdsByKey)
    }

    /**
     * Every blocked channel sender across [radioIDs] — backup import's existing-row lookup.
     * Ported from `existingBlockedSenderKeys`.
     */
    suspend fun existingBlockedSenderKeys(radioIDs: Set<UUID>): Set<String> {
        if (radioIDs.isEmpty()) return emptySet()
        return blockedSenderDao.fetchAll(radioIDs.toList()).mapTo(mutableSetOf()) { BackupDedupKeys.blockedChannelSenderKey(it.radioID, it.name) }
    }

    /** Inserts backup blocked-channel-senders whose `(radioID, name)` key isn't already in [existingKeys]. Ported from `batchInsertBlockedChannelSenders`. */
    suspend fun batchInsertBlockedChannelSenders(dtos: List<BlockedChannelSenderDto>, existingKeys: Set<String>): PerTypeCounts {
        val knownKeys = existingKeys.toMutableSet()
        var inserted = 0
        var skipped = 0
        for (dto in dtos) {
            val key = BackupDedupKeys.blockedChannelSenderKey(dto.radioID, dto.name)
            if (!knownKeys.add(key)) {
                skipped++
                continue
            }
            blockedSenderDao.insert(dto.toEntity())
            inserted++
        }
        return PerTypeCounts(inserted = inserted, skipped = skipped)
    }

    /**
     * Finds a contact by full 32-byte public key, searching across every device — used for
     * routing hints where the contact may exist under a different device's id. Matches
     * `findContactByPublicKey(_:)`.
     */
    suspend fun findContactByPublicKey(publicKey: ByteArray): ContactDto? =
        dao.fetchAll().firstOrNull { it.publicKey.contentEquals(publicKey) }?.toDto()

    /**
     * Finds a contact's display name by a 4- or 6-byte public-key prefix, searching across every
     * device — a room-message author may only be known from a previously-connected radio's
     * contact list. Matches `findContactNameByKeyPrefix(_:)`.
     */
    suspend fun findContactNameByKeyPrefix(prefix: ByteArray): String? =
        dao.fetchAll().firstOrNull { it.publicKey.copyOfRange(0, minOf(prefix.size, it.publicKey.size)).contentEquals(prefix) }?.toDto()?.displayName

    /**
     * Advances `lastMessageDate` toward the per-contact max timestamp from a just-completed backup
     * import, keyed by local contact id. Existing values are preserved when already newer (e.g. a
     * DTO imported out of order). Ported from `applyLastMessageDatesToContacts`.
     */
    suspend fun applyLastMessageDatesToContacts(maxDates: Map<UUID, Instant>) {
        if (maxDates.isEmpty()) return
        for (contact in dao.fetchByIds(maxDates.keys.toList())) {
            val latest = maxDates[contact.id] ?: continue
            if (contact.lastMessageDate == null || contact.lastMessageDate!! < latest) {
                dao.update(contact.copy(lastMessageDate = latest))
            }
        }
    }
}

/**
 * Upper-bounds a phone-clock second stamp to `now + timestampToleranceFuture` (5 minutes),
 * saturating rather than overflowing near [ULong.MAX_VALUE]. Ported from
 * `PersistenceStore.clampedPhoneClockTimestamp(_:at:)`. In [ContactStore.touchContactHeard]'s own
 * call site [stamp] and [now] always derive from the same instant, so the clamp is structurally a
 * no-op there — it is ported anyway for behavioral fidelity, since the same helper also backs
 * [batchInsertContacts]'s newly-inserted-contact path, where the two instants do differ.
 */
private fun clampedPhoneClockTimestamp(stamp: ULong, now: Instant): ULong {
    val tolerance = TIMESTAMP_TOLERANCE_FUTURE_SECONDS
    val nowSeconds = now.epochSecond.toULong()
    val upperBound = if (nowSeconds > ULong.MAX_VALUE - tolerance) ULong.MAX_VALUE else nowSeconds + tolerance
    return minOf(stamp, upperBound)
}

/** 5 minutes, matching `SyncCoordinator.timestampToleranceFuture`. */
private val TIMESTAMP_TOLERANCE_FUTURE_SECONDS = (5uL * 60uL)
