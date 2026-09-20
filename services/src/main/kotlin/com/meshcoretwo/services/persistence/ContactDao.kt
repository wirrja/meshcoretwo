// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [ContactEntity]. Deliberately dumb CRUD only — the upsert business logic that
 * decides whether to insert or update, and which fields survive an update, lives in
 * [ContactStore] (mirroring [ContactEntity.fromMeshContact]/[ContactEntity.updatedFrom]), not
 * here, matching PLAN.md's "Room-сущность живёт в DAO/репозитории" split.
 *
 * Ported from the subset of `PersistenceStore+Contacts.swift` this vertical slice covers.
 */
@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE radioID = :radioID ORDER BY name")
    suspend fun fetchContacts(radioID: UUID): List<ContactEntity>

    /** Same rows as [fetchContacts], but as a live Room query that re-emits on any write to `contacts`. */
    @Query("SELECT * FROM contacts WHERE radioID = :radioID ORDER BY name")
    fun observeContacts(radioID: UUID): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    suspend fun fetchContact(id: UUID): ContactEntity?

    @Query("SELECT * FROM contacts WHERE radioID = :radioID AND publicKey = :publicKey LIMIT 1")
    suspend fun fetchContact(radioID: UUID, publicKey: ByteArray): ContactEntity?

    /** Every contact across every device — see [ContactStore.findContactByPublicKey]/`findContactNameByKeyPrefix` for why this crosses radios. */
    @Query("SELECT * FROM contacts")
    suspend fun fetchAll(): List<ContactEntity>

    /** Every contact across the given devices — backup import's existing-row lookup, scoped to just the radios being touched. Ported from `fetchExistingContactsByKey`. */
    @Query("SELECT * FROM contacts WHERE radioID IN (:radioIDs)")
    suspend fun fetchAll(radioIDs: List<UUID>): List<ContactEntity>

    /** Contacts by local id — backup import's last-message-date reconciliation, which already has local ids from ID-mapping. Ported from the `Contact` fetch in `applyLastMessageDatesToContacts`. */
    @Query("SELECT * FROM contacts WHERE id IN (:ids)")
    suspend fun fetchByIds(ids: List<UUID>): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE radioID = :radioID AND isBlocked = 1 ORDER BY name")
    suspend fun fetchBlockedContacts(radioID: UUID): List<ContactEntity>

    @Insert
    suspend fun insert(contact: ContactEntity)

    @Update
    suspend fun update(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteContact(id: UUID)

    /** Every contact for [radioID] — [DeviceStore.deleteDeviceAndData]'s cascade. */
    @Query("DELETE FROM contacts WHERE radioID = :radioID")
    suspend fun deleteAll(radioID: UUID)
}
