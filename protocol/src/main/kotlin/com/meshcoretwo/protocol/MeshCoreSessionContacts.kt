// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.select
import java.time.Instant

/** Result of a contact-stream exchange: the [ContactManager] update is applied by the caller. */
private data class ContactsFetch(val contacts: List<MeshContact>, val modifiedDate: Instant?, val reportedTotal: Int?)

/**
 * Only ever exits by throwing [MeshCoreError.Timeout]; declared to return [Nothing] explicitly
 * because unlike a lambda ending in `throw`, a `while (true)` loop's static type is always
 * `Unit` in Kotlin (Swift infers `Never` for the equivalent), which would otherwise mismatch
 * the [ContactsFetch] the racing consumer produces.
 */
private suspend fun contactStreamWatchdogLoop(progressTracker: StreamProgressTracker, hardTimeoutMs: Long, inactivityTimeoutMs: Long): Nothing {
    while (true) {
        val before = progressTracker.snapshot()
        if (before.elapsed.toMillis() >= hardTimeoutMs) throw MeshCoreError.Timeout
        val remainingHard = maxOf(1L, hardTimeoutMs - before.elapsed.toMillis())
        delay(minOf(inactivityTimeoutMs, remainingHard))
        val after = progressTracker.snapshot()
        if (after.elapsed.toMillis() >= hardTimeoutMs || after.generation == before.generation) {
            throw MeshCoreError.Timeout
        }
    }
}

// MARK: - Contact Management

/** Returns the currently cached contacts, without making a device request. */
suspend fun MeshCoreSession.cachedContacts(): List<MeshContact> = withContactManager { it.cachedContacts }

/** Returns pending contacts awaiting confirmation. Use [addContact] to add them permanently. */
suspend fun MeshCoreSession.cachedPendingContacts(): List<MeshContact> = withContactManager { it.cachedPendingContacts }

/** Finds a contact by advertised name. */
suspend fun MeshCoreSession.getContactByName(name: String, exactMatch: Boolean = false): MeshContact? =
    withContactManager { it.getByName(name, exactMatch) }

/** Removes and returns a pending contact. */
suspend fun MeshCoreSession.popPendingContact(publicKey: String): MeshContact? =
    withContactManager { it.popPending(publicKey) }

/** Removes all pending contacts from the cache. */
suspend fun MeshCoreSession.flushPendingContacts() {
    withContactManager { it.flushPending() }
}

/** Finds a contact by public key prefix (hex string). */
suspend fun MeshCoreSession.getContactByKeyPrefix(prefix: String): MeshContact? =
    withContactManager { it.getByKeyPrefix(prefix) }

/** Finds a contact by public key prefix (raw bytes). */
suspend fun MeshCoreSession.getContactByKeyPrefix(prefix: ByteArray): MeshContact? =
    withContactManager { it.getByKeyPrefix(prefix) }

/** Whether contacts have been modified since the last fetch, or never populated. */
suspend fun MeshCoreSession.isContactsDirty(): Boolean = withContactManager { it.needsRefresh }

/** Enables or disables automatic contact refresh on advertisements/path updates. */
suspend fun MeshCoreSession.setAutoUpdateContacts(enabled: Boolean) {
    withContactManager { it.setAutoUpdate(enabled) }
}

/**
 * Ensures contacts are loaded, fetching from device if needed.
 *
 * @param force If `true`, always fetches from device. If `false`, uses cached contacts if
 *   available and not dirty.
 */
suspend fun MeshCoreSession.ensureContacts(force: Boolean = false): List<MeshContact> {
    val needsFetch = force || withContactManager { it.needsRefresh || it.isEmpty }
    if (needsFetch) {
        val lastModified = withContactManager { it.contactsLastModified }
        return getContactsImpl(since = lastModified)
    }
    return cachedContacts()
}

/**
 * Fetches contacts from the device.
 *
 * @param since If provided, only returns contacts modified after this date. `null` fetches all.
 */
internal suspend fun MeshCoreSession.getContactsImpl(since: Instant? = null): List<MeshContact> = fetchContacts(since).contacts

/**
 * Fetches contacts and the device's reported contact total.
 *
 * The total comes from the `contactsStart` header. On a full fetch (`since == null`) it is the
 * device's complete contact count, so a prune caller can skip when the received count is below
 * that total.
 */
internal suspend fun MeshCoreSession.getContactsReportingTotalImpl(since: Instant? = null): ContactFetchResult {
    val fetch = fetchContacts(since)
    return ContactFetchResult(contacts = fetch.contacts, reportedTotal = fetch.reportedTotal)
}

/**
 * Races an event consumer (accumulating contacts until `ContactsEnd`) against a watchdog
 * (inactivity/hard timeout), mirroring Swift's `withThrowingTaskGroup` race — see
 * [MeshCoreSession] class doc for why suspend calls stay outside any lock here.
 */
private suspend fun MeshCoreSession.fetchContacts(since: Instant?): ContactsFetch {
    val fetch = requestResponseSerializer.withSerialization {
        val data = PacketBuilder.getContacts(since)
        val (subscriptionId, events) = dispatcher.subscribeTracked()

        try {
            transport.send(data)

            val progressTracker = StreamProgressTracker()
            val inactivityTimeoutMs = (configuration.contactStreamInactivityTimeout * 1000).toLong()
            val hardTimeoutMs = (configuration.contactStreamHardTimeout * 1000).toLong()

            coroutineScope {
                val consumer = async {
                    val channel = events.produceIn(this)
                    try {
                        val receivedContacts = mutableListOf<MeshContact>()
                        var finalModifiedDate: Instant? = null
                        var reportedTotal: Int? = null
                        for (event in channel) {
                            when (event) {
                                is MeshEvent.ContactsStart -> {
                                    progressTracker.markProgress()
                                    reportedTotal = event.count
                                }
                                is MeshEvent.Contact -> {
                                    progressTracker.markProgress()
                                    receivedContacts.add(event.contact)
                                }
                                is MeshEvent.ContactsEnd -> {
                                    progressTracker.markProgress()
                                    finalModifiedDate = event.lastModified
                                    return@async ContactsFetch(receivedContacts, finalModifiedDate, reportedTotal)
                                }
                                is MeshEvent.Error -> throw MeshCoreError.DeviceError(event.code ?: 0u)
                                else -> {}
                            }
                        }
                        throw MeshCoreError.Timeout
                    } finally {
                        channel.cancel()
                    }
                }

                val watchdog = async {
                    contactStreamWatchdogLoop(progressTracker, hardTimeoutMs, inactivityTimeoutMs)
                }

                try {
                    select<ContactsFetch> {
                        consumer.onAwait { it }
                        watchdog.onAwait { it }
                    }
                } finally {
                    consumer.cancel()
                    watchdog.cancel()
                }
            }
        } finally {
            dispatcher.finishSubscription(subscriptionId)
        }
    }

    withContactManager { manager ->
        for (contact in fetch.contacts) manager.store(contact)
        fetch.modifiedDate?.let { manager.markClean(it) }
    }
    return fetch
}

/**
 * Fetches a single contact from the device by public key.
 *
 * @throws MeshCoreError.Timeout if the device doesn't emit a matching contact response.
 */
internal suspend fun MeshCoreSession.getContactImpl(publicKey: ByteArray): MeshContact? {
    requireFullPublicKey(publicKey, "getContact")
    val data = PacketBuilder.getContactByKey(publicKey)
    return sendAndMatch(data) { event ->
        when (event) {
            is MeshEvent.Contact ->
                if (event.contact.publicKey.contentEquals(publicKey)) {
                    ResponseDisposition.Success(event.contact)
                } else {
                    ResponseDisposition.Ignore
                }
            // Contact not found returns error, treat as null.
            is MeshEvent.Error -> ResponseDisposition.Success(null)
            else -> ResponseDisposition.Ignore
        }
    }
}

// MARK: - Contact Commands

/** Resets the routing path for a contact, forcing the device to rediscover the route. */
internal suspend fun MeshCoreSession.resetPathImpl(publicKey: ByteArray) {
    requireFullPublicKey(publicKey, "resetPath")
    sendSimpleCommand(PacketBuilder.resetPath(publicKey))
}

/** Removes a contact from the device's contact list. */
internal suspend fun MeshCoreSession.removeContactImpl(publicKey: ByteArray) {
    requireFullPublicKey(publicKey, "removeContact")
    sendSimpleCommand(PacketBuilder.removeContact(publicKey))
}

/** Shares a contact with nearby devices via zero-hop broadcast. */
internal suspend fun MeshCoreSession.shareContactImpl(publicKey: ByteArray) {
    requireFullPublicKey(publicKey, "shareContact")
    sendSimpleCommand(PacketBuilder.shareContact(publicKey))
}

/**
 * Exports a contact as a shareable URI string.
 *
 * @param publicKey The contact's public key, or `null` to export self.
 */
internal suspend fun MeshCoreSession.exportContactImpl(publicKey: ByteArray? = null): String {
    if (publicKey != null) requireFullPublicKey(publicKey, "exportContact")
    val requestedKeyHex = publicKey?.hexString
    return sendAndWait(PacketBuilder.exportContact(publicKey)) { event ->
        val uri = (event as? MeshEvent.ContactURI)?.uri ?: return@sendAndWait null
        // The exported card begins with the contact's public key, so a card for a different
        // contact (e.g. an orphan from a cancelled export whose response lands after this
        // command's timeout) is rejected rather than returned.
        if (requestedKeyHex != null && !uri.contains(requestedKeyHex)) return@sendAndWait null
        uri
    }
}

/** Imports a contact from encoded contact card data. */
internal suspend fun MeshCoreSession.importContactImpl(cardData: ByteArray) {
    sendSimpleCommand(byteArrayOf(CommandCode.IMPORT_CONTACT.value.toByte()) + cardData)
}

/**
 * Updates or creates a contact with full details.
 *
 * Consider using [addContact], [changeContactPath], or [changeContactFlags] instead.
 */
suspend fun MeshCoreSession.updateContact(
    publicKey: ByteArray,
    type: ContactType,
    flags: ContactFlags,
    outPathLength: UByte,
    outPath: ByteArray,
    advertisedName: String,
    lastAdvertisement: Instant,
    latitude: Double,
    longitude: Double,
) {
    // PacketBuilder.updateContact(MeshContact) already encodes the wire packet from a full
    // MeshContact, so this builds one instead of duplicating the byte layout Swift's session
    // method hand-rolls. id/typeRawValue/lastModified aren't on the wire; any value satisfies
    // the constructor.
    val contact = MeshContact(
        id = publicKey.hexString,
        publicKey = publicKey,
        type = type,
        flags = flags,
        outPathLength = outPathLength,
        outPath = outPath,
        advertisedName = advertisedName,
        lastAdvertisement = lastAdvertisement,
        latitude = latitude,
        longitude = longitude,
        lastModified = lastAdvertisement,
    )
    sendSimpleCommand(PacketBuilder.updateContact(contact))
}

/**
 * Adds a contact to the device's contact list.
 *
 * Forwards the raw [MeshContact.typeRawValue] byte and uses the saturating coordinate/timestamp
 * encoders in [PacketBuilder.updateContact], so a contact carrying a type byte not modeled by
 * [ContactType] (e.g. from an imported config) reaches the device verbatim instead of being
 * coerced.
 */
internal suspend fun MeshCoreSession.addContactImpl(contact: MeshContact) {
    sendSimpleCommand(PacketBuilder.updateContact(contact))
}

/**
 * Changes the routing path for a contact, preserving all other contact information.
 *
 * @param hashSize Bytes per path hop (1, 2, or 3). Defaults to 1 for backward compatibility.
 */
suspend fun MeshCoreSession.changeContactPath(contact: MeshContact, path: ByteArray, hashSize: UByte = 1u) {
    val pathLength = if (path.isEmpty()) {
        0xFFu.toUByte()
    } else {
        encodePathLen(hashSize = hashSize.toInt(), hopCount = path.size / hashSize.toInt())
    }
    updateContact(
        publicKey = contact.publicKey,
        type = contact.type,
        flags = contact.flags,
        outPathLength = pathLength,
        outPath = path,
        advertisedName = contact.advertisedName,
        lastAdvertisement = contact.lastAdvertisement,
        latitude = contact.latitude,
        longitude = contact.longitude,
    )
}

/** Changes the flags for a contact, preserving all other contact information. */
internal suspend fun MeshCoreSession.changeContactFlagsImpl(contact: MeshContact, flags: ContactFlags) {
    updateContact(
        publicKey = contact.publicKey,
        type = contact.type,
        flags = flags,
        outPathLength = contact.outPathLength,
        outPath = contact.outPath,
        advertisedName = contact.advertisedName,
        lastAdvertisement = contact.lastAdvertisement,
        latitude = contact.latitude,
        longitude = contact.longitude,
    )
}
