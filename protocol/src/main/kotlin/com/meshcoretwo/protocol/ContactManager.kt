// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Manages contact storage, caching, and lifecycle.
 *
 * Owned exclusively by [MeshCoreSession] and only ever touched from within a section already
 * serialized by the session, so (like Swift's non-`Sendable` struct instance isolated to the
 * session actor) this needs no synchronization of its own.
 *
 * Swift's version logs via `os.Logger`, which has no equivalent in this pure-JVM module; those
 * diagnostic calls are dropped here rather than replaced, matching every other file in this
 * module (`protocol` takes no logging dependency — see [EventDispatcher], [MockTransport]).
 */
class ContactManager {
    private val contacts = LinkedHashMap<String, MeshContact>()
    private val pendingContacts = LinkedHashMap<String, MeshContact>()
    private var lastModified: java.time.Instant? = null
    private var isDirty = true
    private var autoUpdate = false

    /** Retrieves all currently cached contacts. */
    val cachedContacts: List<MeshContact> get() = contacts.values.toList()

    /** Retrieves all pending contacts awaiting confirmation. */
    val cachedPendingContacts: List<MeshContact> get() = pendingContacts.values.toList()

    /** Indicates whether the contact cache needs to be refreshed from the device. */
    val needsRefresh: Boolean get() = isDirty

    /** Retrieves the last modified date of the contacts as reported by the device. */
    val contactsLastModified: java.time.Instant? get() = lastModified

    /** Indicates whether the contact cache is empty. */
    val isEmpty: Boolean get() = contacts.isEmpty()

    /**
     * Finds a contact by their advertised name.
     *
     * @param exactMatch If `true`, requires an exact case-insensitive match; otherwise falls back
     *   to a plain case-insensitive substring search (Swift's `localizedStandardContains` has no
     *   JVM equivalent; this is a deliberate, low-risk simplification).
     */
    fun getByName(name: String, exactMatch: Boolean = false): MeshContact? {
        return if (exactMatch) {
            contacts.values.firstOrNull { it.advertisedName.equals(name, ignoreCase = true) }
        } else {
            contacts.values.firstOrNull { it.advertisedName.contains(name, ignoreCase = true) }
        }
    }

    /** Finds a contact by their public key prefix (hex string). */
    fun getByKeyPrefix(prefix: String): MeshContact? {
        val normalizedPrefix = prefix.lowercase()
        return contacts.values.firstOrNull { it.publicKey.hexString.lowercase().startsWith(normalizedPrefix) }
    }

    /** Finds a contact by their public key prefix (raw bytes). */
    fun getByKeyPrefix(prefix: ByteArray): MeshContact? {
        return contacts.values.firstOrNull { it.publicKey.prefixBytes(prefix.size).contentEquals(prefix) }
    }

    /** Finds a contact by their full public key. */
    fun getByPublicKey(key: ByteArray): MeshContact? = contacts[key.hexString]

    /** Stores a single contact in the cache. */
    fun store(contact: MeshContact) {
        contacts[contact.id] = contact
    }

    /** Updates the contact cache with new contacts and a modification date. */
    fun updateCache(newContacts: List<MeshContact>, lastModified: java.time.Instant) {
        for (contact in newContacts) {
            contacts[contact.id] = contact
        }
        this.lastModified = lastModified
        isDirty = false
    }

    /** Marks the cache as clean (synchronized with the device). */
    fun markClean(lastModified: java.time.Instant) {
        this.lastModified = lastModified
        isDirty = false
    }

    /** Marks the cache as needing a refresh from the device. */
    fun markDirty() {
        isDirty = true
    }

    /** Adds a contact to the pending contacts list. */
    fun addPending(contact: MeshContact) {
        pendingContacts[contact.id] = contact
    }

    /** Removes and returns a pending contact by their public key hex string. */
    fun popPending(publicKey: String): MeshContact? = pendingContacts.remove(publicKey)

    /** Removes all contacts from the pending list. */
    fun flushPending() {
        pendingContacts.clear()
    }

    /** Removes a contact from both the active and pending caches. */
    fun remove(contactId: String) {
        contacts.remove(contactId)
        pendingContacts.remove(contactId)
        isDirty = true
    }

    /** Clears all cached contact data and marks the cache as dirty. */
    fun clear() {
        contacts.clear()
        pendingContacts.clear()
        lastModified = null
        isDirty = true
    }

    /** Indicates whether automatic contact updates are enabled. */
    val isAutoUpdateEnabled: Boolean get() = autoUpdate

    /** Enables or disables automatic contact updates based on device events. */
    fun setAutoUpdate(enabled: Boolean) {
        autoUpdate = enabled
    }

    /** Tracks contact changes based on events received from the device. */
    fun trackChanges(event: MeshEvent) {
        when (event) {
            is MeshEvent.Contact -> contacts[event.contact.id] = event.contact
            is MeshEvent.NewContact -> {
                addPending(event.contact)
                isDirty = true
            }
            is MeshEvent.ContactsEnd -> {
                lastModified = event.lastModified
                isDirty = false
            }
            is MeshEvent.Advertisement, is MeshEvent.PathUpdate -> isDirty = true
            is MeshEvent.ContactDeleted -> {
                val contactId = event.publicKey.hexString
                contacts.remove(contactId)
                pendingContacts.remove(contactId)
                isDirty = true
            }
            is MeshEvent.ContactsFull -> isDirty = true
            else -> {}
        }
    }
}
