// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.advertisement

import com.meshcoretwo.protocol.ContactType
import java.util.UUID

/**
 * Advertisement and discovery notifications broadcast by [AdvertisementService]. Ported from
 * `AdvertisementEvent.swift`, trimmed to the four cases this MVP slice actually emits.
 *
 * Not ported: [conversationsChanged]/`orphanDirectMessagesAdopted` (orphan-DM adoption isn't
 * ported — see [AdvertisementService]'s class doc), `pathDiscoveryResponse`/`traceResponse`/
 * `traceSnrObserved` (TracePath UI, not this port's event monitoring scope).
 */
sealed class AdvertisementEvent {
    /** A contact was created or updated; observers should reload contact lists. */
    object ContactUpdated : AdvertisementEvent()

    /** A new contact was discovered via a delta-sync round following an advertisement. */
    data class NewContactDiscovered(val name: String, val contactID: UUID, val contactType: ContactType) : AdvertisementEvent()

    /** The device's node storage full state changed (`true` = full, `false` = has space). */
    data class NodeStorageFullChanged(val isFull: Boolean) : AdvertisementEvent()

    /** The device auto-deleted a contact (overwrite-oldest); observers clean up its state. */
    data class ContactDeletedCleanup(val contactID: UUID, val publicKey: ByteArray) : AdvertisementEvent()
}
