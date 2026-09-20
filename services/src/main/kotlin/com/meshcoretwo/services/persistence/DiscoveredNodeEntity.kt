// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.meshcoretwo.protocol.MeshContact
import java.time.Instant
import java.util.UUID

/**
 * A node heard via advertisement but not (yet) added as a contact — the "Discover" list. Ported
 * from `DiscoveredNode.swift`'s `@Model` (SwiftData) to a Room `@Entity`, same
 * lossless-signed-widening convention as [ContactEntity] (see its class doc) for the [UByte]/
 * [UInt] wire fields Room's KSP can't handle directly.
 *
 * Ephemeral and app-only, capped at [DiscoveredNodeStore.MAX_DISCOVERED_NODES] rows per device
 * (enforced in [DiscoveredNodeStore], not here) — unlike [ContactEntity], nothing here round-trips
 * back to the radio.
 */
@Entity(
    tableName = "discovered_nodes",
    indices = [
        Index(value = ["radioID", "publicKey"], unique = true),
        Index(value = ["radioID", "lastHeard"]),
    ],
)
data class DiscoveredNodeEntity(
    @PrimaryKey val id: UUID,
    /** Parent device — partition key, not the volatile BLE address. */
    val radioID: UUID,
    val publicKey: ByteArray,
    val name: String,
    val typeRawValue: Int,
    /** When we last received an advertisement from this node. */
    val lastHeard: Instant,
    val lastAdvertTimestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val outPathLength: Int,
    val outPath: ByteArray,
    /**
     * Hops the advert traversed to reach this phone (inbound), decoded from the heard RX-log
     * packet's path length. `null` = never heard via an advert RX-log entry; 0 = heard directly.
     * Distinct from [outPathLength]/[outPath], the route used to send *to* this node.
     */
    val inboundHopCount: Int?,
    /**
     * The firmware advert timestamp that was current when [inboundHopCount] was last written.
     * Paired with it to implement latest-advert semantics — see [adoptInboundHop].
     */
    val inboundHopAdvertTimestamp: Long?,
) {
    companion object {
        /** Builds a brand-new row from a freshly-heard advertisement, matching `DiscoveredNode.init`. */
        fun fromMeshContact(id: UUID, radioID: UUID, contact: MeshContact, lastHeard: Instant): DiscoveredNodeEntity =
            DiscoveredNodeEntity(
                id = id,
                radioID = radioID,
                publicKey = contact.publicKey,
                name = contact.advertisedName,
                typeRawValue = contact.typeRawValue.toInt(),
                lastHeard = lastHeard,
                lastAdvertTimestamp = contact.lastAdvertisement.epochSecond,
                latitude = contact.latitude,
                longitude = contact.longitude,
                outPathLength = contact.outPathLength.toInt(),
                outPath = contact.outPath,
                inboundHopCount = null,
                inboundHopAdvertTimestamp = null,
            )
    }

    /**
     * Applies a freshly-heard advertisement onto this existing row, matching the update branch of
     * `PersistenceStore.upsertDiscoveredNode`: wire fields refresh and [lastHeard] is re-stamped;
     * [inboundHopCount]/[inboundHopAdvertTimestamp] are untouched here (a separate concern, see
     * [adoptInboundHop]).
     */
    fun updatedFrom(contact: MeshContact, lastHeard: Instant): DiscoveredNodeEntity = copy(
        name = contact.advertisedName,
        typeRawValue = contact.typeRawValue.toInt(),
        lastHeard = lastHeard,
        lastAdvertTimestamp = contact.lastAdvertisement.epochSecond,
        latitude = contact.latitude,
        longitude = contact.longitude,
        outPathLength = contact.outPathLength.toInt(),
        outPath = contact.outPath,
    )
}
