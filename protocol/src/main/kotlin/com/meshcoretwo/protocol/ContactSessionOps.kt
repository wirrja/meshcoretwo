// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

/** Session operations for querying and managing the device's contact list. */
interface ContactSessionOps {
    /**
     * Retrieves contacts from the device.
     *
     * @param since An optional date for incremental synchronization.
     * @throws MeshCoreError if the contact query fails.
     */
    suspend fun getContacts(since: Instant? = null): List<MeshContact>

    /**
     * Retrieves contacts and the device's reported contact total.
     *
     * The total comes from the `contactsStart` header. On a full fetch (`since == null`) it is
     * the device's complete contact count, so a caller that prunes local rows can detect a
     * truncated stream.
     *
     * @param since An optional date for incremental synchronization.
     * @throws MeshCoreError if the contact query fails.
     */
    suspend fun getContactsReportingTotal(since: Instant? = null): ContactFetchResult

    /**
     * Fetches a single contact from the device by public key.
     *
     * @param publicKey The full 32-byte public key of the contact.
     * @return The contact if found, or `null` if no contact exists with that key.
     * @throws MeshCoreError if the query fails.
     */
    suspend fun getContact(publicKey: ByteArray): MeshContact?

    /**
     * Adds a contact to the device.
     *
     * @throws MeshCoreError if the contact cannot be added.
     */
    suspend fun addContact(contact: MeshContact)

    /**
     * Removes a contact from the device.
     *
     * @throws MeshCoreError if the contact cannot be removed.
     */
    suspend fun removeContact(publicKey: ByteArray)

    /**
     * Resets the path to a contact.
     *
     * Triggers path re-discovery for the specified contact by clearing existing routing info.
     *
     * @throws MeshCoreError if the path reset command fails.
     */
    suspend fun resetPath(publicKey: ByteArray)

    /**
     * Sends a path discovery request to a contact.
     *
     * @return A [MessageSentInfo] containing information about the discovery request.
     * @throws MeshCoreError if the discovery request fails.
     */
    suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo

    /**
     * Shares a contact via zero-hop broadcast.
     *
     * @param publicKey The contact's 32-byte public key.
     * @throws MeshCoreError if the share fails.
     */
    suspend fun shareContact(publicKey: ByteArray)

    /**
     * Exports a contact to a shareable URI.
     *
     * @param publicKey The contact's public key (`null` for self).
     * @throws MeshCoreError if the export fails.
     */
    suspend fun exportContact(publicKey: ByteArray? = null): String

    /**
     * Imports a contact from card data.
     *
     * @throws MeshCoreError if the import fails.
     */
    suspend fun importContact(cardData: ByteArray)

    /**
     * Updates a contact's flags on the device.
     *
     * Use this to modify contact flags (e.g., favorite bit) while preserving other contact data.
     *
     * @throws MeshCoreError if the update fails.
     */
    suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags)
}
