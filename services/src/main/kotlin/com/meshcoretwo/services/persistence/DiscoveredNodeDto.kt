// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.decodePathLen
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.RepeaterResolvable
import java.time.Instant
import java.util.UUID

/**
 * An immutable, [DiscoveredNodeEntity]-independent snapshot of a discovered ("Discover" list)
 * node. Ported from `DiscoveredNodeDTO` (`DiscoveredNode.swift`), same split as [ContactDto]
 * (entity stays behind the DAO/store, this crosses it).
 *
 * Conforms to [RepeaterResolvable] so `app`'s `RepeaterResolver`/Trace Path hop picker can match a
 * hash prefix against discovered nodes the same way it already does contacts. [recencyValue] uses
 * [lastHeard]'s epoch second truncated to [UInt] — the same "recency as a raw timestamp" shape
 * [ContactDto.lastModified] already exposes there — standing in for Swift's `recencyDate: Date`.
 *
 * Not ported: `pathNodesHex` (a hex-path display helper with no caller anywhere in this port yet).
 */
data class DiscoveredNodeDto(
    val id: UUID,
    val radioID: UUID,
    override val publicKey: ByteArray,
    val name: String,
    val typeRawValue: UByte,
    val lastHeard: Instant,
    override val lastAdvertTimestamp: UInt,
    override val latitude: Double,
    override val longitude: Double,
    val outPathLength: UByte,
    val outPath: ByteArray,
    val inboundHopCount: Int?,
    val inboundHopAdvertTimestamp: UInt?,
) : RepeaterResolvable {
    val nodeType: ContactType get() = ContactType.fromValue(typeRawValue) ?: ContactType.CHAT

    override val resolvableName: String get() = name

    override val recencyValue: UInt get() = lastHeard.epochSecond.toUInt()

    override val hasLocation: Boolean
        get() = latitude != 0.0 || longitude != 0.0

    val isFloodRouted: Boolean get() = outPathLength == PacketBuilder.FLOOD_PATH_SENTINEL

    val pathHashSize: Int get() = decodePathLen(outPathLength)?.hashSize ?: 1

    val pathHopCount: Int get() = decodePathLen(outPathLength)?.hopCount ?: 0

    val pathByteLength: Int get() = decodePathLen(outPathLength)?.byteLength ?: 0

    /**
     * The hop count to surface in the UI: the deliberately-set out-path hops when a route exists,
     * otherwise the passively-heard inbound advert hops stored on this row. `null` when
     * flood-routed and no advert hop count is known.
     */
    val displayedHopCount: Int? get() = if (isFloodRouted) inboundHopCount else pathHopCount

    // ByteArray has reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveredNodeDto) return false
        return id == other.id &&
            radioID == other.radioID &&
            publicKey.contentEquals(other.publicKey) &&
            name == other.name &&
            typeRawValue == other.typeRawValue &&
            lastHeard == other.lastHeard &&
            lastAdvertTimestamp == other.lastAdvertTimestamp &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            outPathLength == other.outPathLength &&
            outPath.contentEquals(other.outPath) &&
            inboundHopCount == other.inboundHopCount &&
            inboundHopAdvertTimestamp == other.inboundHopAdvertTimestamp
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + typeRawValue.hashCode()
        result = 31 * result + lastHeard.hashCode()
        result = 31 * result + lastAdvertTimestamp.hashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + outPathLength.hashCode()
        result = 31 * result + outPath.contentHashCode()
        result = 31 * result + (inboundHopCount ?: 0)
        result = 31 * result + (inboundHopAdvertTimestamp?.hashCode() ?: 0)
        return result
    }
}

/**
 * A [MeshContact] built from this Discover row, for the "Add contact" action
 * (`ContactService.addOrUpdateContact`). Ported from `DiscoveredNodeDTO.makeContactFrame()`
 * (`DiscoveredNodeDTO+ContactFrame.swift`) — flags reset to none (Swift's `flags: 0`) since a
 * discovered node's flags were never authoritative contact state, and `lastModified` stamped to
 * now, matching Swift's default `lastModified: UInt32(Date().timeIntervalSince1970)`.
 */
fun DiscoveredNodeDto.toMeshContact(asOf: Instant = Instant.now()): MeshContact = MeshContact(
    id = publicKey.hexString,
    publicKey = publicKey,
    type = nodeType,
    typeRawValue = typeRawValue,
    flags = ContactFlags.NONE,
    outPathLength = outPathLength,
    outPath = outPath,
    advertisedName = name,
    lastAdvertisement = Instant.ofEpochSecond(lastAdvertTimestamp.toLong()),
    latitude = latitude,
    longitude = longitude,
    lastModified = asOf,
)

/** Maps a persisted row to the immutable snapshot services/UI consume. */
fun DiscoveredNodeEntity.toDto(): DiscoveredNodeDto = DiscoveredNodeDto(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = typeRawValue.toUByte(),
    lastHeard = lastHeard,
    lastAdvertTimestamp = lastAdvertTimestamp.toUInt(),
    latitude = latitude,
    longitude = longitude,
    outPathLength = outPathLength.toUByte(),
    outPath = outPath,
    inboundHopCount = inboundHopCount,
    inboundHopAdvertTimestamp = inboundHopAdvertTimestamp?.toUInt(),
)

/** The reverse of [DiscoveredNodeEntity.toDto] — backup import's batch-insert needs to persist an arbitrary [DiscoveredNodeDto], not just one built from a [MeshContact]. */
fun DiscoveredNodeDto.toEntity(): DiscoveredNodeEntity = DiscoveredNodeEntity(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = typeRawValue.toInt(),
    lastHeard = lastHeard,
    lastAdvertTimestamp = lastAdvertTimestamp.toLong(),
    latitude = latitude,
    longitude = longitude,
    outPathLength = outPathLength.toInt(),
    outPath = outPath,
    inboundHopCount = inboundHopCount,
    inboundHopAdvertTimestamp = inboundHopAdvertTimestamp?.toLong(),
)
